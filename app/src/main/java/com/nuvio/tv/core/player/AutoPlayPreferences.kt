package com.nuvio.tv.core.player

import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.core.streams.StreamQualityParser
import com.nuvio.tv.domain.model.Stream

/**
 * Auto-play's preferred sources, picked as tap-to-toggle pills instead of writing a regex: the quality, size, video
 * codec, picture, release type, audio, and whether to leave out CAM and TS copies. Stored in [PlayerSettings] as the
 * set of [Pill.key] strings.
 */
object AutoPlayPreferences {

    enum class Group { QUALITY, SIZE, CODEC, PICTURE, RELEASE, AUDIO, CAM }

    enum class Pill(val key: String, val group: Group) {
        Q4K("4k", Group.QUALITY),
        Q1080("1080p", Group.QUALITY),
        Q720("720p", Group.QUALITY),
        SMALLEST("smallest", Group.SIZE),
        LARGEST("largest", Group.SIZE),
        HEVC("hevc", Group.CODEC),
        AVC("avc", Group.CODEC),
        HDR("hdr", Group.PICTURE),
        SDR("sdr", Group.PICTURE),
        REMUX("remux", Group.RELEASE),
        BLURAY("bluray", Group.RELEASE),
        WEB("web", Group.RELEASE),
        ATMOS_DTS("atmos", Group.AUDIO),
        NO_CAM("nocam", Group.CAM);

        companion object {
            fun of(key: String): Pill? = entries.firstOrNull { it.key == key }
        }
    }

    /** Quality below which a source counts as low-quality and is only picked when nothing better is there. */
    private const val LOW_QUALITY_BELOW = 720

    /**
     * Toggles [key] in [selected]. Size, codec and picture are one-of-two choices (picking the other replaces the
     * first); quality and release allow several at once.
     */
    fun toggle(selected: Set<String>, key: String): Set<String> {
        val pill = Pill.of(key) ?: return selected
        if (key in selected) return selected - key
        val exclusive = pill.group in setOf(Group.SIZE, Group.CODEC, Group.PICTURE)
        val cleared = if (exclusive) selected.filterNot { Pill.of(it)?.group == pill.group }.toSet() else selected
        return cleared + key
    }

    fun isConfigured(selected: Set<String>): Boolean = selected.any { Pill.of(it) != null }

    /**
     * True when [stream] states a resolution below 720p (480p, 360p, 240p, 144p, SD). Sources that don't state a
     * resolution are not marked low-quality, so addons that don't label their streams still play as before.
     */
    fun isLowQuality(stream: Stream): Boolean =
        StreamQualityParser.parse(stream)?.let { it.height < LOW_QUALITY_BELOW } == true

    /** Same check for a remembered link, from the name and filename kept with it. */
    fun isLowQuality(streamName: String?, filename: String?): Boolean =
        StreamQualityParser.cappedByName(StreamQualityParser.parse(filename, streamName), streamName)
            ?.let { it.height < LOW_QUALITY_BELOW } == true

    /** [streams] without the low-quality ones, unless that leaves nothing. */
    fun withoutLowQuality(streams: List<Stream>): List<Stream> = streams.filterNot(::isLowQuality).ifEmpty { streams }

    fun isUncachedOrDownload(stream: Stream): Boolean {
        val state = stream.debridCacheStatus?.state
        if (state == com.nuvio.tv.domain.model.StreamDebridCacheState.NOT_CACHED ||
            state == com.nuvio.tv.domain.model.StreamDebridCacheState.CHECKING
        ) return true
        val combined = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.badges.joinToString(" ") { it.name }
        ).joinToString(" ")
        if (UNCACHED_MARKER.containsMatchIn(combined)) return true
        if (stream.isTorrent() && stream.getStreamUrl() == null && !stream.isDirectDebrid()) return true
        return false
    }

    fun isCachedDebrid(stream: Stream): Boolean {
        if (isUncachedOrDownload(stream)) return false
        if (stream.debridCacheStatus?.state == com.nuvio.tv.domain.model.StreamDebridCacheState.CACHED) return true
        if (stream.isDirectDebrid()) return true
        val combined = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.badges.joinToString(" ") { it.name }
        ).joinToString(" ")
        if (CACHED_MARKER.containsMatchIn(combined)) return true
        return stream.getStreamUrl() != null && !stream.isTorrent()
    }

    private fun isLikelyVc1(stream: Stream): Boolean =
        VC1.containsMatchIn(text(stream))

    /**
     * Ranks [streams] so that:
     * 1. Cached Debrid / direct playable HTTP streams always come before uncached/P2P torrents.
     * 2. Exact 1080p (`StreamQuality.P1080`) streams always come first (distance 0), followed by nearest quality.
     * 3. Standard hardware-seekable codecs (HEVC / AVC) come before legacy VC-1 streams.
     * 4. Within the same quality bucket (e.g. all cached 1080p streams), streams are sorted strictly by
     *    LARGEST file size in bytes descending (`sizeOf(stream)`), so the largest 1080p cached file ALWAYS wins.
     * 5. Secondary preference score breaks any exact byte-size ties.
     */
    fun rank(streams: List<Stream>, selected: Set<String>): List<Stream> {
        if (streams.isEmpty()) return streams
        val effectiveSelected = (selected - Pill.SMALLEST.key) + setOf(Pill.Q1080.key, Pill.LARGEST.key)
        val pills = effectiveSelected.mapNotNull(Pill::of).toSet()
        val nonCamPool = streams.filterNot { CAM.containsMatchIn(text(it)) }
        val pool = nonCamPool.ifEmpty { streams }
        val targetQualities = listOf(StreamQuality.P1080)

        return pool.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Stream>> { isCachedDebrid(it.value) }
                    .thenBy { isUncachedOrDownload(it.value) }
                    .thenBy { distance(StreamQualityParser.parse(it.value), targetQualities) }
                    .thenBy { isLowQuality(it.value) }
                    .thenBy { isLikelyVc1(it.value) }
                    .thenByDescending { entry -> sizeOf(entry.value) ?: -1L }
                    .thenByDescending { score(it.value, pills) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    /** How well [stream] fits: each picked group it matches counts, quality most of all. */
    private fun score(stream: Stream, pills: Set<Pill>): Int {
        var score = 0
        val text by lazy { text(stream) }
        val quality = StreamQualityParser.parse(stream)
        if (quality == StreamQuality.P1080) score += 1000
        if (BLURAY.containsMatchIn(text)) score += 25
        if (REMUX.containsMatchIn(text)) score += 15
        pills.firstOrNull { it.group == Group.CODEC }?.let { pill ->
            if ((if (pill == Pill.HEVC) HEVC else AVC).containsMatchIn(text)) score += 10
        }
        pills.firstOrNull { it.group == Group.PICTURE }?.let { pill ->
            if (HDR.containsMatchIn(text) == (pill == Pill.HDR)) score += 10
        }
        if (ATMOS_DTS.containsMatchIn(text)) score += 5
        return score
    }

    /** Whether [stream] matches every picked group but size (for preparing debrid sources ahead). */
    fun matches(stream: Stream, selected: Set<String>): Boolean {
        if (CAM.containsMatchIn(text(stream))) return false
        return StreamQualityParser.parse(stream) == StreamQuality.P1080
    }

    private fun distance(quality: StreamQuality?, picked: List<StreamQuality>): Int {
        if (picked.isEmpty()) return 0
        if (quality == null) return 50
        return picked.minOf { target ->
            val steps = quality.ordinal - target.ordinal
            if (steps >= 0) steps * 2 else -steps * 2 - 1
        }
    }

    private fun Pill.quality(): StreamQuality? = when (this) {
        Pill.Q4K -> StreamQuality.P2160
        Pill.Q1080 -> StreamQuality.P1080
        Pill.Q720 -> StreamQuality.P720
        else -> null
    }

    /**
     * Canonical file size resolution in bytes:
     * 1. Structured byte fields (`behaviorHints.videoSize`, `clientResolve.stream.raw.size`, `debridCacheStatus.cachedSize`)
     *    always represent the exact single video file size and take priority over pack sizes in description text.
     * 2. If `💾` episode disk size is present in text, prefer that over `📦` pack size.
     * 3. Otherwise parse size tokens from `name`, `title`, `description`, `filename`, and `badges`.
     */
    fun sizeOf(stream: Stream): Long? {
        stream.behaviorHints?.videoSize?.takeIf { it > 0L }?.let { return it }
        stream.clientResolve?.stream?.raw?.size?.takeIf { it > 0L }?.let { return it }
        stream.debridCacheStatus?.cachedSize?.takeIf { it > 0L }?.let { return it }

        val rawText = text(stream)
        val episodeDiskSizes = EPISODE_DISK_SIZE.findAll(rawText).mapNotNull { m ->
            parseSizeMatch(m.groupValues[1], m.groupValues[2])
        }.toList()
        if (episodeDiskSizes.isNotEmpty()) {
            return episodeDiskSizes.max()
        }

        val textSizes = SIZE.findAll(rawText).mapNotNull { m ->
            parseSizeMatch(m.groupValues[1], m.groupValues[2])
        }.toList()
        if (textSizes.isEmpty()) return null
        if (textSizes.size > 1 && PACK_MARKER.containsMatchIn(rawText)) {
            return textSizes.min()
        }
        return textSizes.max()
    }

    private fun parseSizeMatch(numStr: String, unitStr: String): Long? {
        val normalizedNum = if (THOUSANDS_COMMA.matches(numStr)) {
            numStr.replace(",", "")
        } else {
            numStr.replace(',', '.')
        }
        val value = normalizedNum.toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        val unit = unitStr.lowercase()
        val factor = when {
            unit.startsWith("t") -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            unit.startsWith("g") -> 1024.0 * 1024.0 * 1024.0
            unit.startsWith("m") -> 1024.0 * 1024.0
            unit.startsWith("k") -> 1024.0
            else -> 1024.0 * 1024.0
        }
        return (value * factor).toLong().takeIf { it > 0L }
    }

    private fun text(stream: Stream): String = listOfNotNull(
        stream.name,
        stream.title,
        stream.description,
        stream.behaviorHints?.filename,
        stream.clientResolve?.filename,
        stream.clientResolve?.torrentName,
        stream.clientResolve?.stream?.raw?.filename,
        stream.clientResolve?.stream?.raw?.torrentName,
        stream.debridCacheStatus?.cachedName,
        stream.badges.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.name }
    ).joinToString(" ")

    private val THOUSANDS_COMMA = Regex("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$")
    private val CAM = Regex("(?<![a-z0-9])(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val VC1 = Regex("(?<![a-z0-9])vc[- .]?1(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HEVC = Regex("(?<![a-z0-9])(hevc|x265|h\\.?265)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val AVC = Regex("(?<![a-z0-9])(avc|x264|h\\.?264)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HDR = Regex("(?<![a-z0-9])(hdr|hdr10|hdr10\\+|hdr10plus|dv|dovi|dolby ?vision)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val REMUX = Regex("(?<![a-z0-9])remux(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val BLURAY = Regex("(?<![a-z0-9])(blu-?ray|bdrip|brrip|bd)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val WEB = Regex("(?<![a-z0-9])(web|web-?dl|web-?rip|webrip)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val ATMOS_DTS = Regex("(?<![a-z0-9])(atmos|dts|dts-?hd|dts-?x|truehd)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val PACK_MARKER = Regex("(📦|📁|\\bpack\\b|\\bcomplete\\b)", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB)(?![a-z/])", RegexOption.IGNORE_CASE)
    private val EPISODE_DISK_SIZE = Regex("💾\\s*(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB)(?![a-z/])", RegexOption.IGNORE_CASE)
    private val UNCACHED_MARKER = Regex("(\\[[a-z]{2,4}\\s*download\\]|\\buncached\\b|⏳|❌|magnet:)", RegexOption.IGNORE_CASE)
    private val CACHED_MARKER = Regex("(\\[[a-z]{2,4}\\+\\]|⚡|\\binstant\\b|\\bcached\\b)", RegexOption.IGNORE_CASE)
}
