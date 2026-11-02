@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.StreamBadgeChips
import java.util.Locale

private const val PREFS_PLAYER_STYLE = "nuvio_legacy_player_style"
private const val KEY_ACCENT_HEX = "player_accent_hex"

data class PlayerAccentOption(
    val name: String,
    val hex: String,
    val color: Color
)

val PLAYER_ACCENT_OPTIONS = listOf(
    PlayerAccentOption("Theme Default", "THEME", Color.Unspecified),
    PlayerAccentOption("Crimson Red", "#E53935", Color(0xFFE53935)),
    PlayerAccentOption("Pure White", "#FFFFFF", Color(0xFFFFFFFF)),
    PlayerAccentOption("Cinema Gold", "#FFB300", Color(0xFFFFB300)),
    PlayerAccentOption("Electric Cyan", "#00E5FF", Color(0xFF00E5FF)),
    PlayerAccentOption("Royal Purple", "#8E24AA", Color(0xFF8E24AA)),
    PlayerAccentOption("Emerald Green", "#00C853", Color(0xFF00C853)),
    PlayerAccentOption("Sunset Orange", "#FF6D00", Color(0xFFFF6D00))
)

private val accentChangeListeners = mutableListOf<() -> Unit>()

fun getSavedPlayerAccentHex(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_PLAYER_STYLE, Context.MODE_PRIVATE)
    return prefs.getString(KEY_ACCENT_HEX, "#E53935") ?: "#E53935"
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
 * Standalone pill button on the far left of the Nuvio Legacy 2.0 player dock:
 * Shows `▶ Continue` when paused and `❚❚ Pause` when playing, filled with the selected accent color.
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
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "playPillScale"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isFocused) accentColor else accentColor.copy(alpha = 0.88f),
        animationSpec = tween(durationMillis = 140),
        label = "playPillBg"
    )
    val contentColor = if (accentColor.luminance() > 0.62f) Color(0xFF101216) else Color.White
    val label = if (isPlaying) stringResource(R.string.cd_pause) else "Continue"

    Surface(
        onClick = onClick,
        modifier = modifier
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
            focusedContentColor = contentColor
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.92f)),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = contentColor
            )
        }
    }
}

/**
 * Separate circular dark icon button used in Nuvio Legacy 2.0 player dock
 * (Subtitles, Audio/Soundwave, Aspect Ratio, Sources/Layers, Episodes/Filmstrip, Accent Color).
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
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "circleBtnScale"
    )
    val focusedContentColor = if (accentColor.luminance() > 0.62f) Color(0xFF101216) else Color.White

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
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
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1E2026).copy(alpha = 0.86f),
            focusedContainerColor = accentColor,
            contentColor = Color.White.copy(alpha = 0.92f),
            focusedContentColor = focusedContentColor
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                shape = CircleShape
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.92f)),
                shape = CircleShape
            )
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
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
        }
    }
}

/**
 * Split-tone timestamp `0:05 / 3:24:27` on the bottom-right of the Nuvio Legacy 2.0 dock.
 */
@Composable
fun NuvioLegacySplitTimeText(
    currentFormatted: String,
    durationFormatted: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(currentFormatted)
            }
            withStyle(
                SpanStyle(
                    color = Color.White.copy(alpha = 0.56f),
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(" / ")
                append(durationFormatted)
            }
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
        modifier = modifier
    )
}

/**
 * Top-left rounded square dark format cards (`Dolby ATMOS`, `Dolby VISION`, `HDR10+`, `HDR10`, `DTS-HD`, `TrueHD`)
 * matching screenshot `1000041684.jpg`.
 */
@Composable
fun NuvioLegacyTopLeftBadges(
    uiState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val streamText = remember(
        uiState.currentStreamName,
        uiState.currentStreamTitle,
        uiState.currentStreamDescription,
        uiState.currentStreamUrl,
        uiState.streamInfoData?.filename
    ) {
        listOfNotNull(
            uiState.currentStreamName,
            uiState.currentStreamTitle,
            uiState.currentStreamDescription,
            uiState.currentStreamUrl,
            uiState.streamInfoData?.filename
        ).joinToString(" ").lowercase(Locale.US)
    }

    val selectedAudioText = remember(uiState.audioTracks, uiState.selectedAudioTrackIndex) {
        val selected = uiState.audioTracks.getOrNull(uiState.selectedAudioTrackIndex)
        listOfNotNull(selected?.name, selected?.codec, selected?.language, selected?.channelCount?.let { if (it >= 8) "7.1" else if (it >= 6) "5.1" else null })
            .joinToString(" ")
            .lowercase(Locale.US)
    }

    val combinedAudio = "$streamText $selectedAudioText"

    val hasDolbyAtmos = remember(combinedAudio) {
        combinedAudio.contains("atmos") || combinedAudio.contains("eac3-joc") || combinedAudio.contains("ddp5.1.atmos")
    }
    val hasDolbyVision = remember(streamText) {
        streamText.contains("dolby vision") ||
            streamText.contains("dovi") ||
            Regex("""\bdv\b""").containsMatchIn(streamText)
    }
    val hasHdr10Plus = remember(streamText) {
        streamText.contains("hdr10+") || streamText.contains("hdr10plus")
    }
    val hasHdr10 = remember(streamText, hasHdr10Plus) {
        !hasHdr10Plus && (streamText.contains("hdr10") || Regex("""\bhdr\b""").containsMatchIn(streamText))
    }
    val hasTrueHd = remember(combinedAudio) {
        combinedAudio.contains("truehd") || combinedAudio.contains("true-hd")
    }
    val hasDts = remember(combinedAudio) {
        combinedAudio.contains("dts-hd") || combinedAudio.contains("dts:x") || combinedAudio.contains("dtsx") || Regex("""\bdts\b""").containsMatchIn(combinedAudio)
    }

    val cards = remember(hasDolbyAtmos, hasDolbyVision, hasHdr10Plus, hasHdr10, hasTrueHd, hasDts) {
        buildList {
            if (hasDolbyAtmos) add("Dolby" to "ATMOS")
            else if (hasTrueHd) add("Dolby" to "TrueHD")
            else if (hasDts) add("DTS" to "HD MA")

            if (hasDolbyVision) add("Dolby" to "VISION")
            else if (hasHdr10Plus) add("HDR" to "10+")
            else if (hasHdr10) add("HDR" to "10")
        }
    }

    if (cards.isEmpty()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cards.forEach { (topLine, bottomLine) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF12151C).copy(alpha = 0.82f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "DD",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Text(
                            text = topLine,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                    Text(
                        text = bottomLine,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Inline stream quality / HDR / Codec / Audio / Size / Custom StreamBadgeChips shown in the player controls overlay.
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

    val chips = remember(lower, (uiState.streamInfoData?.videoWidth ?: 0), uiState.streamInfoData?.videoHeight) {
        buildList {
            // Resolution
            when {
                (uiState.streamInfoData?.videoWidth ?: 0) >= 3800 || lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd") ->
                    add(Triple("4K", Color(0xFFFFB300).copy(alpha = 0.22f), Color(0xFFFFD54F)))
                (uiState.streamInfoData?.videoWidth ?: 0) >= 1900 || lower.contains("1080p") || lower.contains("fhd") ->
                    add(Triple("1080p", Color(0xFF1E88E5).copy(alpha = 0.24f), Color(0xFF90CAF9)))
                (uiState.streamInfoData?.videoWidth ?: 0) >= 1200 || lower.contains("720p") || lower.contains("hd") ->
                    add(Triple("720p", Color(0xFF546E7A).copy(alpha = 0.28f), Color(0xFFCFD8DC)))
            }
            // HDR / DV
            if (lower.contains("dolby vision") || lower.contains("dovi") || Regex("""\bdv\b""").containsMatchIn(lower)) {
                add(Triple("DV", Color(0xFF8E24AA).copy(alpha = 0.28f), Color(0xFFE1BEE7)))
            }
            if (lower.contains("hdr10+") || lower.contains("hdr10plus")) {
                add(Triple("HDR10+", Color(0xFFF57C00).copy(alpha = 0.26f), Color(0xFFFFCC80)))
            } else if (lower.contains("hdr10") || Regex("""\bhdr\b""").containsMatchIn(lower)) {
                add(Triple("HDR", Color(0xFFF57C00).copy(alpha = 0.24f), Color(0xFFFFCC80)))
            }
            // Codec / Remux
            if (lower.contains("remux")) {
                add(Triple("REMUX", Color(0xFF00897B).copy(alpha = 0.28f), Color(0xFF80CBC4)))
            } else if (lower.contains("hevc") || lower.contains("x265") || lower.contains("h265") || lower.contains("h.265")) {
                add(Triple("HEVC", Color(0xFF3949AB).copy(alpha = 0.25f), Color(0xFF9FA8DA)))
            } else if (lower.contains("av1")) {
                add(Triple("AV1", Color(0xFF00ACC1).copy(alpha = 0.25f), Color(0xFF80DEEA)))
            }
            // Audio
            if (lower.contains("atmos")) {
                add(Triple("Atmos", Color(0xFF1565C0).copy(alpha = 0.28f), Color(0xFF90CAF9)))
            } else if (lower.contains("truehd")) {
                add(Triple("TrueHD", Color(0xFF1565C0).copy(alpha = 0.24f), Color(0xFF90CAF9)))
            } else if (lower.contains("7.1")) {
                add(Triple("7.1", Color(0xFF37474F).copy(alpha = 0.34f), Color(0xFFECEFF1)))
            } else if (lower.contains("5.1")) {
                add(Triple("5.1", Color(0xFF37474F).copy(alpha = 0.34f), Color(0xFFECEFF1)))
            }
            // Size
            Regex("""(\d+(?:\.\d+)?\s?(?:GB|MB))""", RegexOption.IGNORE_CASE).find(rawText)?.value?.let { sizeStr ->
                add(Triple(sizeStr.uppercase(Locale.US), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.88f)))
            }
        }
    }

    if (chips.isEmpty() && uiState.currentStreamBadges.isEmpty()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { (label, bg, fg) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .border(1.dp, fg.copy(alpha = 0.32f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = fg
                )
            }
        }
        if (uiState.currentStreamBadges.isNotEmpty()) {
            StreamBadgeChips(badges = uiState.currentStreamBadges)
        }
    }
}

/**
 * Dialog allowing the user to pick the player accent color (Crimson Red, Pure White, Cinema Gold,
 * Electric Cyan, Royal Purple, Emerald Green, Sunset Orange, or Theme Default) right inside the player.
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
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(
                containerColor = Color(0xFF16181F),
                contentColor = Color.White
            ),
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                shape = RoundedCornerShape(20.dp)
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Player Accent Color",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = "Choose the highlight color for the Continue/Pause pill, progress bar, and focused player controls.",
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
                            containerColor = if (isSelected) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = Color.White.copy(alpha = 0.22f),
                            contentColor = Color.White,
                            focusedContentColor = Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
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
