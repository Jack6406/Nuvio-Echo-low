package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.ui.newlook.underSidebar
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.bins.Bin
import com.nuvio.tv.core.bins.BinSort
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackInfo
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private enum class LibraryTab { Classic, Bins, Cloud }
private enum class ClassicMediaFilter { ALL, MOVIES, SERIES, WATCHED_HISTORY }

private sealed interface LibraryOverlay {
    data class Sort(val anchor: Rect) : LibraryOverlay
    data class GenreFilter(val anchor: Rect) : LibraryOverlay
    data class YearFilter(val anchor: Rect) : LibraryOverlay
    data class BinOptions(val bin: Bin, val anchor: Rect) : LibraryOverlay
    data class TitleOptions(val bin: Bin, val title: BinTitle, val anchor: Rect) : LibraryOverlay
    data object Create : LibraryOverlay
    data class Rename(val bin: Bin) : LibraryOverlay
    data class Delete(val bin: Bin) : LibraryOverlay
}

/** Stable focus requesters by key ("pill:sort", "bin:<id>", "star:<id>", "title:<key>"). */
private class FocusKeys {
    private val map = HashMap<String, FocusRequester>()
    operator fun get(key: String): FocusRequester = map.getOrPut(key) { FocusRequester() }
}

private val ScreenPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp)
private val GridGap = 24.dp

/**
 * The Library: Unified 2:3 Portrait Poster Grid with All / Movies / Series / Watched History filters
 * and a Random Movie quick-select pill.
 */
@Composable
fun LibraryScreen(
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onCloudPlaybackResolved: (CloudLibraryPlaybackInfo) -> Unit = {},
    binsViewModel: BinsViewModel = hiltViewModel(),
    cloudViewModel: CloudLibraryViewModel = hiltViewModel()
) {
    val state by binsViewModel.uiState.collectAsState()
    val message by binsViewModel.message.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Classic) }
    var classicFilter by rememberSaveable { mutableStateOf(ClassicMediaFilter.ALL) }
    var selectedGenre by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedYear by rememberSaveable { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<LibraryOverlay?>(null) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var sortBounds by remember { mutableStateOf(Rect.Zero) }
    var genreBounds by remember { mutableStateOf(Rect.Zero) }
    var yearBounds by remember { mutableStateOf(Rect.Zero) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    var refocusAfterSwap by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusKeys() }
    val classicGrid = rememberLazyGridState()
    val contentFocus = LocalContentFocusRequester.current

    val sourcePoolForFilters = remember(classicFilter, state.allTitles, state.watchedHistoryTitles) {
        if (classicFilter == ClassicMediaFilter.WATCHED_HISTORY) state.watchedHistoryTitles else state.allTitles
    }

    val availableGenres = remember(sourcePoolForFilters) {
        val fromItems = sourcePoolForFilters
            .flatMap { it.entry.genres + it.preview.genres }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.none(Char::isDigit) }
        val standardGenres = listOf(
            "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
            "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
            "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western"
        )
        (fromItems + standardGenres).distinctBy { it.lowercase() }.sorted()
    }

    val availableYears = remember(sourcePoolForFilters) {
        val yearRegex = Regex("\\b(19\\d{2}|20\\d{2})\\b")
        val exactYears = sourcePoolForFilters
            .mapNotNull { title ->
                yearRegex.find(title.entry.releaseInfo.orEmpty())?.value
                    ?: yearRegex.find(title.preview.releaseInfo.orEmpty())?.value
            }
            .distinct()
            .sortedDescending()
        val recentYears = (2026 downTo 1980).map { it.toString() }
        val decades = listOf("2020s", "2010s", "2000s", "1990s", "Classic (<1990)")
        (decades + (exactYears + recentYears).distinct().sortedDescending()).distinct()
    }

    fun matchesGenreAndYear(title: BinTitle): Boolean {
        val g = selectedGenre
        val itemGenres = (title.entry.genres + title.preview.genres).map { it.trim() }
        if (g != null && itemGenres.none { it.equals(g, ignoreCase = true) || (g.equals("Sci-Fi", true) && it.equals("Science Fiction", true)) }) {
            return false
        }
        val y = selectedYear
        if (y != null) {
            val rawYearText = "${title.entry.releaseInfo.orEmpty()} ${title.preview.releaseInfo.orEmpty()}"
            val yearInt = Regex("\\b(19\\d{2}|20\\d{2})\\b")
                .find(rawYearText)
                ?.value
                ?.toIntOrNull() ?: return false
            val matchesY = when (y) {
                "2020s" -> yearInt in 2020..2029
                "2010s" -> yearInt in 2010..2019
                "2000s" -> yearInt in 2000..2009
                "1990s" -> yearInt in 1990..1999
                "Classic (<1990)" -> yearInt < 1990
                else -> yearInt.toString() == y
            }
            if (!matchesY) return false
        }
        return true
    }

    val filteredClassicTitles = remember(state.allTitles, state.watchedHistoryTitles, classicFilter, selectedGenre, selectedYear) {
        val mediaFiltered = when (classicFilter) {
            ClassicMediaFilter.ALL -> state.allTitles
            ClassicMediaFilter.MOVIES -> state.allTitles.filter {
                !it.entry.type.equals("series", true) && !it.entry.type.equals("tv", true)
            }
            ClassicMediaFilter.SERIES -> state.allTitles.filter {
                it.entry.type.equals("series", true) || it.entry.type.equals("tv", true)
            }
            ClassicMediaFilter.WATCHED_HISTORY -> state.watchedHistoryTitles
        }
        mediaFiltered.filter(::matchesGenreAndYear)
    }

    fun local(rect: Rect): Rect = rect.translate(-rootOffset)
    fun dismissOverlay(returnTo: String?) {
        overlay = null
        if (returnTo != null) pendingFocus = returnTo
    }

    LaunchedEffect(state.loaded) {
        if (!state.loaded || pendingFocus != null) return@LaunchedEffect
        pendingFocus = lastFocused
            ?: filteredClassicTitles.firstOrNull()?.let { "classic:${it.entry.key}" }
            ?: "pill:filter_all"
    }

    LaunchedEffect(pendingFocus, filteredClassicTitles) {
        val key = pendingFocus ?: return@LaunchedEffect
        val (grid, index) = when {
            key.startsWith("classic:") ->
                classicGrid to filteredClassicTitles.indexOfFirst { it.entry.key == key.removePrefix("classic:") }.let { if (it < 0) -1 else it + 1 }
            else -> null to -1
        }
        if (grid != null && index > 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            runCatching { grid.scrollToItem(index) }
        }
        repeat(8) {
            if (runCatching { focus[key].requestFocus() }.getOrDefault(false)) {
                pendingFocus = null
                return@LaunchedEffect
            }
            delay(32)
        }
        pendingFocus = null
    }

    LaunchedEffect(state.bins, state.openTitles) {
        val key = refocusAfterSwap ?: return@LaunchedEffect
        refocusAfterSwap = null
        pendingFocus = key
    }

    val sortLabel = sortLabel(state.sort)
    val header: @Composable () -> Unit = {
        LibraryHeader(
            classicFilter = classicFilter,
            onClassicFilter = { classicFilter = it },
            selectedGenre = selectedGenre,
            selectedYear = selectedYear,
            onClearFilters = {
                selectedGenre = null
                selectedYear = null
            },
            sortLabel = sortLabel,
            focus = focus,
            onRandomMovie = {
                binsViewModel.pickRandomMovie { picked ->
                    val owningBin = state.bins.firstOrNull { b -> b.entries.any { it.key == picked.entry.key } }
                    if (owningBin != null) {
                        binsViewModel.onTitleOpened(owningBin, picked)
                    }
                    HeroBackdropState.update(binsViewModel.cachedBackdrop(picked))
                    onNavigateToDetail(picked.entry.id, picked.entry.type, picked.entry.addonBaseUrl)
                }
            },
            onSortBounds = { sortBounds = it },
            onSort = { overlay = LibraryOverlay.Sort(local(sortBounds)) },
            onGenreBounds = { genreBounds = it },
            onGenre = { overlay = LibraryOverlay.GenreFilter(local(genreBounds)) },
            onYearBounds = { yearBounds = it },
            onYear = { overlay = LibraryOverlay.YearFilter(local(yearBounds)) }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOffset = it.positionInRoot() }
    ) {
        Canvas(Modifier.fillMaxSize().underSidebar()) {
            drawRect(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF403A40), Color(0xFF29272D), Color(0xFF343137)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
            val radius = size.maxDimension * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x668F858E), Color(0x228F858E), Color.Transparent),
                    center = Offset(size.width * 0.78f, size.height * 0.12f),
                    radius = radius
                ),
                radius = radius,
                center = Offset(size.width * 0.78f, size.height * 0.12f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x444C5A61), Color(0x224C5A61), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.88f),
                    radius = radius * 0.82f
                ),
                radius = radius * 0.82f,
                center = Offset(size.width * 0.18f, size.height * 0.88f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusProperties {
                    onEnter = {
                        val key = pendingFocus ?: lastFocused
                            ?: filteredClassicTitles.firstOrNull()?.let { "classic:${it.entry.key}" }
                            ?: "pill:filter_all"
                        runCatching { focus[key].requestFocus() }
                    }
                }
                .focusGroup()
        ) {
            ClassicLibraryPortraitGrid(
                titles = filteredClassicTitles,
                isWatchedHistoryTab = classicFilter == ClassicMediaFilter.WATCHED_HISTORY,
                state = state,
                gridState = classicGrid,
                header = header,
                focus = focus,
                onFocused = { lastFocused = it },
                onClick = { title ->
                    val owningBin = state.bins.firstOrNull { b -> b.entries.any { it.key == title.entry.key } }
                    if (owningBin != null) {
                        binsViewModel.onTitleOpened(owningBin, title)
                    }
                    HeroBackdropState.update(binsViewModel.cachedBackdrop(title))
                    onNavigateToDetail(title.entry.id, title.entry.type, title.entry.addonBaseUrl)
                },
                onLongClick = { title, anchor ->
                    val owningBin = state.bins.firstOrNull { b -> b.entries.any { it.key == title.entry.key } }
                    if (owningBin != null) {
                        overlay = LibraryOverlay.TitleOptions(owningBin, title, local(anchor))
                    }
                },
                onPrefetch = { binsViewModel.prefetch(it.entry) }
            )
        }

        when (val current = overlay) {
            is LibraryOverlay.Sort -> BinPillMenu(
                anchor = current.anchor,
                title = null,
                items = BinSort.Menu.map { sort ->
                    BinMenuItem(sortLabelText(sort), checked = sort == state.sort) {
                        binsViewModel.selectSort(sort)
                        dismissOverlay("pill:sort")
                    }
                },
                initialIndex = BinSort.Menu.indexOf(state.sort).coerceAtLeast(0),
                openedByHold = false,
                onDismiss = { dismissOverlay("pill:sort") }
            )
            is LibraryOverlay.GenreFilter -> {
                val genreOptions = listOf<String?>(null) + availableGenres
                BinPillMenu(
                    anchor = current.anchor,
                    title = "Filter by Genre",
                    items = genreOptions.map { g ->
                        val label = g ?: "All Genres"
                        BinMenuItem(label, checked = g == selectedGenre) {
                            selectedGenre = g
                            dismissOverlay("pill:genre")
                        }
                    },
                    initialIndex = genreOptions.indexOf(selectedGenre).coerceAtLeast(0),
                    openedByHold = false,
                    onDismiss = { dismissOverlay("pill:genre") }
                )
            }
            is LibraryOverlay.YearFilter -> {
                val yearOptions = listOf<String?>(null) + availableYears
                BinPillMenu(
                    anchor = current.anchor,
                    title = "Filter by Year",
                    items = yearOptions.map { y ->
                        val label = y ?: "All Years"
                        BinMenuItem(label, checked = y == selectedYear) {
                            selectedYear = y
                            dismissOverlay("pill:year")
                        }
                    },
                    initialIndex = yearOptions.indexOf(selectedYear).coerceAtLeast(0),
                    openedByHold = false,
                    onDismiss = { dismissOverlay("pill:year") }
                )
            }
            is LibraryOverlay.TitleOptions -> BinPillMenu(
                anchor = current.anchor,
                title = current.title.entry.name,
                items = listOf(
                    BinMenuItem(stringResource(R.string.bins_title_open)) {
                        dismissOverlay(null)
                        binsViewModel.onTitleOpened(current.bin, current.title)
                        onNavigateToDetail(current.title.entry.id, current.title.entry.type, current.title.entry.addonBaseUrl)
                    },
                    BinMenuItem(stringResource(R.string.bins_title_remove, current.bin.name)) {
                        val titles = filteredClassicTitles
                        val prefix = "classic:"
                        val at = titles.indexOfFirst { it.entry.key == current.title.entry.key }
                        val next = titles.getOrNull(at + 1) ?: titles.getOrNull(at - 1)
                        binsViewModel.removeTitle(current.bin, current.title)
                        dismissOverlay(next?.let { "$prefix${it.entry.key}" } ?: "pill:filter_all")
                    }
                ),
                initialIndex = 0,
                openedByHold = true,
                onDismiss = {
                    dismissOverlay("classic:${current.title.entry.key}")
                }
            )
            else -> Unit
        }

        BinsToast(message, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}

@Composable
private fun LibraryHeader(
    classicFilter: ClassicMediaFilter,
    onClassicFilter: (ClassicMediaFilter) -> Unit,
    selectedGenre: String?,
    selectedYear: String?,
    onClearFilters: () -> Unit,
    sortLabel: String,
    focus: FocusKeys,
    onRandomMovie: () -> Unit,
    onSortBounds: (Rect) -> Unit,
    onSort: () -> Unit,
    onGenreBounds: (Rect) -> Unit,
    onGenre: () -> Unit,
    onYearBounds: (Rect) -> Unit,
    onYear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(stringResource(R.string.library_title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        val endOfRow = Modifier.focusProperties { right = FocusRequester.Cancel }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BinsTextPill(
                "All",
                selected = classicFilter == ClassicMediaFilter.ALL,
                requester = focus["pill:filter_all"],
                onClick = { onClassicFilter(ClassicMediaFilter.ALL) }
            )
            BinsTextPill(
                "Movies",
                selected = classicFilter == ClassicMediaFilter.MOVIES,
                requester = focus["pill:filter_movies"],
                onClick = { onClassicFilter(ClassicMediaFilter.MOVIES) }
            )
            BinsTextPill(
                "Series",
                selected = classicFilter == ClassicMediaFilter.SERIES,
                requester = focus["pill:filter_series"],
                onClick = { onClassicFilter(ClassicMediaFilter.SERIES) }
            )
            BinsTextPill(
                "Watched History",
                selected = classicFilter == ClassicMediaFilter.WATCHED_HISTORY,
                requester = focus["pill:watched_history"],
                onClick = { onClassicFilter(ClassicMediaFilter.WATCHED_HISTORY) }
            ) { color ->
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = "Watched History",
                    tint = color,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Watched History", color = color, fontSize = 15.sp, maxLines = 1)
            }
            BinsTextPill(
                "Random Movie",
                selected = false,
                requester = focus["pill:random_movie"],
                onClick = onRandomMovie
            ) { color ->
                Icon(
                    imageVector = Icons.Outlined.Shuffle,
                    contentDescription = "Random Movie",
                    tint = color,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Random Movie", color = color, fontSize = 15.sp, maxLines = 1)
            }
            BinsSortPill("Genre: ${selectedGenre ?: "All"}", focus["pill:genre"], onClick = onGenre, onBounds = onGenreBounds)
            BinsSortPill(
                "Year: ${selectedYear ?: "All"}",
                focus["pill:year"],
                onClick = onYear,
                onBounds = onYearBounds,
                modifier = if (classicFilter == ClassicMediaFilter.WATCHED_HISTORY && selectedGenre == null && selectedYear == null) endOfRow else Modifier
            )
            if (classicFilter != ClassicMediaFilter.WATCHED_HISTORY) {
                BinsSortPill(
                    sortLabel,
                    focus["pill:sort"],
                    onClick = onSort,
                    onBounds = onSortBounds,
                    modifier = if (selectedGenre == null && selectedYear == null) endOfRow else Modifier
                )
            }
            if (selectedGenre != null || selectedYear != null) {
                BinsTextPill("Reset", selected = false, requester = focus["pill:reset_filters"], onClick = onClearFilters, modifier = endOfRow)
            }
        }
    }
}

@Composable
private fun ClassicLibraryPortraitGrid(
    titles: List<BinTitle>,
    isWatchedHistoryTab: Boolean,
    state: BinsUiState,
    gridState: LazyGridState,
    header: @Composable () -> Unit,
    focus: FocusKeys,
    onFocused: (String) -> Unit,
    onClick: (BinTitle) -> Unit,
    onLongClick: (BinTitle, Rect) -> Unit,
    onPrefetch: (BinTitle) -> Unit
) {
    val style = PosterCardDefaults.Style.copy(
        width = state.posterWidthDp.dp,
        height = (state.posterWidthDp * 3 / 2).dp,
        cornerRadius = state.posterCornerDp.dp
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = style.width),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (state.loaded && titles.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyScreenState(
                    title = if (isWatchedHistoryTab) "No Watched History Yet" else stringResource(R.string.bins_empty_title),
                    subtitle = if (isWatchedHistoryTab) "Movies and episodes you watch will appear here chronologically." else stringResource(R.string.bins_empty_subtitle),
                    icon = if (isWatchedHistoryTab) Icons.Outlined.History else Icons.Outlined.Inventory2,
                    height = 260.dp
                )
            }
        }
        items(titles, key = { it.entry.key }) { title ->
            val key = title.entry.key
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val series = title.entry.type.equals("series", true) || title.entry.type.equals("tv", true)
            Box(
                Modifier
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .animateItem(
                        fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
                    )
            ) {
                GridContentCard(
                    item = title.preview,
                    posterCardStyle = style,
                    isWatched = isWatchedHistoryTab || (if (series) title.entry.id in state.watchedSeriesIds else title.entry.id in state.watchedMovieIds),
                    focusRequester = focus["classic:$key"],
                    showLabel = true,
                    onFocused = {
                        onFocused("classic:$key")
                        onPrefetch(title)
                    },
                    onClick = { onClick(title) },
                    onLongPress = { onLongClick(title, bounds) }
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: BinSort): String = sortLabelText(sort)

@Composable
private fun sortLabelText(sort: BinSort): String = stringResource(
    when (sort) {
        BinSort.CUSTOM -> R.string.bins_sort_custom
        BinSort.RECENTLY_VIEWED -> R.string.bins_sort_recent
        BinSort.ADDED_NEWEST -> R.string.library_sort_added_desc
        BinSort.ADDED_OLDEST -> R.string.library_sort_added_asc
        BinSort.TITLE_AZ -> R.string.library_sort_title_asc
        BinSort.TITLE_ZA -> R.string.library_sort_title_desc
    }
)
