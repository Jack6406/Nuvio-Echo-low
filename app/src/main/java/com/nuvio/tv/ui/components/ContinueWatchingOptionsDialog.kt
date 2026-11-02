@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.data.local.CustomBackdropStore
import com.nuvio.tv.ui.newlook.NewLookOption
import com.nuvio.tv.ui.newlook.NewLookOptionsMenu
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem

/** URLs that failed to load — skip them immediately on next recomposition. */
internal val brokenImageUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

/** Holding a Continue Watching card: the new look's glass menu of what to do with it. */
@Composable
fun ContinueWatchingOptionsDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    showPlayManually: Boolean = false,
    onPlayManually: () -> Unit = {},
    onChangePoster: (() -> Unit)? = null
) {
    val isPlayEnabled = LocalPlaybackAvailability.current.canStream(item)
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.name
        is ContinueWatchingItem.NextUp -> item.info.name
    }
    val contentId = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.contentId
        is ContinueWatchingItem.NextUp -> item.info.contentId
    }
    val defaultImage = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.backdrop ?: item.progress.poster
        is ContinueWatchingItem.NextUp -> item.info.thumbnail ?: item.info.backdrop ?: item.info.poster
    }
    val image = CustomBackdropStore.resolve(contentId, defaultImage)
    NewLookOptionsMenu(
        title = title,
        subtitle = stringResource(R.string.cw_dialog_subtitle),
        image = image,
        options = listOfNotNull(
            NewLookOption(stringResource(R.string.cw_action_go_to_details), onClick = onDetails),
            if (showPlayManually && isPlayEnabled) NewLookOption(stringResource(R.string.play_manually), onClick = onPlayManually) else null,
            if (item is ContinueWatchingItem.InProgress && isPlayEnabled) {
                NewLookOption(stringResource(R.string.cw_action_start_from_beginning), onClick = onStartFromBeginning)
            } else {
                null
            },
            if (onChangePoster != null) {
                NewLookOption("Change poster / thumbnail", onClick = onChangePoster)
            } else null,
            NewLookOption(stringResource(R.string.cw_action_remove), onClick = onRemove)
        ),
        onDismiss = onDismiss
    )
}

private data class CwPosterCandidate(
    val label: String,
    val provider: String,
    val url: String?
)

/**
 * Item 9: Visual Poster / Thumbnail Picker for Continue Watching items opened from the Hold-OK menu.
 */
@Composable
fun ContinueWatchingPosterPickerDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val contentId = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.contentId
        is ContinueWatchingItem.NextUp -> item.info.contentId
    }
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.name
        is ContinueWatchingItem.NextUp -> item.info.name
    }
    val episodeThumb = when (item) {
        is ContinueWatchingItem.InProgress -> item.episodeThumbnail
        is ContinueWatchingItem.NextUp -> item.info.thumbnail
    }
    val itemBackdrop = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.backdrop
        is ContinueWatchingItem.NextUp -> item.info.backdrop
    }
    val itemPoster = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.poster
        is ContinueWatchingItem.NextUp -> item.info.poster
    }
    val imdbId = remember(contentId) {
        Regex("(tt\\d{5,12})").find(contentId)?.groupValues?.get(1)
    }

    val candidates = remember(contentId, episodeThumb, itemBackdrop, itemPoster, imdbId) {
        val seen = LinkedHashSet<String>()
        val list = ArrayList<CwPosterCandidate>()
        fun addIfValid(label: String, provider: String, url: String?) {
            val clean = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (seen.add(clean)) {
                list.add(CwPosterCandidate(label, provider, clean))
            }
        }
        addIfValid("Episode Thumbnail (Default)", "Episode Still", episodeThumb)
        addIfValid("Title Backdrop", "Catalog Backdrop", itemBackdrop)
        if (imdbId != null) {
            addIfValid("Metahub HD Backdrop", "Metahub", "https://images.metahub.space/background/medium/$imdbId/img")
            addIfValid("Metahub Full Backdrop", "Metahub", "https://images.metahub.space/background/large/$imdbId/img")
            addIfValid("Metahub Portrait Poster", "Metahub", "https://images.metahub.space/poster/medium/$imdbId/img")
        }
        addIfValid("Title Poster", "Catalog Poster", itemPoster)
        list
    }

    val currentOverride = CustomBackdropStore.getOverride(contentId)
    var customUrlInput by rememberSaveable { mutableStateOf(currentOverride.orEmpty()) }
    var showCustomUrlEntry by rememberSaveable { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Change Continue Watching Poster",
        subtitle = title,
        width = 580.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showCustomUrlEntry) {
                InputField(
                    value = customUrlInput,
                    onValueChange = { customUrlInput = it },
                    placeholder = "https://image.tmdb.org/t/p/w780/...",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (customUrlInput.isNotBlank()) {
                            CustomBackdropStore.setDirectOverride(context, contentId, customUrlInput.trim())
                            onDismiss()
                        }
                    },
                    modifier = Modifier.focusRequester(firstFocus)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (customUrlInput.isNotBlank()) {
                                CustomBackdropStore.setDirectOverride(context, contentId, customUrlInput.trim())
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White,
                            focusedContainerColor = Color.White,
                            contentColor = Color.Black,
                            focusedContentColor = Color.Black
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text("Apply Custom URL", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { showCustomUrlEntry = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = Color.White,
                            contentColor = Color.White,
                            focusedContentColor = Color.Black
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text("Back to Presets")
                    }
                }
            } else {
                candidates.forEachIndexed { index, candidate ->
                    val isSelected = currentOverride == candidate.url || (currentOverride == null && index == 0)
                    Button(
                        onClick = {
                            CustomBackdropStore.setDirectOverride(context, contentId, candidate.url)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = Color.White,
                            contentColor = Color.White,
                            focusedContentColor = Color.Black
                        ),
                        border = ButtonDefaults.border(
                            border = Border(
                                border = BorderStroke(1.dp, Color.White.copy(alpha = if (isSelected) 0.45f else 0.12f)),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(76.dp)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1A1C22))
                                ) {
                                    AsyncImage(
                                        model = remember(context, candidate.url) {
                                            ImageRequest.Builder(context)
                                                .data(candidate.url)
                                                .size(160, 90)
                                                .build()
                                        },
                                        contentDescription = candidate.label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Column {
                                    Text(
                                        text = candidate.label,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = candidate.provider,
                                        fontSize = 11.sp,
                                        color = Color.Gray,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showCustomUrlEntry = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = Color.White,
                            contentColor = Color.White,
                            focusedContentColor = Color.Black
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text("Custom Image URL...", fontSize = 13.sp)
                    }
                    if (currentOverride != null) {
                        Button(
                            onClick = {
                                CustomBackdropStore.setDirectOverride(context, contentId, null)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                focusedContainerColor = Color.White,
                                contentColor = Color.White,
                                focusedContentColor = Color.Black
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text("Reset to Default", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
