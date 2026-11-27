package com.nuvio.tv.ui.newlook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.ui.screens.detail.ignoreHeldSelect
import com.nuvio.tv.ui.screens.detail.ndClickable
import kotlinx.coroutines.delay

/** One pill in a [NewLookOptionsMenu]. A [checked] one shows a tick; a disabled one is dimmed and can't be picked. */
internal class NewLookOption(
    val label: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Nuvio-Legacy side-by-side hold menu: left preview card with 16:9 artwork & title details,
 * right floating frosted dark-glass action list with icon rows and dynamic accent focus pill.
 */
@Composable
internal fun NewLookOptionsMenu(
    title: String,
    subtitle: String?,
    options: List<NewLookOption>,
    onDismiss: () -> Unit,
    image: String? = null,
    initialIndex: Int = 0,
    contentKey: Any? = null
) {
    val accent = com.nuvio.tv.ui.screens.player.rememberDynamicArtworkAccent(image)
    val accentContent = if (
        (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f) > 0.62f
    ) Color(0xFF0A0C10) else Color.White

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val glass = com.nuvio.tv.ui.theme.rememberPopupGlassTokens(accent)
        val glassFill = glass.fill
        val glassBorder = glass.border
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Left: Compact frosted glass preview card with dynamic artwork border
                if (image != null) {
                    val context = LocalContext.current
                    Column(
                        modifier = Modifier
                            .width(248.dp)
                            .clip(CardShape)
                            .background(glassFill)
                            .border(1.dp, glassBorder, CardShape)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        ) {
                            AsyncImage(
                                model = remember(image) { ImageRequest.Builder(context).data(image).build() },
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Brush.verticalGradient(0.50f to Color.Transparent, 1f to Color(0xCC0A0D14)))
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(glass.captionBg)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = title,
                                color = glass.primaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    color = glass.secondaryText,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Right: Compact frosted glass action menu
                Column(
                    modifier = Modifier
                        .width(236.dp)
                        .clip(CardShape)
                        .background(glassFill)
                        .border(1.dp, glassBorder, CardShape)
                        .ignoreHeldSelect()
                        .focusGroup()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        color = glass.secondaryText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                    val requesters = remember(contentKey, options.size) { List(options.size) { FocusRequester() } }
                    LaunchedEffect(contentKey, options.size) {
                        val target = requesters.getOrNull(initialIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
                            ?: return@LaunchedEffect
                        repeat(8) {
                            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                            delay(40)
                        }
                    }
                    options.forEachIndexed { i, option ->
                        OptionPill(option, requesters[i], accent, accentContent, glass.primaryText)
                    }
                }
            }
        }
    }
}

private fun optionIconFor(label: String): ImageVector {
    val l = label.lowercase()
    return when {
        "detail" in l -> Icons.Outlined.Info
        "begin" in l || "start from" in l || "restart" in l -> Icons.Filled.Replay
        "play" in l || "resume" in l -> Icons.Filled.PlayArrow
        "watch" in l -> Icons.Outlined.Visibility
        "poster" in l || "thumb" in l || "backdrop" in l || "art" in l -> Icons.Outlined.Image
        "save" in l || "bin" in l || "library" in l || "list" in l -> Icons.Outlined.BookmarkBorder
        "remove" in l || "hide" in l || "delete" in l -> Icons.Filled.Close
        else -> Icons.Outlined.Info
    }
}

@Composable
private fun OptionPill(
    option: NewLookOption,
    requester: FocusRequester,
    accent: Color,
    accentContent: Color,
    unfocusedFg: Color = Color.White
) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) accentContent else unfocusedFg.copy(alpha = 0.92f)
    val isDestructive = option.label.lowercase().let { "remove" in it || "delete" in it || "hide" in it }
    val focusFill = Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.82f)))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(33.dp)
            .clip(PillShape)
            .then(if (focused) Modifier.background(focusFill) else Modifier.background(Color.Transparent))
            .border(
                width = if (focused) 1.dp else 0.dp,
                color = if (focused) Color.White.copy(alpha = 0.65f) else Color.Transparent,
                shape = PillShape
            )
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = {
                if (option.enabled) option.onClick()
            })
            .alpha(if (option.enabled) 1f else 0.4f)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(optionIconFor(option.label), contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(
            text = option.label,
            color = fg,
            fontSize = 12.5.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (option.checked) {
            Text("✓", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else if (!isDestructive) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = fg.copy(alpha = if (focused) 0.9f else 0.45f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private val CardShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(999.dp)
private val GlassBg = Color(0xFF141822).copy(alpha = 0.92f)
private val MutedText = Color(0xFFB0B6C2)
