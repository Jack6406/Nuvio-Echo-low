package com.nuvio.tv.ui.screens.library

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.bins.Bin
import com.nuvio.tv.core.bins.BinEntry
import com.nuvio.tv.core.bins.BinNameResult
import com.nuvio.tv.core.bins.BinSort
import com.nuvio.tv.core.bins.BinsReducer
import com.nuvio.tv.core.bins.BinsRepository
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A title in an open bin, ready to draw. */
@Immutable
data class BinTitle(val entry: BinEntry, val preview: MetaPreview)

@Immutable
data class BinsUiState(
    val loaded: Boolean = false,
    /** Every bin, in the chosen sort. */
    val bins: List<Bin> = emptyList(),
    val sort: BinSort = BinSort.RECENTLY_VIEWED,
    /** The bin being looked inside, if any. */
    val openBin: Bin? = null,
    val openTitles: List<BinTitle> = emptyList(),
    /** All unique saved titles across all bins for the Classic Library 2:3 portrait grid. */
    val allTitles: List<BinTitle> = emptyList(),
    /** All watched or recently played movies and series sorted by most recently watched. */
    val watchedHistoryTitles: List<BinTitle> = emptyList(),
    /** Custom order mode: pick one, then pick another to swap them. */
    val reordering: Boolean = false,
    /** The bin id or title key picked to move. */
    val picked: String? = null,
    val posterWidthDp: Int = 126,
    /** Layout's custom poster URL for the Library (blank when off), for the bins' own covers. */
    val posterPattern: String = "",
    val posterCornerDp: Int = 12,
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedSeriesIds: Set<String> = emptySet()
)

private data class Local(
    val openBinId: String? = null,
    val reordering: Boolean = false,
    val picked: String? = null
)

private data class WatchedHistorySnapshot(
    val watchedMovieIds: Set<String>,
    val watchedSeriesIds: Set<String>,
    val allProgress: List<WatchProgress>,
    val watchedItems: List<WatchedItem>
)

@HiltViewModel
class BinsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BinsRepository,
    private val layoutPreferences: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val metaRepository: MetaRepository,
    private val libraryRepository: com.nuvio.tv.domain.repository.LibraryRepository
) : ViewModel() {

    private val local = MutableStateFlow(Local())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var messageJob: Job? = null

    private val posterLayout = combine(
        layoutPreferences.posterCardWidthDp,
        layoutPreferences.posterCardCornerRadiusDp,
        layoutPreferences.customPosterUrlPattern,
        layoutPreferences.customPosterEnabledScreens
    ) { width, corner, pattern, screens ->
        Triple(width, corner, patternForScreen(pattern, CustomPosterScreen.LIBRARY, screens))
    }

    private val watchedSnapshot = combine(
        watchProgressRepository.observeWatchedMovieIds(),
        watchedSeriesStateHolder.fullyWatchedSeriesIds,
        watchProgressRepository.allProgress,
        watchProgressRepository.watchedItems
    ) { movies, series, progressList, watchedItemsList ->
        WatchedHistorySnapshot(
            watchedMovieIds = movies,
            watchedSeriesIds = series,
            allProgress = progressList,
            watchedItems = watchedItemsList
        )
    }

    val uiState: StateFlow<BinsUiState> = combine(
        repository.state,
        local,
        posterLayout,
        watchedSnapshot,
        libraryRepository.libraryItems
    ) { state, local, layout, watched, syncedItems ->
        val (width, corner, pattern) = layout
        val bins = BinsReducer.sortedBins(state.bins, state.sort)
        val open = local.openBinId?.let { id -> state.bins.firstOrNull { it.id == id } }
        val titles = open?.let { bin ->
            val sorted = BinsReducer.sortedEntries(bin.entries, state.sort)
            val previews = sorted.map { it.toPreview() }.withCustomPosterUrls(pattern)
            sorted.zip(previews) { entry, preview -> BinTitle(entry, preview) }
        }.orEmpty()
        val syncedAsBinEntries = syncedItems.map { item ->
            BinEntry(
                id = item.id,
                type = item.type,
                name = item.name,
                poster = item.poster,
                background = item.background,
                logo = item.logo,
                description = item.description,
                releaseInfo = item.releaseInfo,
                imdbRating = item.imdbRating,
                genres = item.genres,
                addonBaseUrl = item.addonBaseUrl,
                addedAt = item.listedAt,
                viewedAt = item.listedAt
            )
        }
        val uniqueEntries = (bins.flatMap { it.entries } + syncedAsBinEntries)
            .distinctBy { it.key }
        val sortedAllEntries = BinsReducer.sortedEntries(uniqueEntries, state.sort)
        val allPreviews = sortedAllEntries.map { it.toPreview() }.withCustomPosterUrls(pattern)
        val allTitles = sortedAllEntries.zip(allPreviews) { entry, preview -> BinTitle(entry, preview) }

        val libraryByBaseId = uniqueEntries.associateBy { it.id.substringBefore(':').trim() }
        val historyMap = LinkedHashMap<String, Pair<Long, BinEntry>>()

        fun normalizeType(rawType: String): String {
            return if (rawType.equals("series", ignoreCase = true) || rawType.equals("tv", ignoreCase = true)) {
                "series"
            } else {
                "movie"
            }
        }

        // 1. Add from WatchProgress (most recently watched/played first)
        watched.allProgress
            .sortedByDescending { it.lastWatched }
            .forEach { wp ->
                val baseId = wp.contentId.ifBlank { wp.videoId }.substringBefore(':').trim()
                if (baseId.isBlank()) return@forEach
                val normType = normalizeType(wp.contentType)
                val mapKey = "$normType:$baseId"
                if (historyMap.containsKey(mapKey)) return@forEach

                val libMatch = libraryByBaseId[baseId]
                val cachedMeta = metaRepository.getCachedMeta(normType, baseId)
                    ?: metaRepository.getCachedMeta(wp.contentType, baseId)
                val resolvedName = wp.name.takeIf { it.isNotBlank() && it != baseId }
                    ?: libMatch?.name?.takeIf { it.isNotBlank() }
                    ?: cachedMeta?.name?.takeIf { it.isNotBlank() }
                    ?: wp.name.ifBlank { baseId }
                val resolvedPoster = wp.poster?.takeIf { it.isNotBlank() && !it.contains("/background/") }
                    ?: libMatch?.poster?.takeIf { it.isNotBlank() }
                    ?: cachedMeta?.poster?.takeIf { it.isNotBlank() }
                    ?: baseId.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/$it/img" }
                val resolvedBackdrop = wp.backdrop?.takeIf { it.isNotBlank() }
                    ?: libMatch?.background
                    ?: cachedMeta?.backdropUrl

                val entry = BinEntry(
                    id = baseId,
                    type = libMatch?.type ?: normType,
                    name = resolvedName,
                    poster = resolvedPoster,
                    background = resolvedBackdrop,
                    logo = wp.logo ?: libMatch?.logo ?: cachedMeta?.logo,
                    description = libMatch?.description ?: cachedMeta?.description,
                    releaseInfo = libMatch?.releaseInfo ?: cachedMeta?.releaseInfo,
                    imdbRating = libMatch?.imdbRating ?: cachedMeta?.imdbRating,
                    genres = libMatch?.genres?.takeIf { it.isNotEmpty() } ?: cachedMeta?.genres.orEmpty(),
                    addonBaseUrl = libMatch?.addonBaseUrl,
                    addedAt = wp.lastWatched,
                    viewedAt = wp.lastWatched
                )
                historyMap[mapKey] = wp.lastWatched to entry
            }

        // 2. Add from WatchedItems (completed movies & episodes)
        watched.watchedItems
            .sortedByDescending { it.watchedAt }
            .forEach { wi ->
                val baseId = wi.contentId.substringBefore(':').trim()
                if (baseId.isBlank()) return@forEach
                val normType = normalizeType(wi.contentType)
                val mapKey = "$normType:$baseId"
                val existing = historyMap[mapKey]
                if (existing != null && existing.first >= wi.watchedAt) return@forEach

                val libMatch = libraryByBaseId[baseId]
                val cachedMeta = metaRepository.getCachedMeta(normType, baseId)
                    ?: metaRepository.getCachedMeta(wi.contentType, baseId)
                val resolvedName = wi.title.takeIf { it.isNotBlank() && it != baseId }
                    ?: libMatch?.name?.takeIf { it.isNotBlank() }
                    ?: cachedMeta?.name?.takeIf { it.isNotBlank() }
                    ?: wi.title.ifBlank { baseId }
                val resolvedPoster = wi.poster?.takeIf { it.isNotBlank() && !it.contains("/background/") }
                    ?: libMatch?.poster?.takeIf { it.isNotBlank() }
                    ?: cachedMeta?.poster?.takeIf { it.isNotBlank() }
                    ?: baseId.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/$it/img" }

                val entry = BinEntry(
                    id = baseId,
                    type = libMatch?.type ?: normType,
                    name = resolvedName,
                    poster = resolvedPoster,
                    background = libMatch?.background ?: cachedMeta?.backdropUrl,
                    logo = libMatch?.logo ?: cachedMeta?.logo,
                    description = libMatch?.description ?: cachedMeta?.description,
                    releaseInfo = wi.releaseInfo ?: libMatch?.releaseInfo ?: cachedMeta?.releaseInfo,
                    imdbRating = libMatch?.imdbRating ?: cachedMeta?.imdbRating,
                    genres = libMatch?.genres?.takeIf { it.isNotEmpty() } ?: cachedMeta?.genres.orEmpty(),
                    addonBaseUrl = libMatch?.addonBaseUrl,
                    addedAt = wi.watchedAt,
                    viewedAt = wi.watchedAt
                )
                historyMap[mapKey] = wi.watchedAt to entry
            }

        // 3. Also include any Library entries marked watched in watchedMovieIds / watchedSeriesIds
        uniqueEntries.forEach { entry ->
            val baseId = entry.id.substringBefore(':').trim()
            val isSeries = entry.type.equals("series", true) || entry.type.equals("tv", true)
            val isMarkedWatched = if (isSeries) {
                entry.id in watched.watchedSeriesIds || baseId in watched.watchedSeriesIds
            } else {
                entry.id in watched.watchedMovieIds || baseId in watched.watchedMovieIds
            }
            if (isMarkedWatched) {
                val normType = if (isSeries) "series" else "movie"
                val mapKey = "$normType:$baseId"
                if (!historyMap.containsKey(mapKey)) {
                    val ts = maxOf(entry.viewedAt, entry.addedAt)
                    historyMap[mapKey] = ts to entry
                }
            }
        }

        val sortedHistoryEntries = historyMap.values
            .sortedByDescending { it.first }
            .map { it.second }
        val historyPreviews = sortedHistoryEntries.map { it.toPreview() }.withCustomPosterUrls(pattern)
        val watchedHistoryTitles = sortedHistoryEntries.zip(historyPreviews) { entry, preview ->
            BinTitle(entry, preview)
        }

        BinsUiState(
            loaded = true,
            bins = bins,
            sort = state.sort,
            openBin = open,
            openTitles = titles,
            allTitles = allTitles,
            watchedHistoryTitles = watchedHistoryTitles,
            reordering = local.reordering,
            picked = local.picked,
            posterWidthDp = width,
            posterCornerDp = corner,
            posterPattern = pattern,
            watchedMovieIds = watched.watchedMovieIds,
            watchedSeriesIds = watched.watchedSeriesIds
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BinsUiState())

    fun pickRandomMovie(onPicked: (BinTitle) -> Unit) {
        val state = uiState.value
        val movies = state.allTitles.filter {
            !it.entry.type.equals("series", ignoreCase = true) &&
                !it.entry.type.equals("tv", ignoreCase = true)
        }
        if (movies.isEmpty()) {
            showMessage("No movies found in your Library")
            return
        }
        val unwatched = movies.filter { it.entry.id !in state.watchedMovieIds }
        val pool = unwatched.ifEmpty { movies }
        val chosen = pool.random()
        onPicked(chosen)
    }

    fun openBin(bin: Bin) {
        local.update { Local(openBinId = bin.id) }
        viewModelScope.launch { repository.markBinViewed(bin.id) }
    }

    fun closeBin() {
        local.update { Local() }
    }

    fun selectSort(sort: BinSort) {
        local.update { it.copy(reordering = false, picked = null) }
        viewModelScope.launch { repository.setSort(sort) }
    }

    /** Custom order on (starting from what's shown) or off. */
    fun toggleReorder() {
        val state = uiState.value
        if (state.reordering) {
            local.update { it.copy(reordering = false, picked = null) }
            showMessage(context.getString(R.string.bins_custom_order_saved))
            return
        }
        local.update { it.copy(reordering = true, picked = null) }
        viewModelScope.launch {
            val open = state.openBin
            if (open != null) {
                repository.useDisplayedEntryOrder(open.id, state.openTitles.map { it.entry.key })
            } else {
                repository.useDisplayedBinOrder(state.bins.map { it.id })
            }
        }
        showMessage(context.getString(if (state.openBin != null) R.string.bins_reorder_hint_titles else R.string.bins_reorder_hint_bins))
    }

    /** In custom order: the first pick is held; picking another swaps the two; picking it again lets go. */
    fun pick(target: String) {
        val current = local.value
        if (!current.reordering) return
        val held = current.picked
        when {
            held == null -> local.update { it.copy(picked = target) }
            held == target -> local.update { it.copy(picked = null) }
            else -> {
                local.update { it.copy(picked = null) }
                val openId = current.openBinId
                viewModelScope.launch {
                    if (openId != null) repository.swapEntries(openId, held, target) else repository.swapBins(held, target)
                }
            }
        }
    }

    /** Back while custom order is on: first drop the held pick, then leave custom order. Returns whether it was used. */
    fun backInReorder(): Boolean {
        val current = local.value
        if (!current.reordering) return false
        if (current.picked != null) local.update { it.copy(picked = null) } else toggleReorder()
        return true
    }

    fun toggleStar(bin: Bin) {
        viewModelScope.launch { repository.toggleStar(bin.id) }
        showMessage(
            if (bin.starred) context.getString(R.string.bins_unstarred)
            else context.getString(R.string.bins_starred, bin.name)
        )
    }

    /** Null once the bin exists; otherwise why not, for the dialog to show. */
    suspend fun createBin(name: String): String? = when (val result = repository.createBin(name)) {
        is BinNameResult.Ok -> {
            showMessage(context.getString(R.string.bins_created, com.nuvio.tv.core.bins.cleanBinName(name)))
            null
        }
        BinNameResult.Blank -> context.getString(R.string.bins_name_blank)
        BinNameResult.Duplicate -> context.getString(R.string.bins_name_taken, com.nuvio.tv.core.bins.cleanBinName(name))
    }

    suspend fun renameBin(bin: Bin, name: String): String? = when (repository.renameBin(bin.id, name)) {
        is BinNameResult.Ok -> null
        BinNameResult.Blank -> context.getString(R.string.bins_name_blank)
        BinNameResult.Duplicate -> context.getString(R.string.bins_name_taken, com.nuvio.tv.core.bins.cleanBinName(name))
    }

    fun deleteBin(bin: Bin) {
        if (local.value.openBinId == bin.id) closeBin()
        viewModelScope.launch { repository.deleteBin(bin.id) }
        showMessage(context.getString(R.string.bins_deleted, bin.name))
    }

    fun removeTitle(bin: Bin, title: BinTitle) {
        viewModelScope.launch { repository.remove(bin.id, title.entry.id, title.entry.type) }
        showMessage(context.getString(R.string.bins_removed_from, bin.name))
    }

    fun onTitleOpened(bin: Bin, title: BinTitle) {
        viewModelScope.launch { repository.markEntryViewed(bin.id, title.entry.key) }
        prefetch(title.entry)
    }

    fun cachedBackdrop(title: BinTitle): String? = metaRepository.getCachedMeta(title.entry.type, title.entry.id)?.backdropUrl

    private var prefetchJob: Job? = null

    /** Warms the details page for the focused title, so opening it is instant. */
    fun prefetch(entry: BinEntry) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            delay(150)
            runCatching {
                metaRepository.getMetaFromAllAddons(type = entry.type, id = entry.id)
                    .firstOrNull { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            }
        }
    }

    private fun showMessage(text: String) {
        _message.value = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(2_400)
            _message.value = null
        }
    }
}

internal fun BinEntry.toPreview(): MetaPreview {
    val cleanPoster = poster?.takeIf { it.isNotBlank() && !it.contains("/background/") }
        ?: id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/$it/img" }
        ?: poster
    return MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = cleanPoster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres
    )
}
