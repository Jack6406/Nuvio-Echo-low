@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.newlook.home

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.ui.newlook.glassLens
import com.nuvio.tv.ui.newlook.glassSurface
import com.nuvio.tv.ui.newlook.menuFrost
import coil3.imageLoader
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HomeControlCenterEntryPoint {
    fun watchProgressRepository(): WatchProgressRepository
    fun libraryRepository(): LibraryRepository
    fun startupSyncService(): StartupSyncService
    fun profileSettingsSyncService(): ProfileSettingsSyncService
    fun betaPerformanceSettingsDataStore(): BetaPerformanceSettingsDataStore
}

/**
 * Floating Clock Dynamic Island pill in the top-right corner of Home.
 * Displays the live clock, active sync/resume status, and opens the Quick Control Center
 * when clicked or when pressing Up from the Home Hero / holding CH+ or Menu.
 */
@Composable
internal fun HomeTopRightClockIsland(
    statusNotice: String?,
    continueWatchingCount: Int,
    focusRequester: FocusRequester,
    onFocusedChanged: (Boolean) -> Unit,
    onOpenControlCenter: () -> Unit,
    onOpenStats: () -> Unit,
    onLeftToTopBar: () -> Unit,
    onDownToHero: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var clockText by remember { mutableStateOf(formatCurrentClock(context)) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        while (true) {
            clockText = formatCurrentClock(context)
            delay(15_000L)
        }
    }

    val shape = RoundedCornerShape(19.dp)
    val subtitle = when {
        !statusNotice.isNullOrBlank() -> statusNotice
        continueWatchingCount > 0 -> "$continueWatchingCount In Progress"
        else -> "Control Center"
    }

    Surface(
        onClick = onOpenControlCenter,
        modifier = modifier
            .height(36.dp)
            .menuFrost(shape)
            .glassSurface(shape)
            .then(if (isFocused) Modifier.glassLens(shape) else Modifier)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                onFocusedChanged(state.isFocused)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU || event.key == Key.Menu || event.key == Key.Info) {
                    onOpenStats()
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionLeft -> {
                        onLeftToTopBar()
                        true
                    }
                    Key.DirectionDown -> {
                        onDownToHero()
                        true
                    }
                    Key.DirectionUp, Key.DirectionRight -> true
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(1.25.dp, Color.White.copy(alpha = 0.88f)),
                shape = shape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(if (!statusNotice.isNullOrBlank()) Color(0xFF4ADE80) else Color(0xFF60A5FA))
            )
            Text(
                text = subtitle,
                color = if (isFocused) Color.White else Color(0xFFE6E6E6),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(12.dp)
                    .background(Color.White.copy(alpha = 0.24f))
            )
            Text(
                text = clockText,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Control Center",
                tint = if (isFocused) Color.White else Color(0xFFE6E6E6),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun formatCurrentClock(context: Context): String {
    val is24Hour = runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(false)
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}

/**
 * Quick Control Center modal with 1-click shortcuts for:
 * 1) Clear Cache
 * 2) Sync Account, Addons & Backdrops
 * 3) Speed Test
 * 4) Performance Mode
 * 5) Profile & Viewing Stats
 */
@Composable
internal fun QuickControlCenterDialog(
    settings: BetaPerformanceSettings,
    onDismiss: () -> Unit,
    onOpenViewingStats: () -> Unit,
    onStatusBannerChanged: (String?) -> Unit,
    onRefreshHome: suspend () -> Unit
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, HomeControlCenterEntryPoint::class.java)
    }
    val scope = rememberCoroutineScope()
    val firstCardFocus = remember { FocusRequester() }

    var cacheSubtitle by remember { mutableStateOf("Free image, stream badge & HTTP disk cache") }
    var isClearingCache by remember { mutableStateOf(false) }

    var syncSubtitle by remember { mutableStateOf("Pull & push addons, watch progress & custom backdrops") }
    var isSyncing by remember { mutableStateOf(false) }

    var speedTestSubtitle by remember { mutableStateOf("Measure download speed & 4K remux headroom") }
    var isTestingSpeed by remember { mutableStateOf(false) }

    val currentPerfLabel = remember(settings) {
        when {
            !settings.homeAmbientBackground && !settings.posterColourMesh && settings.enrichment == BetaPerformanceSettings.ENRICHMENT_OFF ->
                "1 GB RAM Turbo (Ultra-Low Memory)"
            settings.enrichment >= BetaPerformanceSettings.ENRICHMENT_MEDIUM && settings.menuGlass >= 2 ->
                "Max Glass & Enrichment"
            else ->
                "Balanced (Default)"
        }
    }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { firstCardFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .padding(top = 56.dp, end = 36.dp)
                .width(460.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF232733).copy(alpha = 0.96f),
                            Color(0xFF12151C).copy(alpha = 0.98f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(24.dp))
                .clickable(enabled = false, onClick = {})
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Quick Control Center",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Press Up on Home Hero or CH+ / Menu anytime",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.5.sp
                    )
                }
                Text(
                    text = formatCurrentClock(context),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            ControlCenterActionRow(
                icon = Icons.Default.BarChart,
                title = "Profile & Viewing Stats",
                subtitle = "Monthly movies/episodes, total hours, 7-day rhythm & top genres",
                badgeText = "Open",
                focusRequester = firstCardFocus,
                onClick = {
                    onDismiss()
                    onOpenViewingStats()
                }
            )

            ControlCenterActionRow(
                icon = Icons.Default.Sync,
                title = "Sync Addons, Progress & Backdrops",
                subtitle = syncSubtitle,
                badgeText = if (isSyncing) "Syncing…" else "Sync Now",
                onClick = {
                    if (isSyncing) return@ControlCenterActionRow
                    isSyncing = true
                    syncSubtitle = "Syncing addons, watch progress & custom backdrops…"
                    onStatusBannerChanged("Syncing cloud…")
                    scope.launch {
                        val startupSync = entryPoint.startupSyncService()
                        val profileSync = entryPoint.profileSettingsSyncService()
                        runCatching {
                            startupSync.requestAddonSyncNow()
                            startupSync.requestSyncNow(includeProfileSettings = true)
                            withContext(Dispatchers.IO) {
                                profileSync.pushCurrentProfileToRemote()
                                profileSync.pullCurrentProfileFromRemote()
                            }
                            onRefreshHome()
                        }
                        isSyncing = false
                        syncSubtitle = "Synced addons, watch history & custom backdrops across devices"
                        onStatusBannerChanged("Cloud synced")
                        delay(4_000L)
                        onStatusBannerChanged(null)
                    }
                }
            )

            ControlCenterActionRow(
                icon = Icons.Default.CleaningServices,
                title = "Clear Cache",
                subtitle = cacheSubtitle,
                badgeText = if (isClearingCache) "Cleaning…" else "Clean",
                onClick = {
                    if (isClearingCache) return@ControlCenterActionRow
                    isClearingCache = true
                    cacheSubtitle = "Scanning and clearing regenerable cache files…"
                    scope.launch {
                        val freedBytes = withContext(Dispatchers.IO) {
                            clearRegenerableCaches(context)
                        }
                        runCatching { context.imageLoader.memoryCache?.clear() }
                        val freedMb = String.format(Locale.US, "%.1f MB", freedBytes / (1024.0 * 1024.0))
                        isClearingCache = false
                        cacheSubtitle = "Freed $freedMb of regenerable cache & memory"
                        onStatusBannerChanged("Freed $freedMb")
                        delay(4_000L)
                        onStatusBannerChanged(null)
                    }
                }
            )

            ControlCenterActionRow(
                icon = Icons.Default.Speed,
                title = "Speed Test",
                subtitle = speedTestSubtitle,
                badgeText = if (isTestingSpeed) "Testing…" else "Run Test",
                onClick = {
                    if (isTestingSpeed) return@ControlCenterActionRow
                    isTestingSpeed = true
                    speedTestSubtitle = "Testing connection throughput…"
                    onStatusBannerChanged("Testing speed…")
                    scope.launch {
                        val mbps = withContext(Dispatchers.IO) { measureDownloadMbps() }
                        isTestingSpeed = false
                        if (mbps > 0.0) {
                            val maxGbPerHr = (mbps * 0.36).coerceAtLeast(1.0)
                            val tier = when {
                                mbps >= 80 -> "4K Remux Ready"
                                mbps >= 35 -> "4K Web-DL Ready"
                                mbps >= 15 -> "1080p High Bitrate"
                                else -> "720p / 1080p Compressed"
                            }
                            val formatted = String.format(Locale.US, "%.1f Mbps", mbps)
                            val gbFormatted = String.format(Locale.US, "%.0f GB/hr", maxGbPerHr)
                            speedTestSubtitle = "$formatted • $tier (up to ~$gbFormatted)"
                            onStatusBannerChanged(formatted)
                        } else {
                            speedTestSubtitle = "Could not reach speed test endpoint — check connection"
                            onStatusBannerChanged(null)
                        }
                    }
                }
            )

            ControlCenterActionRow(
                icon = Icons.Default.Memory,
                title = "Performance Mode",
                subtitle = currentPerfLabel,
                badgeText = "Cycle",
                onClick = {
                    scope.launch {
                        val store = entryPoint.betaPerformanceSettingsDataStore()
                        val isLowRam = !settings.homeAmbientBackground && !settings.posterColourMesh &&
                            settings.enrichment == BetaPerformanceSettings.ENRICHMENT_OFF
                        val isMaxGlass = settings.enrichment >= BetaPerformanceSettings.ENRICHMENT_MEDIUM &&
                            settings.menuGlass >= 2
                        when {
                            isLowRam -> {
                                // Switch to Balanced
                                store.setHomeAmbientBackground(true)
                                store.setDetailsAmbientBackground(true)
                                store.setPosterColourMesh(true)
                                store.setEnrichment(BetaPerformanceSettings.ENRICHMENT_LOW)
                                store.setSeekThumbnails(true)
                                store.setMenuGlass(1)
                                store.setFastPlaybackStart(true)
                                onStatusBannerChanged("Balanced Mode")
                            }
                            !isMaxGlass -> {
                                // Switch to Max Glass & Enrichment
                                store.setHomeAmbientBackground(true)
                                store.setDetailsAmbientBackground(true)
                                store.setPosterColourMesh(true)
                                store.setEnrichment(BetaPerformanceSettings.ENRICHMENT_MEDIUM)
                                store.setSeekThumbnails(true)
                                store.setMenuGlass(2)
                                store.setFastPlaybackStart(true)
                                onStatusBannerChanged("Max Glass Mode")
                            }
                            else -> {
                                // Switch to 1 GB Low-RAM Turbo
                                store.setHomeAmbientBackground(false)
                                store.setDetailsAmbientBackground(false)
                                store.setPosterColourMesh(false)
                                store.setEnrichment(BetaPerformanceSettings.ENRICHMENT_OFF)
                                store.setSeekThumbnails(false)
                                store.setMenuGlass(0)
                                store.setFastPlaybackStart(true)
                                onStatusBannerChanged("1 GB RAM Turbo")
                            }
                        }
                        delay(3_500L)
                        onStatusBannerChanged(null)
                    }
                }
            )
        }
    }
}

@Composable
private fun ControlCenterActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        targetValue = if (focused) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f),
        animationSpec = tween(140),
        label = "ccRowBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(140),
        label = "ccRowBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = if (focused) 0.24f else 0.11f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
                .padding(horizontal = 11.dp, vertical = 5.dp)
        ) {
            Text(
                text = badgeText,
                color = if (focused) Color(0xFF101218) else Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun clearRegenerableCaches(context: Context): Long {
    var freed = 0L
    fun deleteChildren(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var sum = 0L
        dir.listFiles()?.forEach { child ->
            sum += if (child.isDirectory) {
                val sub = deleteChildren(child)
                runCatching { child.delete() }
                sub
            } else {
                val len = child.length()
                if (runCatching { child.delete() }.getOrDefault(false)) len else 0L
            }
        }
        return sum
    }
    freed += deleteChildren(context.cacheDir)
    freed += deleteChildren(context.externalCacheDir)
    return freed
}

private fun measureDownloadMbps(): Double {
    val testUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=6000000",
        "https://proof.ovh.net/files/10Mb.dat"
    )
    for (urlStr in testUrls) {
        val conn = runCatching {
            (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 6_000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
        }.getOrNull() ?: continue
        try {
            val startNs = System.nanoTime()
            val buffer = ByteArray(32 * 1024)
            var totalBytes = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                    val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                    if (elapsedSec >= 3.2 || totalBytes >= 6_000_000L) break
                }
            }
            val durationSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            if (totalBytes > 64 * 1024) {
                return (totalBytes * 8.0) / (durationSec * 1_000_000.0)
            }
        } catch (_: Throwable) {
            // Try fallback endpoint
        } finally {
            runCatching { conn.disconnect() }
        }
    }
    return 0.0
}

/**
 * Item 10: Dedicated Profile & Viewing Stats screen/dialog showing:
 * - Monthly movies & episodes watched
 * - All-time movies watched, episodes watched, and total watch hours
 * - Viewing rhythm by day of the week (Mon - Sun)
 * - Top genres breakdown
 * - Recently watched timeline with watched dates
 */
@Composable
internal fun ProfileViewingStatsDialog(
    catalogItemsForGenres: List<MetaPreview>,
    onDismiss: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, HomeControlCenterEntryPoint::class.java)
    }
    val watchProgressRepo = remember(entryPoint) { entryPoint.watchProgressRepository() }
    val libraryRepo = remember(entryPoint) { entryPoint.libraryRepository() }

    val allProgress by watchProgressRepo.allProgress.collectAsState(initial = emptyList())
    val watchedItems by watchProgressRepo.watchedItems.collectAsState(initial = emptyList())
    val watchedMovieIds by watchProgressRepo.observeWatchedMovieIds().collectAsState(initial = emptySet())
    val libraryItems by libraryRepo.libraryItems.collectAsState(initial = emptyList())

    val closeFocusRequester = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { closeFocusRequester.requestFocus() }
    }

    val stats = remember(allProgress, watchedItems, watchedMovieIds, libraryItems, catalogItemsForGenres) {
        computeViewingStats(
            allProgress = allProgress,
            watchedItems = watchedItems,
            watchedMovieIds = watchedMovieIds,
            libraryItemsCount = libraryItems.size,
            catalogItems = catalogItemsForGenres
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 42.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1E222E).copy(alpha = 0.97f),
                            Color(0xFF0E1016).copy(alpha = 0.99f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(26.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Profile & Viewing Stats",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${stats.currentMonthName} Activity • ${stats.moviesThisMonth} Movies & ${stats.episodesThisMonth} Episodes watched this month",
                        color = Color(0xFF93C5FD),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                var closeFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (closeFocused) Color.White else Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                        .focusRequester(closeFocusRequester)
                        .onFocusChanged { closeFocused = it.isFocused }
                        .focusable()
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = if (closeFocused) Color.Black else Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Close",
                        color = if (closeFocused) Color.Black else Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Top 4 KPI Summary Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatKpiCard(
                    label = "Movies Watched",
                    value = stats.totalMoviesWatched.toString(),
                    subValue = "+${stats.moviesThisMonth} in ${stats.currentMonthShort}",
                    accentColor = Color(0xFF60A5FA),
                    modifier = Modifier.weight(1f)
                )
                StatKpiCard(
                    label = "Episodes Watched",
                    value = stats.totalEpisodesWatched.toString(),
                    subValue = "+${stats.episodesThisMonth} in ${stats.currentMonthShort}",
                    accentColor = Color(0xFF34D399),
                    modifier = Modifier.weight(1f)
                )
                StatKpiCard(
                    label = "Total Watch Time",
                    value = String.format(Locale.US, "%.1f hrs", stats.totalHoursWatched),
                    subValue = "${stats.totalMinutesWatched} mins tracked",
                    accentColor = Color(0xFFFBBF24),
                    modifier = Modifier.weight(1f)
                )
                StatKpiCard(
                    label = "Viewing Streak & Peak",
                    value = stats.peakDayName,
                    subValue = "${stats.activeDaysLast30} active days (last 30d)",
                    accentColor = Color(0xFFC084FC),
                    modifier = Modifier.weight(1f)
                )
            }

            // Middle Section: 7-Day Viewing Rhythm + Top Genres + Recent Activity
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Column 1: Viewing Rhythm by Day of Week (Mon - Sun)
                Column(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Viewing Rhythm by Day",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Most active on ${stats.peakDayName}",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.5.sp
                        )
                    }

                    val maxDayCount = (stats.dayOfWeekCounts.maxOrNull() ?: 1).coerceAtLeast(1)
                    val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        dayLabels.forEachIndexed { idx, day ->
                            val count = stats.dayOfWeekCounts.getOrElse(idx) { 0 }
                            val ratio = (count.toFloat() / maxDayCount.toFloat()).coerceIn(0.08f, 1f)
                            val isPeak = count > 0 && count == maxDayCount
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Text(
                                    text = count.toString(),
                                    color = if (isPeak) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.72f),
                                    fontSize = 11.sp,
                                    fontWeight = if (isPeak) FontWeight.Bold else FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.68f)
                                        .fillMaxHeight(ratio * 0.72f)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                        .background(
                                            if (isPeak) {
                                                Brush.verticalGradient(listOf(Color(0xFF60A5FA), Color(0xFF2563EB)))
                                            } else {
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.38f),
                                                        Color.White.copy(alpha = 0.16f)
                                                    )
                                                )
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = day,
                                    color = if (isPeak) Color.White else Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp,
                                    fontWeight = if (isPeak) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Column 2: Top Genres Breakdown
                Column(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text(
                        text = "Top Genres",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (stats.topGenres.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Watch movies or shows to build your genre profile",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        val maxGenre = stats.topGenres.maxOf { it.second }.coerceAtLeast(1)
                        stats.topGenres.take(5).forEachIndexed { idx, (genre, count) ->
                            val fraction = (count.toFloat() / maxGenre.toFloat()).coerceIn(0.12f, 1f)
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${idx + 1}. $genre",
                                        color = Color.White,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$count titles",
                                        color = Color.White.copy(alpha = 0.68f),
                                        fontSize = 11.5.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color.White.copy(alpha = 0.10f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(Color(0xFF34D399), Color(0xFF60A5FA))
                                                )
                                            )
                                    )
                                }
                            }
                        }
                    }
                }

                // Column 3: Recently Watched Timeline
                Column(
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Recently Watched Timeline",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (stats.recentHistory.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No recent watch history yet",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 4.dp)
                        ) {
                            items(stats.recentHistory, key = { "${it.contentId}_${it.badge}_${it.timestamp}" }) { entry ->
                                var itemFocused by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (itemFocused) Color.White.copy(alpha = 0.18f)
                                            else Color.White.copy(alpha = 0.06f)
                                        )
                                        .border(
                                            1.dp,
                                            if (itemFocused) Color.White.copy(alpha = 0.70f)
                                            else Color.White.copy(alpha = 0.08f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .onFocusChanged { itemFocused = it.isFocused }
                                        .focusable()
                                        .clickable {
                                            onDismiss()
                                            onNavigateToDetail(entry.contentId, entry.contentType, "")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.title,
                                            color = Color.White,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${entry.badge} • ${entry.formattedDate}",
                                            color = Color.White.copy(alpha = 0.65f),
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        text = "✓",
                                        color = Color(0xFF4ADE80),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatKpiCard(
    label: String,
    value: String,
    subValue: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.70f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = accentColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subValue,
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class RecentWatchedEntry(
    val contentId: String,
    val contentType: String,
    val title: String,
    val badge: String,
    val timestamp: Long,
    val formattedDate: String
)

private data class ComputedViewingStats(
    val totalMoviesWatched: Int,
    val totalEpisodesWatched: Int,
    val moviesThisMonth: Int,
    val episodesThisMonth: Int,
    val totalHoursWatched: Double,
    val totalMinutesWatched: Long,
    val currentMonthName: String,
    val currentMonthShort: String,
    val dayOfWeekCounts: List<Int>,
    val peakDayName: String,
    val activeDaysLast30: Int,
    val topGenres: List<Pair<String, Int>>,
    val recentHistory: List<RecentWatchedEntry>
)

private fun computeViewingStats(
    allProgress: List<WatchProgress>,
    watchedItems: List<WatchedItem>,
    watchedMovieIds: Set<String>,
    libraryItemsCount: Int,
    catalogItems: List<MetaPreview>
): ComputedViewingStats {
    val nowCal = Calendar.getInstance()
    val currentYear = nowCal.get(Calendar.YEAR)
    val currentMonth = nowCal.get(Calendar.MONTH)
    val currentMonthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(nowCal.time)
    val currentMonthShort = SimpleDateFormat("MMM", Locale.getDefault()).format(nowCal.time)
    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    val movieKeys = HashSet<String>()
    val episodeKeys = HashSet<String>()
    var moviesThisMonth = 0
    var episodesThisMonth = 0
    val monthMovieKeys = HashSet<String>()
    val monthEpisodeKeys = HashSet<String>()

    // Mon(0) .. Sun(6)
    val dayCounts = IntArray(7)
    val activeDayKeysLast30 = HashSet<String>()
    val thirtyDaysAgoMs = System.currentTimeMillis() - 30L * 24L * 3600L * 1000L

    var totalWatchedMs = 0L
    val recentMap = LinkedHashMap<String, RecentWatchedEntry>()

    fun recordTimestamp(ts: Long, isEpisode: Boolean, key: String) {
        if (ts <= 0L) return
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val dow = cal.get(Calendar.DAY_OF_WEEK) // Sun=1 .. Sat=7
        val monIndex = if (dow == Calendar.SUNDAY) 6 else dow - 2
        if (monIndex in 0..6) dayCounts[monIndex]++

        if (ts >= thirtyDaysAgoMs) {
            activeDayKeysLast30.add("${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}")
        }
        if (cal.get(Calendar.YEAR) == currentYear && cal.get(Calendar.MONTH) == currentMonth) {
            if (isEpisode) {
                if (monthEpisodeKeys.add(key)) episodesThisMonth++
            } else {
                if (monthMovieKeys.add(key)) moviesThisMonth++
            }
        }
    }

    watchedMovieIds.filter { it.startsWith("tt") }.forEach { movieKeys.add(it) }

    allProgress.forEach { wp ->
        val isEp = wp.season != null && wp.episode != null
        val watchedDuration = when {
            wp.position > 0L -> wp.position
            wp.duration > 0L && wp.isCompleted() -> wp.duration
            else -> if (isEp) 42L * 60_000L else 110L * 60_000L
        }
        totalWatchedMs += watchedDuration

        if (wp.isCompleted() || wp.progressPercentage >= 0.75f) {
            if (isEp) {
                val k = "${wp.contentId}_s${wp.season}_e${wp.episode}"
                episodeKeys.add(k)
                recordTimestamp(wp.lastWatched, isEpisode = true, key = k)
            } else {
                val k = wp.contentId
                movieKeys.add(k)
                recordTimestamp(wp.lastWatched, isEpisode = false, key = k)
            }
        } else if (wp.lastWatched > 0L) {
            val k = if (isEp) "${wp.contentId}_s${wp.season}_e${wp.episode}" else wp.contentId
            recordTimestamp(wp.lastWatched, isEpisode = isEp, key = k)
        }

        if (wp.lastWatched > 0L && wp.name.isNotBlank()) {
            val badge = if (isEp) "S${wp.season}:E${wp.episode}" else "Movie"
            val entryKey = "${wp.contentId}_$badge"
            recentMap[entryKey] = RecentWatchedEntry(
                contentId = wp.contentId,
                contentType = wp.contentType.ifBlank { if (isEp) "series" else "movie" },
                title = wp.name,
                badge = badge,
                timestamp = wp.lastWatched,
                formattedDate = dateFmt.format(Date(wp.lastWatched))
            )
        }
    }

    watchedItems.forEach { item ->
        val isEp = item.season != null && item.episode != null
        if (isEp) {
            val k = "${item.contentId}_s${item.season}_e${item.episode}"
            if (episodeKeys.add(k)) {
                totalWatchedMs += 42L * 60_000L
            }
            recordTimestamp(item.watchedAt, isEpisode = true, key = k)
        } else {
            val k = item.contentId
            if (movieKeys.add(k)) {
                totalWatchedMs += 110L * 60_000L
            }
            recordTimestamp(item.watchedAt, isEpisode = false, key = k)
        }
        if (item.watchedAt > 0L && item.title.isNotBlank()) {
            val badge = if (isEp) "S${item.season}:E${item.episode}" else "Movie"
            val entryKey = "${item.contentId}_$badge"
            if (!recentMap.containsKey(entryKey)) {
                recentMap[entryKey] = RecentWatchedEntry(
                    contentId = item.contentId,
                    contentType = item.contentType.ifBlank { if (isEp) "series" else "movie" },
                    title = item.title,
                    badge = badge,
                    timestamp = item.watchedAt,
                    formattedDate = dateFmt.format(Date(item.watchedAt))
                )
            }
        }
    }

    // Build top genres from catalog items matching watched IDs (plus catalog items in general if watched metadata is sparse)
    val watchedIdSet = HashSet<String>().apply {
        addAll(movieKeys)
        allProgress.forEach { add(it.contentId) }
        watchedItems.forEach { add(it.contentId) }
    }
    val genreFreq = HashMap<String, Int>()
    val byId = catalogItems.associateBy { it.id }
    watchedIdSet.forEach { id ->
        byId[id]?.genres?.forEach { raw ->
            val g = raw.trim()
            if (g.isNotEmpty() && g.none { it.isDigit() }) {
                genreFreq[g] = (genreFreq[g] ?: 0) + 1
            }
        }
    }
    if (genreFreq.isEmpty()) {
        catalogItems.take(60).forEach { meta ->
            meta.genres.forEach { raw ->
                val g = raw.trim()
                if (g.isNotEmpty() && g.none { it.isDigit() }) {
                    genreFreq[g] = (genreFreq[g] ?: 0) + 1
                }
            }
        }
    }
    val topGenres = genreFreq.entries
        .sortedByDescending { it.value }
        .take(6)
        .map { it.key to it.value }

    val dayFullNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val maxDayIdx = dayCounts.indices.maxByOrNull { dayCounts[it] } ?: 0
    val peakDayName = if (dayCounts[maxDayIdx] > 0) dayFullNames[maxDayIdx] else "Weekend"

    val totalMinutes = (totalWatchedMs / 60_000L).coerceAtLeast(0L)
    val totalHours = totalMinutes / 60.0

    val recentHistory = recentMap.values
        .sortedByDescending { it.timestamp }
        .take(20)

    return ComputedViewingStats(
        totalMoviesWatched = movieKeys.size,
        totalEpisodesWatched = episodeKeys.size,
        moviesThisMonth = moviesThisMonth,
        episodesThisMonth = episodesThisMonth,
        totalHoursWatched = totalHours,
        totalMinutesWatched = totalMinutes,
        currentMonthName = currentMonthName,
        currentMonthShort = currentMonthShort,
        dayOfWeekCounts = dayCounts.toList(),
        peakDayName = peakDayName,
        activeDaysLast30 = activeDayKeysLast30.size,
        topGenres = topGenres,
        recentHistory = recentHistory
    )
}
