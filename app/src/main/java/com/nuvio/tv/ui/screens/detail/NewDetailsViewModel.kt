package com.nuvio.tv.ui.screens.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.poster.CustomPosterUrlResolver
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.CustomBackdropStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ReleaseReminder
import com.nuvio.tv.data.local.ReleaseRemindersStore
import com.nuvio.tv.data.local.TitleRating
import com.nuvio.tv.data.local.TitleRatings
import com.nuvio.tv.data.local.TitleRatingsStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.TitleCredits
import com.nuvio.tv.data.repository.TitleCreditsRepository
import com.nuvio.tv.data.repository.TitleReviewsRepository
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reviews for a title or an episode, as the new details page shows them. */
data class ReviewsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<TraktCommentReview> = emptyList()
)

@Immutable
data class BackdropCandidate(
    val url: String,
    val previewUrl: String,
    val provider: String,
    val subtitle: String? = null,
    val badge: String? = null
)

data class BackdropPickerState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<BackdropCandidate> = emptyList()
)

/**
 * What the new details page adds to [MetaDetailsViewModel]: the thumbs (stored per profile in [TitleRatingsStore],
 * read by Home's personal rows and Smart Highlights), reviews ([TitleReviewsRepository]), and custom backdrops
 * ([CustomBackdropStore]) from TMDB and other artwork providers.
 */
@HiltViewModel
class NewDetailsViewModel @Inject constructor(
    private val ratingsStore: TitleRatingsStore,
    private val reviewsRepository: TitleReviewsRepository,
    private val creditsRepository: TitleCreditsRepository,
    private val remindersStore: ReleaseRemindersStore,
    private val customBackdropStore: CustomBackdropStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val streamRepository: com.nuvio.tv.domain.repository.StreamRepository,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _preselectedStream = MutableStateFlow<PreselectedStreamInfo?>(null)
    val preselectedStream: StateFlow<PreselectedStreamInfo?> = _preselectedStream.asStateFlow()
    private var preselectedStreamKey: String? = null
    private var preselectedStreamJob: kotlinx.coroutines.Job? = null
    private val itemId: String? = savedStateHandle["itemId"]
    private val itemType: String? = savedStateHandle["itemType"]

    val ratings: StateFlow<TitleRatings> = ratingsStore.ratings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TitleRatings())

    val customBackdrops: StateFlow<Map<String, String>> = customBackdropStore.overrides

    private val _backdropPicker = MutableStateFlow(BackdropPickerState())
    val backdropPicker: StateFlow<BackdropPickerState> = _backdropPicker.asStateFlow()

    private val _titleReviews = MutableStateFlow(ReviewsState())
    val titleReviews: StateFlow<ReviewsState> = _titleReviews.asStateFlow()

    private val _episodeReviews = MutableStateFlow<Map<Pair<Int, Int>, ReviewsState>>(emptyMap())
    val episodeReviews: StateFlow<Map<Pair<Int, Int>, ReviewsState>> = _episodeReviews.asStateFlow()

    private var reviewsFor: String? = null
    private var backdropsFor: String? = null

    /** TMDB's full cast and crew, for the Cast & Crew panel; empty until loaded (or without a TMDB key). */
    private val _credits = MutableStateFlow(TitleCredits())
    val credits: StateFlow<TitleCredits> = _credits.asStateFlow()

    /** "Remind me" entries of the active profile, by [ReleaseReminder.key]. */
    val reminders: StateFlow<Map<String, ReleaseReminder>> = remindersStore.reminders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Starts loading the title's reviews and credits once per title. */
    fun bind(meta: Meta) {
        if (reviewsFor == meta.id) return
        reviewsFor = meta.id
        _titleReviews.value = ReviewsState(loading = true)
        viewModelScope.launch {
            val items = runCatching { reviewsRepository.titleReviews(meta, itemId, itemType) }.getOrDefault(emptyList())
            _titleReviews.value = ReviewsState(loading = false, loaded = true, items = items)
        }
        viewModelScope.launch {
            _credits.value = runCatching { creditsRepository.credits(meta) }.getOrDefault(TitleCredits())
        }
    }

    /** Loads available backdrops for [meta] from TMDB, Addon, Metahub, Cinemeta, Custom Provider, and Episode Stills. */
    fun loadBackdrops(meta: Meta, force: Boolean = false) {
        if (!force && backdropsFor == meta.id && (_backdropPicker.value.loading || _backdropPicker.value.loaded)) return
        backdropsFor = meta.id
        _backdropPicker.value = BackdropPickerState(loading = true, items = _backdropPicker.value.items)
        viewModelScope.launch(Dispatchers.IO) {
            val seenUrls = LinkedHashSet<String>()
            val candidates = ArrayList<BackdropCandidate>()

            fun addCandidate(url: String?, previewUrl: String? = null, provider: String, subtitle: String? = null, badge: String? = null) {
                val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
                if (!seenUrls.add(cleanUrl)) return
                candidates.add(
                    BackdropCandidate(
                        url = cleanUrl,
                        previewUrl = previewUrl?.trim()?.takeIf { it.isNotEmpty() } ?: cleanUrl,
                        provider = provider,
                        subtitle = subtitle,
                        badge = badge
                    )
                )
            }

            // 1. Addon's own default backdrop(s)
            addCandidate(
                url = meta.background,
                provider = "Addon",
                subtitle = "Default Backdrop",
                badge = "Default"
            )
            if (meta.landscapePoster != null && meta.landscapePoster != meta.background) {
                addCandidate(
                    url = meta.landscapePoster,
                    provider = "Addon",
                    subtitle = "Landscape Poster",
                    badge = "Wide"
                )
            }

            // 2. TMDB backdrops (clean textless + localized + English high-res options)
            val isSeries = meta.type == ContentType.SERIES || meta.type == ContentType.TV ||
                meta.apiType.equals("series", ignoreCase = true) || meta.apiType.equals("tv", ignoreCase = true)
            val tmdbType = if (isSeries) "tv" else "movie"
            val tmdbContentType = if (isSeries) ContentType.SERIES else ContentType.MOVIE
            val tmdbId = runCatching {
                tmdbService.ensureTmdbId(meta.id, tmdbType, fallbackImdbId = meta.imdbId)
                    ?: if (!itemId.isNullOrBlank()) tmdbService.ensureTmdbId(itemId, itemType ?: tmdbType, fallbackImdbId = meta.imdbId) else null
            }.getOrNull()

            if (!tmdbId.isNullOrBlank()) {
                val lang = runCatching { tmdbSettingsDataStore.settings.first().language }.getOrDefault("en")
                val tmdbOptions = runCatching {
                    tmdbMetadataService.fetchBackdropOptions(
                        tmdbId = tmdbId,
                        contentType = tmdbContentType,
                        language = lang
                    )
                }.getOrDefault(emptyList())

                tmdbOptions.forEach { opt ->
                    val subParts = listOfNotNull(
                        opt.languageLabel,
                        opt.voteAverage?.takeIf { it > 0.0 }?.let { String.format(java.util.Locale.US, "★ %.1f", it) }
                    )
                    addCandidate(
                        url = opt.url,
                        previewUrl = opt.previewUrl,
                        provider = "TMDB",
                        subtitle = subParts.joinToString(" · ").ifBlank { "TMDB Artwork" },
                        badge = opt.resolutionLabel
                    )
                }
            }

            // 3. Metahub / Cinemeta providers (when IMDb ID is available)
            val imdbId = listOfNotNull(meta.imdbId, meta.id, itemId)
                .firstOrNull { it.startsWith("tt") }
                ?.takeWhile { it != ':' && it != '/' }
            if (!imdbId.isNullOrBlank()) {
                addCandidate(
                    url = "https://images.metahub.space/background/large/$imdbId/img",
                    previewUrl = "https://images.metahub.space/background/medium/$imdbId/img",
                    provider = "Metahub",
                    subtitle = "Metahub HD Backdrop",
                    badge = "1080p"
                )
                addCandidate(
                    url = "https://live.metahub.space/background/large/$imdbId/img",
                    previewUrl = "https://live.metahub.space/background/medium/$imdbId/img",
                    provider = "Cinemeta",
                    subtitle = "Cinemeta Live Artwork",
                    badge = "HD"
                )
            }

            // 4. Custom Poster/Backdrop URL Pattern Provider (RPDB / Top-Posters / AIORatings landscape)
            runCatching {
                val pattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                if (pattern.isNotBlank()) {
                    val baseIds = CustomPosterUrlResolver.extractIds(meta.id, meta.imdbId)
                    val ids = if (baseIds.tmdbId == null && !tmdbId.isNullOrBlank()) {
                        baseIds.copy(tmdbId = tmdbId)
                    } else baseIds
                    val resolvedLandscape = CustomPosterUrlResolver.resolve(
                        pattern = pattern,
                        ids = ids,
                        type = if (isSeries) "series" else "movie",
                        shape = "landscape"
                    )
                    addCandidate(
                        url = resolvedLandscape,
                        provider = "Custom Provider",
                        subtitle = "Configured Layout Provider",
                        badge = "Landscape"
                    )
                }
            }

            // 5. Scene / Episode Stills (up to 12 distinct episode thumbnails for series/movies)
            meta.videos
                .filter { !it.thumbnail.isNullOrBlank() }
                .take(12)
                .forEach { video ->
                    val epLabel = if (video.season != null && video.episode != null) {
                        "S${video.season} E${video.episode}" + (video.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    } else {
                        video.title.ifBlank { "Scene Still" }
                    }
                    addCandidate(
                        url = video.thumbnail,
                        provider = "Scene Still",
                        subtitle = epLabel,
                        badge = "Still"
                    )
                }

            _backdropPicker.value = BackdropPickerState(
                loading = false,
                loaded = true,
                items = candidates
            )
        }
    }

    /** Saves a custom backdrop override (or clears it when [url] is null) across all matching IDs for this title. */
    fun setCustomBackdrop(meta: Meta, url: String?) {
        val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            val keys = buildSet {
                add(meta.id)
                itemId?.takeIf { it.isNotBlank() }?.let(::add)
                meta.imdbId?.takeIf { it.isNotBlank() }?.let(::add)
            }
            keys.forEach { key ->
                customBackdropStore.setBackdrop(key, cleanUrl)
            }
        }
    }

    fun resolveCustomBackdrop(meta: Meta): String? {
        return customBackdrops.value[meta.id]
            ?: (itemId?.let { customBackdrops.value[it] })
            ?: (meta.imdbId?.let { customBackdrops.value[it] })
    }

    fun toggleReminder(reminder: ReleaseReminder) {
        viewModelScope.launch { remindersStore.toggle(reminder) }
    }

    fun loadEpisodeReviews(meta: Meta, video: Video) {
        val key = (video.season ?: return) to (video.episode ?: return)
        val existing = _episodeReviews.value[key]
        if (existing != null && (existing.loading || existing.loaded)) return
        _episodeReviews.update { it + (key to ReviewsState(loading = true)) }
        viewModelScope.launch {
            val items = runCatching { reviewsRepository.episodeReviews(meta, itemId, itemType, video) }
                .getOrDefault(emptyList())
            _episodeReviews.update { it + (key to ReviewsState(loading = false, loaded = true, items = items)) }
        }
    }

    /** Sets the title's thumbs; choosing the current one again clears it. */
    fun toggleTitleRating(meta: Meta, rating: TitleRating) {
        val next = if (ratings.value.title(meta.id) == rating) null else rating
        viewModelScope.launch { ratingsStore.rateTitle(meta.id, meta.apiType, meta.name, next) }
    }

    fun toggleEpisodeRating(meta: Meta, video: Video, rating: TitleRating) {
        val season = video.season ?: return
        val episode = video.episode ?: return
        val current = ratings.value.episodesOf(meta.id)[season to episode]
        val next = if (current == rating) null else rating
        viewModelScope.launch { ratingsStore.rateEpisode(meta.id, meta.apiType, meta.name, season, episode, next) }
    }

    fun observePreselectedStream(meta: Meta, nextToWatch: com.nuvio.tv.domain.model.NextToWatch?) {
        val isSeries = meta.type == ContentType.SERIES || meta.videos.isNotEmpty()
        val targetVideo = if (isSeries) {
            nextToWatch?.let { ntw ->
                meta.videos.firstOrNull { it.id == ntw.nextVideoId || (it.season == ntw.nextSeason && it.episode == ntw.nextEpisode) }
            } ?: meta.videos.firstOrNull { (it.season ?: 0) > 0 } ?: meta.videos.firstOrNull()
        } else null

        val type = meta.apiType.ifBlank { itemType ?: if (isSeries) "series" else "movie" }
        val videoId = if (isSeries) (targetVideo?.id ?: meta.id) else (itemId?.takeIf { it.isNotBlank() } ?: meta.id)
        val season = if (isSeries) targetVideo?.season else null
        val episode = if (isSeries) targetVideo?.episode else null
        val key = "${type.lowercase()}|$videoId|${season ?: ""}|${episode ?: ""}"
        if (preselectedStreamKey == key) return
        preselectedStreamKey = key
        _preselectedStream.value = null
        preselectedStreamJob?.cancel()
        preselectedStreamJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                streamRepository.getStreamsFromAllAddons(
                    type = type,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    speculative = true
                ).collect { result ->
                    if (result is com.nuvio.tv.core.network.NetworkResult.Success) {
                        val groups = runCatching { streamBadgePresentation.apply(result.data) }.getOrDefault(result.data)
                        val firstStream = groups.asSequence().flatMap { it.streams.asSequence() }.firstOrNull()
                        if (firstStream != null) {
                            _preselectedStream.value = buildPreselectedStreamInfo(firstStream)
                        }
                    }
                }
            }
        }
    }

    private fun buildPreselectedStreamInfo(stream: com.nuvio.tv.domain.model.Stream): PreselectedStreamInfo {
        val chips = mutableListOf<String>()
        val text = buildString {
            append(stream.name.orEmpty()).append(' ')
            append(stream.title.orEmpty()).append(' ')
            append(stream.description.orEmpty()).append(' ')
            append(stream.behaviorHints?.filename.orEmpty())
        }.lowercase()

        val parsedQualityLabel = com.nuvio.tv.core.streams.StreamQualityParser.parse(stream)?.label
        if (parsedQualityLabel != null) {
            chips += parsedQualityLabel
        } else {
            when {
                text.contains("2160p") || text.contains("4k") || text.contains("uhd") -> chips += "4K"
                text.contains("1080p") -> chips += "1080p"
                text.contains("720p") -> chips += "720p"
            }
        }

        when {
            text.contains("dolby vision") || Regex("\\b(dv|dovi)\\b").containsMatchIn(text) -> chips += "DV"
            text.contains("hdr10+") || text.contains("hdr10plus") -> chips += "HDR10+"
            text.contains("hdr10") || Regex("\\bhdr\\b").containsMatchIn(text) -> chips += "HDR"
        }

        when {
            text.contains("remux") -> chips += "REMUX"
            text.contains("av1") -> chips += "AV1"
            text.contains("hevc") || text.contains("x265") || text.contains("h265") -> chips += "HEVC"
        }

        when {
            text.contains("atmos") -> chips += "Atmos"
            text.contains("truehd") -> chips += "TrueHD"
            text.contains("dts-hd") || text.contains("dtshd") -> chips += "DTS-HD"
            text.contains("7.1") -> chips += "7.1"
            text.contains("5.1") -> chips += "5.1"
        }

        val sizeBytes = stream.behaviorHints?.videoSize
        if (sizeBytes != null && sizeBytes > 0L) {
            val gib = sizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            if (gib >= 1.0) {
                chips += "${kotlin.math.round(gib * 10.0) / 10.0} GB"
            } else {
                val mib = (sizeBytes.toDouble() / (1024.0 * 1024.0)).toInt()
                if (mib > 0) chips += "$mib MB"
            }
        }

        return PreselectedStreamInfo(
            addonName = stream.addonName,
            specChips = chips.distinct().take(5),
            customBadges = stream.badges,
            fileSizeBytes = sizeBytes
        )
    }

}
