package com.nuvio.tv.ui.screens.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Shown once playback has been paused: shifts the title, synopsis, and remaining-time bar upward
 * and displays the Cast row (circular actor photo + Actor Name + Character role) directly below
 * the remaining-time bar.
 */
@Composable
fun PauseOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    modifier: Modifier = Modifier,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    showClock: Boolean = true,
    castMembers: List<MetaCastMember> = emptyList()
) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerUiState by playerViewModel.uiState.collectAsState()
    val effectiveCast = remember(castMembers, playerUiState.castMembers) {
        (if (castMembers.isNotEmpty()) castMembers else playerUiState.castMembers).take(8)
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        modifier = modifier,
        captureKeys = false,
        dismissOnBackgroundClick = true,
        overlayTint = Color.Black.copy(alpha = 0.16f),
        drawShade = false,
        topEndContent = {
            if (showClock) PauseOverlayClock(modifier = Modifier.padding(top = 36.dp, end = 52.dp))
        }
    ) {
        // Darkest in the lower-left, behind the text and cast row, and clear towards the top right.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.76f),
                        0.55f to Color.Black.copy(alpha = 0.36f),
                        1f to Color.Transparent
                    )
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.38f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 58.dp,
                    end = 48.dp,
                    bottom = if (effectiveCast.isNotEmpty()) 36.dp else 60.dp
                )
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.48f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.pause_label).uppercase(),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                PauseTitle(title = title, logo = logo)
                val details = pauseDetails(episodeTitle, season, episode, year, type)
                if (details.isNotBlank()) {
                    Text(
                        text = details,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = androidx.compose.ui.text.TextStyle(textDirection = description.contentTextDirection()),
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = if (effectiveCast.isNotEmpty()) 3 else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (durationMs > 0L) {
                    PauseProgress(positionMs = positionMs, durationMs = durationMs)
                }
            }

            if (effectiveCast.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                PauseCastSection(castMembers = effectiveCast)
            }
        }
    }
}

@Composable
private fun PauseCastSection(castMembers: List<MetaCastMember>) {
    val context = LocalContext.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(0.88f),
        contentPadding = PaddingValues(end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(castMembers, key = { "${it.name}:${it.character.orEmpty()}" }) { member ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.38f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    val photoUrl = member.photo?.takeIf { it.isNotBlank() }
                    if (photoUrl != null) {
                        val request = remember(context, photoUrl) {
                            ImageRequest.Builder(context)
                                .data(photoUrl)
                                .size(96, 96)
                                .memoryCacheKey("pause_cast_$photoUrl")
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val initials = member.name
                            .split(" ")
                            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                            .take(2)
                            .joinToString("")
                        Text(
                            text = initials.ifBlank { "?" },
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(
                    modifier = Modifier.widthIn(max = 136.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = member.name,
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val role = member.character?.takeIf { it.isNotBlank() }
                    if (role != null) {
                        Text(
                            text = role,
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseTitle(title: String, logo: String?) {
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (!logo.isNullOrBlank() && !logoFailed) {
        val context = LocalContext.current
        AsyncImage(
            model = remember(logo) { ImageRequest.Builder(context).data(logo).memoryCacheKey(logo).crossfade(true).build() },
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { logoFailed = true },
            modifier = Modifier.height(64.dp).widthIn(max = 320.dp)
        )
    } else {
        Text(
            text = title,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 36.dp)
        )
    }
}

/** "2024 · S1 E2 · The Episode": the year, then the episode and its name for a series. */
@Composable
private fun pauseDetails(episodeTitle: String?, season: Int?, episode: Int?, year: String?, type: String?): String {
    val context = LocalContext.current
    val parts = buildList {
        year?.takeIf { it.isNotBlank() }?.let(::add)
        if (type in listOf("series", "tv") && season != null && episode != null) {
            add(stringResource(R.string.season_episode_format, season, episode))
        }
        episodeTitle?.takeIf { it.isNotBlank() }?.let { add(it.localizeEpisodeTitle(context)) }
    }
    return parts.joinToString("  ·  ")
}

/** A thin line of how far in, and the time left beside it. */
@Composable
private fun PauseProgress(positionMs: Long, durationMs: Long) {
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val leftMinutes = ((durationMs - positionMs).coerceAtLeast(0L) / 60_000L).toInt()
    val left = if (leftMinutes >= 60) {
        stringResource(R.string.pause_left_hours, leftMinutes / 60, leftMinutes % 60)
    } else {
        stringResource(R.string.pause_left_minutes, leftMinutes.coerceAtLeast(1))
    }
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }
        Text(
            text = left,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun PauseOverlayClock(modifier: Modifier = Modifier) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMillis = current
            delay((60_000L - (current % 60_000L)).coerceAtLeast(1_000L))
        }
    }

    Text(
        text = formatter.format(Date(nowMillis)),
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 28.sp,
        fontWeight = FontWeight.Light,
        modifier = modifier
    )
}
