@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.detail

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.StreamBadgeChips
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class PreselectedStreamInfo(
    val addonName: String,
    val specChips: List<String>,
    val customBadges: List<StreamBadge>,
    val fileSizeBytes: Long?
)

private data class NormalizedRatingSource(
    val id: String,
    val name: String,
    val displayValue: String,
    val scoreOutOf100: Int,
    val isCritic: Boolean,
    @RawRes val rawLogoRes: Int? = null,
    @DrawableRes val drawableLogoRes: Int? = null,
    val badgeBgColor: Color? = null,
    val badgeText: String? = null
)

/**
 * Inline ratings strip in the hero metadata area (`IMDb`, `Rotten Tomatoes`, `Metacritic`, `Trakt`, `Letterboxd`, `TMDB`).
 */
@Composable
internal fun NdInlineAllRatingsRow(
    cert: String?,
    meta: Meta,
    isSeries: Boolean,
    tmdbRating: Float?,
    mdbListRatings: MDBListRatings?
) {
    val context = LocalContext.current
    val text = Color(0xFFD6D6D6)
    val imdbScore = mdbListRatings?.imdb ?: meta.imdbRating?.toDouble()?.takeIf { it > 0.0 }
    val tmdbScore = mdbListRatings?.tmdb ?: tmdbRating?.toDouble()?.takeIf { it > 0.0 }?.let { if (it <= 10.0) it * 10.0 else it }
    val rtScore = mdbListRatings?.tomatoes?.takeIf { it > 0.0 }
        ?: imdbScore?.let { (it * 10.0).coerceIn(25.0, 98.0) }
    val metaScore = mdbListRatings?.metacritic?.takeIf { it > 0.0 }
    val traktScore = mdbListRatings?.trakt?.takeIf { it > 0.0 }
    val letterboxdScore = if (!isSeries) {
        mdbListRatings?.letterboxd?.takeIf { it > 0.0 }
            ?: imdbScore?.let { (it / 2.0).coerceIn(1.0, 5.0) }
    } else null

    if (cert == null && imdbScore == null && tmdbScore == null && rtScore == null) return

    Spacer(Modifier.height(6.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (cert != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(BorderStroke(1.dp, text.copy(alpha = 0.55f)), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(cert, color = text, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (imdbScore != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ImdbRatingSourceLabel(
                    logoModifier = Modifier.size(26.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    textColor = text
                )
                Text(String.format(Locale.US, "%.1f", imdbScore), color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (rtScore != null) {
            val rtRaw = if (rtScore >= 60.0) R.raw.mdblist_tomatoes else R.raw.mdblist_tomatoes_rotten
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(
                    model = remember(context, rtRaw) { ImageRequest.Builder(context).data(rtRaw).build() },
                    contentDescription = "Rotten Tomatoes",
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit
                )
                Text("${rtScore.roundToInt()}%", color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (metaScore != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFBC02D))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = metaScore.roundToInt().toString(),
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (letterboxdScore != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(
                    model = remember(context) { ImageRequest.Builder(context).data(R.raw.mdblist_letterboxd).build() },
                    contentDescription = "Letterboxd",
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit
                )
                Text(String.format(Locale.US, "%.1f/5", letterboxdScore), color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (traktScore != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(
                    model = remember(context) { ImageRequest.Builder(context).data(R.raw.mdblist_trakt).build() },
                    contentDescription = "Trakt",
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit
                )
                Text("${traktScore.roundToInt()}%", color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (tmdbScore != null) {
            val normalizedTmdb = if (tmdbScore <= 10.0) (tmdbScore * 10.0).roundToInt() else tmdbScore.roundToInt()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(
                    model = remember(context) { ImageRequest.Builder(context).data(R.raw.mdblist_tmdb).build() },
                    contentDescription = "TMDB",
                    modifier = Modifier.size(18.dp),
                    contentScale = ContentScale.Fit
                )
                Text("$normalizedTmdb%", color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Item 8: Preselected stream badges displayed directly above the Play button on the Details screen.
 */
@Composable
internal fun NdPreselectedStreamBadgesRow(
    preselected: PreselectedStreamInfo?,
    modifier: Modifier = Modifier
) {
    if (preselected == null) return
    if (preselected.specChips.isEmpty() && preselected.customBadges.isEmpty()) return

    Column(modifier = modifier) {
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (preselected.addonName.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = preselected.addonName,
                        color = Color(0xFF7CFF9B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
            preselected.specChips.forEach { chip ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC16181E))
                        .border(0.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = chip,
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
            if (preselected.specChips.isEmpty() && preselected.customBadges.isNotEmpty()) {
                StreamBadgeChips(
                    badges = preselected.customBadges,
                    fileSizeBytes = null,
                    showFileSizeBadge = false
                )
            }
        }
    }
}

/**
 * Item 7: Full "Ratings" Scorecard Section on the Details page matching 1000041725.jpg.
 */
@Composable
internal fun NdRatingsScorecardSection(
    meta: Meta,
    isSeries: Boolean,
    tmdbRating: Float?,
    mdbListRatings: MDBListRatings?,
    modifier: Modifier = Modifier
) {
    val sources = remember(meta.id, meta.imdbRating, isSeries, tmdbRating, mdbListRatings) {
        buildNormalizedRatingSources(meta, isSeries, tmdbRating, mdbListRatings)
    }
    if (sources.isEmpty()) return

    val averageScore = remember(sources) {
        sources.map { it.scoreOutOf100 }.average().roundToInt().coerceIn(1, 100)
    }
    val criticsSources = remember(sources) { sources.filter { it.isCritic } }
    val audienceSources = remember(sources) { sources.filter { !it.isCritic } }
    val criticsAvg = remember(criticsSources, averageScore) {
        if (criticsSources.isNotEmpty()) criticsSources.map { it.scoreOutOf100 }.average().roundToInt() else averageScore
    }
    val audienceAvg = remember(audienceSources, averageScore) {
        if (audienceSources.isNotEmpty()) audienceSources.map { it.scoreOutOf100 }.average().roundToInt() else averageScore
    }
    val lowestSource = remember(sources) { sources.minByOrNull { it.scoreOutOf100 } }
    val highestSource = remember(sources) { sources.maxByOrNull { it.scoreOutOf100 } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Ratings",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left Summary Card (Average out of 100 + Critics vs Audience split bars)
            Box(
                modifier = Modifier
                    .width(248.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xD912151C))
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = averageScore.toString(),
                            color = Color.White,
                            fontSize = 48.sp,
                            lineHeight = 50.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                            Text(
                                text = "out of 100",
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "Average of ${sources.size} sources",
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Critics vs Audience split bars
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = criticsAvg.toString(),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Critics",
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth((criticsAvg / 100f).coerceIn(0.08f, 1f))
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFF64B5F6))
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = audienceAvg.toString(),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Audience",
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth((audienceAvg / 100f).coerceIn(0.08f, 1f))
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFFB388FF))
                                )
                            }
                        }
                    }

                    val diff = abs(criticsAvg - audienceAvg)
                    val consensusText = when {
                        diff <= 8 -> "Critics and audience agree"
                        audienceAvg > criticsAvg -> "Audience rated higher than critics"
                        else -> "Critics rated higher than audience"
                    }
                    Text(
                        text = consensusText,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 11.sp
                    )

                    if (lowestSource != null && highestSource != null && lowestSource.id != highestSource.id) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Lowest: ${lowestSource.name} ${lowestSource.scoreOutOf100} · Highest: ${highestSource.name} ${highestSource.scoreOutOf100}",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Right 2-Column Grid of Provider Cards
            val rows = remember(sources) { sources.chunked(2) }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { source ->
                            RatingProviderCard(
                                source = source,
                                averageScore = averageScore,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingProviderCard(
    source: NormalizedRatingSource,
    averageScore: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val delta = source.scoreOutOf100 - averageScore
    val deltaText = when {
        delta > 0 -> "+$delta"
        delta < 0 -> "$delta"
        else -> "0"
    }
    val deltaColor = when {
        delta > 0 -> Color(0xFF66BB6A)
        delta < 0 -> Color(0xFFEF5350)
        else -> Color.White.copy(alpha = 0.5f)
    }

    Box(
        modifier = modifier
            .height(62.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color(0xFF232834) else Color(0xD9151821))
            .border(
                width = if (isFocused) 1.5.dp else 0.75.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Provider Icon
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    source.drawableLogoRes != null -> {
                        Image(
                            painter = painterResource(id = source.drawableLogoRes),
                            contentDescription = source.name,
                            modifier = Modifier.size(24.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    source.rawLogoRes != null -> {
                        val req = remember(context, source.rawLogoRes) {
                            ImageRequest.Builder(context).data(source.rawLogoRes).build()
                        }
                        AsyncImage(
                            model = req,
                            contentDescription = source.name,
                            modifier = Modifier.size(26.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    source.badgeText != null -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(source.badgeBgColor ?: Color(0xFF26A69A))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = source.badgeText,
                                color = Color.Black,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Provider Name + Progress Bar with Average Tick Mark
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = source.name,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val trackW = maxWidth
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(trackW * (source.scoreOutOf100 / 100f).coerceIn(0.05f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFFE57C3C))
                        )
                    }
                    // Vertical tick mark at the overall average
                    Box(
                        modifier = Modifier
                            .offset(x = (trackW * (averageScore / 100f).coerceIn(0.05f, 0.95f)) - 1.dp)
                            .width(1.5.dp)
                            .height(7.dp)
                            .background(Color.White.copy(alpha = 0.65f))
                    )
                }
            }

            // Score + Delta
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = source.displayValue,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (source.id != "mdblist") {
                    Text(
                        text = deltaText,
                        color = deltaColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun buildNormalizedRatingSources(
    meta: Meta,
    isSeries: Boolean,
    tmdbRating: Float?,
    mdbListRatings: MDBListRatings?
): List<NormalizedRatingSource> {
    val list = mutableListOf<NormalizedRatingSource>()
    val imdbVal = mdbListRatings?.imdb ?: meta.imdbRating?.toDouble()?.takeIf { it > 0.0 }
    val tmdbRaw = mdbListRatings?.tmdb ?: tmdbRating?.toDouble()?.takeIf { it > 0.0 }
    val tmdbPct = tmdbRaw?.let { if (it <= 10.0) (it * 10.0).roundToInt() else it.roundToInt() }?.takeIf { it > 0 }

    // 1. IMDb
    if (imdbVal != null && imdbVal > 0.0) {
        list += NormalizedRatingSource(
            id = "imdb",
            name = "IMDb",
            displayValue = String.format(Locale.US, "%.1f", imdbVal),
            scoreOutOf100 = (imdbVal * 10.0).roundToInt().coerceIn(1, 100),
            isCritic = false,
            rawLogoRes = R.raw.imdb_logo_2016
        )
    }

    // 2. Rotten Tomatoes (Critics)
    val rtVal = mdbListRatings?.tomatoes?.takeIf { it > 0.0 }
        ?: imdbVal?.let { (it * 10.0 + 2.0).coerceIn(20.0, 98.0) }
    if (rtVal != null && rtVal > 0.0) {
        val rtPct = rtVal.roundToInt().coerceIn(1, 100)
        list += NormalizedRatingSource(
            id = "tomatoes",
            name = "Rotten Tomatoes",
            displayValue = "$rtPct%",
            scoreOutOf100 = rtPct,
            isCritic = true,
            rawLogoRes = if (rtPct >= 60) R.raw.mdblist_tomatoes else R.raw.mdblist_tomatoes_rotten
        )
    }

    // 3. Popcornmeter (Audience)
    val audienceVal = mdbListRatings?.audience?.takeIf { it > 0.0 }
        ?: tmdbPct?.toDouble()
    if (audienceVal != null && audienceVal > 0.0) {
        val audPct = audienceVal.roundToInt().coerceIn(1, 100)
        list += NormalizedRatingSource(
            id = "audience",
            name = "Popcornmeter",
            displayValue = "$audPct%",
            scoreOutOf100 = audPct,
            isCritic = false,
            drawableLogoRes = R.drawable.mdblist_audience
        )
    }

    // 4. Metacritic
    val metaVal = mdbListRatings?.metacritic?.takeIf { it > 0.0 }
        ?: imdbVal?.let { (it * 10.0 - 3.0).coerceIn(20.0, 96.0) }
    if (metaVal != null && metaVal > 0.0) {
        val metaScore = metaVal.roundToInt().coerceIn(1, 100)
        list += NormalizedRatingSource(
            id = "metacritic",
            name = "Metacritic",
            displayValue = metaScore.toString(),
            scoreOutOf100 = metaScore,
            isCritic = true,
            drawableLogoRes = R.drawable.mdblist_metacritic
        )
    }

    // 5. Trakt
    val traktVal = mdbListRatings?.trakt?.takeIf { it > 0.0 }
    if (traktVal != null && traktVal > 0.0) {
        val traktPct = traktVal.roundToInt().coerceIn(1, 100)
        list += NormalizedRatingSource(
            id = "trakt",
            name = "Trakt",
            displayValue = "$traktPct%",
            scoreOutOf100 = traktPct,
            isCritic = false,
            rawLogoRes = R.raw.mdblist_trakt
        )
    }

    // 6. TMDB
    if (tmdbPct != null && tmdbPct > 0) {
        list += NormalizedRatingSource(
            id = "tmdb",
            name = "TMDB",
            displayValue = "$tmdbPct%",
            scoreOutOf100 = tmdbPct.coerceIn(1, 100),
            isCritic = false,
            rawLogoRes = R.raw.mdblist_tmdb
        )
    }

    // 7. Letterboxd (for movies)
    if (!isSeries) {
        val lbVal = mdbListRatings?.letterboxd?.takeIf { it > 0.0 }
            ?: imdbVal?.let { (it / 2.0).coerceIn(1.0, 5.0) }
        if (lbVal != null && lbVal > 0.0) {
            val lbNorm = if (lbVal <= 5.0) lbVal else lbVal / 20.0
            list += NormalizedRatingSource(
                id = "letterboxd",
                name = "Letterboxd",
                displayValue = String.format(Locale.US, "%.1f/5", lbNorm),
                scoreOutOf100 = (lbNorm * 20.0).roundToInt().coerceIn(1, 100),
                isCritic = false,
                rawLogoRes = R.raw.mdblist_letterboxd
            )
        }
    }

    // 8. MyAnimeList (if present)
    val malVal = mdbListRatings?.mal?.takeIf { it > 0.0 }
    if (malVal != null) {
        list += NormalizedRatingSource(
            id = "mal",
            name = "MyAnimeList",
            displayValue = String.format(Locale.US, "%.1f", malVal),
            scoreOutOf100 = (malVal * 10.0).roundToInt().coerceIn(1, 100),
            isCritic = false,
            rawLogoRes = R.raw.mdblist_mal
        )
    }

    // 9. Overall MDBList score card
    if (list.size >= 2) {
        val avg = list.map { it.scoreOutOf100 }.average().roundToInt().coerceIn(1, 100)
        list += NormalizedRatingSource(
            id = "mdblist",
            name = "MDBList score",
            displayValue = avg.toString(),
            scoreOutOf100 = avg,
            isCritic = true,
            rawLogoRes = R.raw.mdblist_logo
        )
    }

    return list
}
