package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListRatingsClient
import com.nuvio.tv.data.mdblist.MdbListRatingsDiskCache
import com.nuvio.tv.data.mdblist.MdbListRatingsLoader
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListRatingsResult
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private data class MediaRef(val provider: String, val id: String, val mediaType: String)

@Singleton
class MDBListRepository internal constructor(
    private val api: MdbListRatingsClient,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService,
    private val ratingsLoader: MdbListRatingsLoader
) {
    @Inject constructor(
        api: MdbListRatingsClient,
        settingsDataStore: MDBListSettingsDataStore,
        tmdbService: TmdbService,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ) : this(api, settingsDataStore, tmdbService, MdbListRatingsLoader(api, MdbListRatingsDiskCache(context)))

    private val userRatingsCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile
    private var userRatingsLoadedAtMs: Long = 0L

    fun isAvailable(settings: MDBListSettings): Boolean = settings.enabled && api.credential(settings.apiKey) != null

    private fun ratingCacheKeys(meta: Meta): List<String> {
        val kind = if (meta.type == com.nuvio.tv.domain.model.ContentType.SERIES ||
            meta.type == com.nuvio.tv.domain.model.ContentType.TV ||
            meta.apiType.equals("series", ignoreCase = true) ||
            meta.apiType.equals("tv", ignoreCase = true)
        ) "show" else "movie"
        val keys = mutableListOf<String>()
        val imdb = Regex("(tt\\d{5,12})").find("${meta.id} ${meta.imdbId.orEmpty()}")?.groupValues?.get(1)
        if (!imdb.isNullOrBlank()) keys.add("$kind:imdb:$imdb")
        val tmdb = meta.id.substringAfter("tmdb:", "").substringBefore(':').toIntOrNull()
        if (tmdb != null && tmdb > 0) keys.add("$kind:tmdb:$tmdb")
        return keys
    }

    private suspend fun buildSyncRatingPayload(meta: Meta, rating: Int?): String? {
        val isShow = meta.type == com.nuvio.tv.domain.model.ContentType.SERIES ||
            meta.type == com.nuvio.tv.domain.model.ContentType.TV ||
            meta.apiType.equals("series", ignoreCase = true) ||
            meta.apiType.equals("tv", ignoreCase = true)
        val groupKey = if (isShow) "shows" else "movies"
        val imdb = Regex("(tt\\d{5,12})").find("${meta.id} ${meta.imdbId.orEmpty()}")?.groupValues?.get(1)
        val tmdb = meta.id.substringAfter("tmdb:", "").substringBefore(':').toIntOrNull()
            ?: runCatching { tmdbService.ensureTmdbId(meta.id, meta.apiType.ifBlank { if (isShow) "series" else "movie" })?.toIntOrNull() }.getOrNull()
        if (imdb == null && tmdb == null) return null

        val ids = org.json.JSONObject().apply {
            if (!imdb.isNullOrBlank()) put("imdb", imdb)
            if (tmdb != null && tmdb > 0) put("tmdb", tmdb)
        }
        val entry = org.json.JSONObject().apply {
            put("ids", ids)
            if (rating != null) put("rating", rating.coerceIn(1, 10))
        }
        val arr = org.json.JSONArray().put(entry)
        return org.json.JSONObject().put(groupKey, arr).toString()
    }

    private fun parseAndCacheSyncRatings(rawJson: String?) {
        if (rawJson.isNullOrBlank()) return
        runCatching {
            val root = org.json.JSONObject(rawJson)
            fun ingest(arrayName: String, kind: String, itemObjName: String) {
                val arr = root.optJSONArray(arrayName) ?: return
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val score = obj.optInt("rating", 0)
                    if (score !in 1..10) continue
                    val target = obj.optJSONObject(itemObjName) ?: obj
                    val ids = target.optJSONObject("ids") ?: obj.optJSONObject("ids")
                    val imdb = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }
                        ?: target.optString("imdb_id").takeIf { it.startsWith("tt") }
                    val tmdb = ids?.optInt("tmdb", 0)?.takeIf { it > 0 }
                        ?: target.optInt("tmdb_id", 0).takeIf { it > 0 }
                    if (imdb != null) userRatingsCache["$kind:imdb:$imdb"] = score
                    if (tmdb != null) userRatingsCache["$kind:tmdb:$tmdb"] = score
                }
            }
            ingest("movies", "movie", "movie")
            ingest("shows", "show", "show")
            userRatingsLoadedAtMs = System.currentTimeMillis()
        }
    }

    suspend fun getUserRatingForMeta(meta: Meta, forceRefresh: Boolean = false): Int? {
        val settings = settingsDataStore.settings.first()
        val credential = api.credential(settings.apiKey) ?: return null
        val keys = ratingCacheKeys(meta)
        val cacheFresh = (System.currentTimeMillis() - userRatingsLoadedAtMs) < 5L * 60_000L
        if (!forceRefresh && cacheFresh) {
            keys.firstNotNullOfOrNull { userRatingsCache[it] }?.let { return it }
        }
        if (forceRefresh || !cacheFresh) {
            runCatching {
                val raw = api.getSyncRatingsJson(credential)
                parseAndCacheSyncRatings(raw)
            }
        }
        return keys.firstNotNullOfOrNull { userRatingsCache[it] }
    }

    suspend fun setUserRatingForMeta(meta: Meta, rating: Int): Boolean {
        val settings = settingsDataStore.settings.first()
        val credential = api.credential(settings.apiKey) ?: return false
        val clamped = rating.coerceIn(1, 10)
        val payload = buildSyncRatingPayload(meta, clamped) ?: return false
        val ok = runCatching { api.postSyncRatingsJson(payload, credential) }.getOrDefault(false)
        if (ok) {
            ratingCacheKeys(meta).forEach { userRatingsCache[it] = clamped }
        }
        return ok
    }

    suspend fun removeUserRatingForMeta(meta: Meta): Boolean {
        val settings = settingsDataStore.settings.first()
        val credential = api.credential(settings.apiKey) ?: return false
        val payload = buildSyncRatingPayload(meta, null) ?: return false
        val ok = runCatching { api.removeSyncRatingsJson(payload, credential) }.getOrDefault(false)
        if (ok) {
            ratingCacheKeys(meta).forEach { userRatingsCache.remove(it) }
        }
        return ok
    }

    suspend fun getImdbRatingForItem(itemId: String, itemType: String): Double? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null
        val credential = api.credential(settings.apiKey) ?: return null

        val mediaType = normalizeMediaType(itemType)
        val ref = resolveMediaRef(
            meta = Meta(
                id = itemId,
                type = when (normalizeMediaType(itemType)) {
                    "show" -> com.nuvio.tv.domain.model.ContentType.SERIES
                    else -> com.nuvio.tv.domain.model.ContentType.MOVIE
                },
                name = itemId,
                poster = null,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            ),
            fallbackItemId = itemId,
            fallbackItemType = itemType,
            mediaType = mediaType
        ) ?: return null

        return ratingsLoader.getRatings(ref.provider, ref.mediaType, ref.id, credential)?.imdb
    }

    suspend fun getLetterboxdRatingForItem(itemId: String, itemType: String): Double? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null
        val credential = api.credential(settings.apiKey) ?: return null

        val mediaType = normalizeMediaType(itemType)
        val ref = resolveMediaRef(
            meta = Meta(
                id = itemId,
                type = when (mediaType) {
                    "show" -> com.nuvio.tv.domain.model.ContentType.SERIES
                    else -> com.nuvio.tv.domain.model.ContentType.MOVIE
                },
                name = itemId,
                poster = null,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            ),
            fallbackItemId = itemId,
            fallbackItemType = itemType,
            mediaType = mediaType
        ) ?: return null

        return ratingsLoader.getRatings(ref.provider, ref.mediaType, ref.id, credential)?.letterboxd
    }

    suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String
    ): MDBListRatingsResult? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null

        val credential = api.credential(settings.apiKey) ?: return null

        if (!settings.hasEnabledProviders()) return null

        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val ref = resolveMediaRef(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        val ratings = ratingsLoader.getRatings(ref.provider, ref.mediaType, ref.id, credential)?.let { allRatings ->
            MDBListRatings(
                trakt = allRatings.trakt.takeIf { settings.showTrakt },
                imdb = allRatings.imdb.takeIf { settings.showImdb },
                tmdb = allRatings.tmdb.takeIf { settings.showTmdb },
                letterboxd = allRatings.letterboxd.takeIf { settings.showLetterboxd },
                tomatoes = allRatings.tomatoes.takeIf { settings.showTomatoes },
                audience = allRatings.audience.takeIf { settings.showAudience },
                metacritic = allRatings.metacritic.takeIf { settings.showMetacritic },
                mal = allRatings.mal.takeIf { settings.showMal },
                tomatoesCertified = settings.showTomatoes && allRatings.tomatoesCertified,
                audienceCertified = settings.showAudience && allRatings.audienceCertified
            )
        }?.takeUnless { it.isEmpty() } ?: return null

        return MDBListRatingsResult(ratings, hasImdbRating = ratings.imdb != null)
    }

    private fun MDBListSettings.hasEnabledProviders(): Boolean =
        showTrakt || showImdb || showTmdb || showLetterboxd || showTomatoes || showAudience || showMetacritic || showMal

    private suspend fun resolveMediaRef(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): MediaRef? {
        // 1. Try IMDB ID first (most reliable).
        extractImdbId(meta.id)?.let { return MediaRef("imdb", it, mediaType) }
        extractImdbId(fallbackItemId)?.let { return MediaRef("imdb", it, mediaType) }
        extractImdbId(meta.imdbId)?.let { return MediaRef("imdb", it, mediaType) }

        // 2. Try TMDB ID directly — no need to convert to IMDB.
        val tmdbId = extractPrefixedId(meta.id, "tmdb")
            ?: extractPrefixedId(fallbackItemId, "tmdb")

        if (tmdbId != null) {
            return MediaRef("tmdb", tmdbId, mediaType)
        }

        // 3. Try MAL ID — use media_type "any" since MDBList doesn't distinguish
        //    movie/show for MAL anime entries.
        val malId = extractPrefixedId(meta.id, "mal")
            ?: extractPrefixedId(fallbackItemId, "mal")
        if (malId != null) return MediaRef("mal", malId, "any")

        // 4. Try TVDB ID.
        val tvdbId = extractPrefixedId(meta.id, "tvdb")
            ?: extractPrefixedId(fallbackItemId, "tvdb")
        if (tvdbId != null) return MediaRef("tvdb", tvdbId, mediaType)

        return null
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
    }

    private fun extractPrefixedId(rawId: String?, prefix: String): String? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        if (trimmed.startsWith("$prefix:", ignoreCase = true)) {
            return trimmed.substringAfter(':').substringBefore(':').takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun normalizeMediaType(rawType: String): String {
        return when (rawType.lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }
    }
}
