@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.StreamBadgeChips
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import java.util.Locale

private const val PREFS_PLAYER_STYLE = "nuvio_legacy_player_style"
private const val KEY_ACCENT_HEX = "player_accent_hex"

data class PlayerAccentOption(
    val name: String,
    val hex: String,
    val color: Color
)

val PLAYER_ACCENT_OPTIONS = listOf(
    PlayerAccentOption("Apple TV White", "#FFFFFF", Color(0xFFFFFFFF)),
    PlayerAccentOption("Cinema Gold", "#FFB300", Color(0xFFFFB300)),
    PlayerAccentOption("Crimson Red", "#E53935", Color(0xFFE53935)),
    PlayerAccentOption("Electric Cyan", "#00E5FF", Color(0xFF00E5FF)),
    PlayerAccentOption("Royal Purple", "#8E24AA", Color(0xFF8E24AA)),
    PlayerAccentOption("Emerald Green", "#00C853", Color(0xFF00C853)),
    PlayerAccentOption("Sunset Orange", "#FF6D00", Color(0xFFFF6D00)),
    PlayerAccentOption("Theme Default", "THEME", Color.Unspecified)
)

private val accentChangeListeners = mutableListOf<() -> Unit>()

fun getSavedPlayerAccentHex(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_PLAYER_STYLE, Context.MODE_PRIVATE)
    return prefs.getString(KEY_ACCENT_HEX, "#FFFFFF") ?: "#FFFFFF"
}

fun setSavedPlayerAccentHex(context: Context, hex: String) {
    val prefs = context.getSharedPreferences(PREFS_PLAYER_STYLE, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_ACCENT_HEX, hex).apply()
    accentChangeListeners.toList().forEach { it.invoke() }
}

@Composable
fun rememberPlayerAccentColor(): Color {
    val context = LocalContext.current
    val themeSecondary = com.nuvio.tv.ui.theme.NuvioTheme.extendedColors.focusRing
    var selectedHex by remember { mutableStateOf(getSavedPlayerAccentHex(context)) }

    DisposableEffect(context) {
        val listener = { selectedHex = getSavedPlayerAccentHex(context) }
        accentChangeListeners.add(listener)
        onDispose { accentChangeListeners.remove(listener) }
    }

    val match = PLAYER_ACCENT_OPTIONS.firstOrNull { it.hex.equals(selectedHex, ignoreCase = true) }
    return if (match == null || match.hex == "THEME" || match.color == Color.Unspecified) {
        themeSecondary
    } else {
        match.color
    }
}

/**
 * Apple TV+ Top-Left Cinematic Header:
 * Always displays the Movie or Series graphic ClearLogo (with automatic Metahub logo fallback when addon logo is null),
 * followed by the episode badge (`S1 · E1 — Episode Title`), `Year · Runtime`, and Apple TV frosted format pills.
 */
@Composable
fun NuvioLegacyTopLeftBadges(
    uiState: PlayerUiState,
    contentId: String? = null,
    durationMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val displayName = if (uiState.currentSeason != null && uiState.currentEpisode != null) {
        uiState.contentName ?: uiState.title
    } else {
        uiState.title
    }

    val imdbId = remember(contentId, uiState.currentVideoId, uiState.backdrop) {
        val combined = listOfNotNull(contentId, uiState.currentVideoId, uiState.backdrop).joinToString(" ")
        Regex("(tt\\d{5,12})").find(combined)?.groupValues?.get(1)
    }

    val logoCandidates = remember(uiState.logo, imdbId) {
        buildList {
            uiState.logo?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            if (imdbId != null) {
                val med = "https://images.metahub.space/logo/medium/$imdbId/img"
                val lrg = "https://images.metahub.space/logo/large/$imdbId/img"
                if (med !in this) add(med)
                if (lrg !in this) add(lrg)
            }
        }
    }

    var candidateIndex by remember(logoCandidates) { mutableIntStateOf(0) }
    val activeLogoUrl = logoCandidates.getOrNull(candidateIndex)

    Column(
        modifier = modifier.widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (activeLogoUrl != null) {
            AsyncImage(
                model = remember(context, activeLogoUrl) {
                    ImageRequest.Builder(context)
                        .data(activeLogoUrl)
                        .memoryCacheKey(activeLogoUrl)
                        .build()
                },
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = {
                    if (candidateIndex + 1 <= logoCandidates.size) {
                        candidateIndex += 1
                    }
                },
                modifier = Modifier
                    .height(68.dp)
                    .widthIn(max = 380.dp)
            )
        } else if (displayName.isNotBlank()) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val durationMinutes = (durationMs / 60_000L).toInt().takeIf { it > 0 }
        val yearAndRuntime = buildList {
            uiState.releaseYear?.takeIf { it.isNotBlank() }?.let(::add)
            durationMinutes?.let { add("${it}m") }
        }.joinToString("  ·  ")

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                val seasonEpisodeCode = stringResource(
                    R.string.season_episode_format,
                    uiState.currentSeason,
                    uiState.currentEpisode
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = seasonEpisodeCode,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
                val localizedEpisodeTitle = uiState.currentEpisodeTitle
                    ?.takeIf { it.isNotBlank() }
                    ?.localizeEpisodeTitle(context)
                if (localizedEpisodeTitle != null) {
                    Text(
                        text = localizedEpisodeTitle,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White.copy(alpha = 0.94f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (yearAndRuntime.isNotBlank()) {
                Text(
                    text = yearAndRuntime,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1
                )
            }
        }

        // Apple TV+ Frosted Format & Audio Pills directly under the logo & episode line
        NuvioLegacyInlineStreamBadges(uiState = uiState)
    }
}

/**
 * Apple TV+ Primary Play / Pause Pill inside the floating glass control dock.
 */
@Composable
fun NuvioLegacyPrimaryPlayButton(
    isPlaying: Boolean,
    accentColor: Color,
    playPainter: Painter,
    pausePainter: Painter,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    onDownKey: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "appleTvPlayScale"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isFocused) accentColor else Color.White.copy(alpha = 0.16f),
        animationSpec = tween(durationMillis = 150),
        label = "appleTvPlayBg"
    )
    val focusedContent = if (accentColor.luminance() > 0.60f) Color(0xFF0B0D12) else Color.White
    val contentColor = if (isFocused) focusedContent else Color.White
    val label = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play)

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(44.dp)
            .focusRequester(focusRequester)
            .focusProperties { up = upFocusRequester }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDownKey()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(999.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = bgColor,
            focusedContainerColor = accentColor,
            contentColor = contentColor,
            focusedContentColor = focusedContent
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                shape = RoundedCornerShape(999.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                painter = if (isPlaying) pausePainter else playPainter,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Apple TV+ Dock Action Button:
 * Sleek circular glass icon button that smoothly expands into a pill showing [Icon + Label] when focused.
 */
@Composable
fun NuvioLegacyCircleIconButton(
    icon: ImageVector,
    iconPainter: Painter? = null,
    contentDescription: String,
    accentColor: Color,
    onClick: () -> Unit,
    upFocusRequester: FocusRequester,
    onDownKey: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "appleTvDockBtnScale"
    )
    val focusedContentColor = if (accentColor.luminance() > 0.60f) Color(0xFF0B0D12) else Color.White

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(44.dp)
            .widthIn(min = 44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { up = upFocusRequester }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDownKey()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(999.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = accentColor,
            contentColor = Color.White.copy(alpha = 0.92f),
            focusedContentColor = focusedContentColor
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(999.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.92f)),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 180))
                .padding(horizontal = if (isFocused) 15.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (iconPainter != null) {
                Icon(
                    painter = iconPainter,
                    contentDescription = contentDescription,
                    tint = if (isFocused) focusedContentColor else Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (isFocused) focusedContentColor else Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(20.dp)
                )
            }
            if (isFocused && contentDescription.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = contentDescription,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = focusedContentColor,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Apple TV+ Split-Tone Timestamp (`0:14:22   -1:42:10 · 1:56:32`)
 */
@Composable
fun NuvioLegacySplitTimeText(
    currentFormatted: String,
    durationFormatted: String,
    remainingFormatted: String? = null,
    modifier: Modifier = Modifier
) {
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            ) {
                append(currentFormatted)
            }
            withStyle(
                SpanStyle(
                    color = Color.White.copy(alpha = 0.62f),
                    fontWeight = FontWeight.Medium
                )
            ) {
                if (!remainingFormatted.isNullOrBlank()) {
                    append("  ·  -")
                    append(remainingFormatted)
                }
                append("  /  ")
                append(durationFormatted)
            }
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        modifier = modifier
    )
}

/**
 * Apple TV+ Frosted Glass Format & Audio Pills (`4K UHD`, `Dolby Vision`, `HDR10+`, `Dolby Atmos`, `REMUX`, `HEVC`, size).
 */
@Composable
fun NuvioLegacyInlineStreamBadges(
    uiState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val rawText = remember(
        uiState.currentStreamName,
        uiState.currentStreamTitle,
        uiState.currentStreamDescription,
        uiState.streamInfoData?.filename
    ) {
        listOfNotNull(
            uiState.currentStreamName,
            uiState.currentStreamTitle,
            uiState.currentStreamDescription,
            uiState.streamInfoData?.filename
        ).joinToString(" ")
    }
    val lower = remember(rawText) { rawText.lowercase(Locale.US) }

    val selectedAudioText = remember(uiState.audioTracks, uiState.selectedAudioTrackIndex) {
        val selected = uiState.audioTracks.getOrNull(uiState.selectedAudioTrackIndex)
        listOfNotNull(
            selected?.name,
            selected?.codec,
            selected?.language,
            selected?.channelCount?.let { if (it >= 8) "7.1" else if (it >= 6) "5.1" else null }
        ).joinToString(" ").lowercase(Locale.US)
    }
    val combinedAudio = "$lower $selectedAudioText"
    val vWidth = uiState.streamInfoData?.videoWidth ?: 0

    val chips = remember(lower, combinedAudio, vWidth) {
        buildList {
            // 1. Resolution
            when {
                vWidth >= 3800 || lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd") ->
                    add("4K UHD")
                vWidth >= 1900 || lower.contains("1080p") || lower.contains("fhd") ->
                    add("1080p HD")
                vWidth >= 1200 || lower.contains("720p") || lower.contains("hd") ->
                    add("720p HD")
            }
            // 2. Dynamic Range (Dolby Vision / HDR10+ / HDR10)
            if (lower.contains("dolby vision") || lower.contains("dovi") || Regex("""\bdv\b""").containsMatchIn(lower)) {
                add("Dolby Vision")
            }
            if (lower.contains("hdr10+") || lower.contains("hdr10plus")) {
                add("HDR10+")
            } else if (lower.contains("hdr10") || Regex("""\bhdr\b""").containsMatchIn(lower)) {
                add("HDR10")
            }
            // 3. Audio Format (Dolby Atmos / TrueHD / DTS-HD / 7.1 / 5.1)
            when {
                combinedAudio.contains("atmos") || combinedAudio.contains("eac3-joc") -> add("Dolby Atmos")
                combinedAudio.contains("truehd") || combinedAudio.contains("true-hd") -> add("Dolby TrueHD")
                combinedAudio.contains("dts-hd") || combinedAudio.contains("dts:x") || combinedAudio.contains("dtsx") -> add("DTS-HD MA")
                Regex("""\bdts\b""").containsMatchIn(combinedAudio) -> add("DTS")
            }
            if (combinedAudio.contains("7.1")) {
                add("7.1")
            } else if (combinedAudio.contains("5.1")) {
                add("5.1")
            }
            // 4. Source / Codec
            if (lower.contains("remux")) {
                add("REMUX")
            } else if (lower.contains("hevc") || lower.contains("x265") || lower.contains("h265") || lower.contains("h.265")) {
                add("HEVC")
            } else if (lower.contains("av1")) {
                add("AV1")
            }
            // 5. File Size
            Regex("""(\d+(?:\.\d+)?\s?(?:GB|MB))""", RegexOption.IGNORE_CASE).find(rawText)?.value?.let { sizeStr ->
                add(sizeStr.uppercase(Locale.US))
            }
        }
    }

    if (chips.isEmpty() && uiState.currentStreamBadges.isEmpty()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { label ->
            val isHighlight = label.contains("Dolby") || label.contains("4K") || label.contains("HDR")
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (isHighlight) Color.White.copy(alpha = 0.16f)
                        else Color(0xFF141720).copy(alpha = 0.78f)
                    )
                    .border(
                        width = 0.75.dp,
                        color = if (isHighlight) Color.White.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(7.dp)
                    )
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    ),
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
        }
        if (chips.isEmpty() && uiState.currentStreamBadges.isNotEmpty()) {
            StreamBadgeChips(badges = uiState.currentStreamBadges)
        }
    }
}

/**
 * Dialog allowing the user to pick the player accent color right inside the player.
 */
@Composable
fun PlayerAccentColorPickerDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentHex by remember { mutableStateOf(getSavedPlayerAccentHex(context)) }
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(420.dp)
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(22.dp),
            colors = SurfaceDefaults.colors(
                containerColor = Color(0xFF141720),
                contentColor = Color.White
            ),
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(22.dp)
            )
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Player Highlight Theme",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = "Choose the highlight color for the Apple TV scrubber and focused dock pills.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))

                PLAYER_ACCENT_OPTIONS.forEachIndexed { index, option ->
                    val isSelected = option.hex.equals(currentHex, ignoreCase = true)
                    val swatchColor = if (option.color == Color.Unspecified) {
                        com.nuvio.tv.ui.theme.NuvioTheme.extendedColors.focusRing
                    } else {
                        option.color
                    }

                    Surface(
                        onClick = {
                            currentHex = option.hex
                            setSavedPlayerAccentHex(context, option.hex)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier),
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                            shape = RoundedCornerShape(12.dp)
                        ),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = Color.White.copy(alpha = 0.24f),
                            contentColor = Color.White,
                            focusedContentColor = Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                )
                                Text(
                                    text = option.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = swatchColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
