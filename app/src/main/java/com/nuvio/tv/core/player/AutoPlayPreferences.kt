package com.nuvio.tv.core.player

import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.core.streams.StreamQualityParser
import com.nuvio.tv.domain.model.Stream

/**
 * Auto-play's preferred sources (Settings → Playback → Stream Selection), picked as pills. Pills in a group that
 * allows several (qualities, release types) add up: "4K" and "1080p" prefer either. Pills that can't both hold
 * ("Smallest" and "Largest", "HEVC" and "AVC", "HDR" and "SDR") replace each other.
 */
object AutoPlayPreferences {

    enum class Group(val oneOnly: Boolean) {
        QUALITY(false),
        SIZE(true),
        CODEC(true),
        PICTURE(true),
        RELEASE(false),
        AUDIO(false),
        CAM(false),
    }

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

    /** [selected] with [key] switched: on, it switches off any pill it can't hold together with. */
    fun toggle(selected: Set<String>, key: String): Set<String> {
        val pill = Pill.of(key) ?: return selected
        if (key in selected) return selected - key
        val rivals = if (pill.group.oneOnly) Pill.entries.filter { it.group == pill.group }.map { it.key }.toSet() else emptySet()
        return selected - rivals + key
    }

    fun isConfigured(selected: Set<String>): Boolean = selected.any { Pill.of(it) != null }

    /** Below this a source is low quality (480p, 360p...): auto-play takes one only when nothing better is there. */
    private const val LOW_QUALITY_BELOW = 720

    /** Whether [stream] says it's below 720p. A source that doesn't say isn't counted as low. */
    fun isLowQuality(stream: Stream): Boolean =
        StreamQualityParser.parse(stream)?.let { it.height < LOW_QUALITY_BELOW } == true

    /** Whether a remembered link, by its [name] and [filename], says it's below 720p. One that doesn't say isn't low. */
    fun isLowQuality(name: String?, filename: String?): Boolean =
        StreamQualityParser.cappedByName(StreamQualityParser.parse(filename, name), name)
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

    /**
     * Ranks [streams] so that:
     * 1. Cached Debrid / direct playable HTTP streams always come before uncached/P2P torrents.
     * 2. Exact 1080p (`StreamQuality.P1080`) streams always come first (distance 0), followed by nearest quality.
     * 3. Within the same quality bucket (e.g. all cached 1080p streams), streams are sorted strictly by
     *    LARGEST file size in bytes descending (`sizeOf(stream)`), so the largest 1080p cached file ALWAYS wins.
     * 4. Secondary preference score (Remux / BluRay / Codec / Audio) breaks any exact byte-size ties.
     */
    fun rank(streams: List<Stream>, selected: Set<String>): List<Stream> {
        if (streams.isEmpty()) return streams
        val effectiveSelected = (selected - Pill.SMALLEST.key) + setOf(Pill.Q1080.key, Pill.LARGEST.key)
        val pills = effectiveSelected.mapNotNull(Pill::of).toSet()
        val nonCamPool = streams.filterNot { CAM.containsMatchIn(text(it)) }
        val pool = nonCamPool.ifEmpty { streams }
        // Always target 1080p first so 1080p cached streams have distance 0
        val targetQualities = listOf(StreamQuality.P1080)

        return pool.withIndex()
            .sortedWith(
                // 1. Always prefer cached debrid HTTP links over uncached/P2P torrents
                compareByDescending<IndexedValue<Stream>> { isCachedDebrid(it.value) }
                    .thenBy { isUncachedOrDownload(it.value) }
                    // 2. Exact 1080p quality match first (distance 0 for 1080p)
                    .thenBy { distance(StreamQualityParser.parse(it.value), targetQualities) }
                    .thenBy { isLowQuality(it.value) }
                    // 3. Strictly largest file size in bytes descending so a larger 1080p cached file
                    //    ALWAYS beats a smaller 1080p file!
                    .thenByDescending { entry -> sizeOf(entry.value) ?: -1L }
                    // 4. Secondary preference score (Remux / BluRay / Codec / HDR / Audio)
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
        if (REMUX.containsMatchIn(text)) score += 50
        if (BLURAY.containsMatchIn(text)) score += 25
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

    /**
     * How far [quality] is from the nearest of [picked], in steps of [StreamQuality] (0 when nothing is picked). A step
     * down costs more than a step up, so with 1080p picked 4K comes before 720p; a source that doesn't state its
     * quality comes after every one that does.
     */
    private fun distance(quality: StreamQuality?, picked: List<StreamQuality>): Int {
        if (picked.isEmpty()) return 0
        if (quality == null) return Int.MAX_VALUE
        return picked.minOf { target ->
            val up = target.ordinal - quality.ordinal
            if (up >= 0) up else -up * 2 + 1
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
     * 1. Structured byte fields (`behaviorHints.videoSize`, `clientResolve.stream.raw.size`, `debridCacheStatus.cachedSize`).
     * 2. If both season-pack size (📦) and episode disk size (💾) appear in text and no structured episode size exists,
     *    prefer the 💾 file size.
     * 3. All size tokens parsed from `name`, `title`, `description`, `filename`, and `badges`.
     */
    fun sizeOf(stream: Stream): Long? {
        val structuredSizes = listOfNotNull(
            stream.behaviorHints?.videoSize?.takeIf { it > 0L },
            stream.clientResolve?.stream?.raw?.size?.takeIf { it > 0L },
            stream.debridCacheStatus?.cachedSize?.takeIf { it > 0L }
        )
        val rawText = text(stream)

        // If structured size is present on the stream, use the largest structured file size unless
        // the text explicitly shows an episode-specific 💾 size that is smaller than a season-pack size.
        val episodeDiskSizes = EPISODE_DISK_SIZE.findAll(rawText).mapNotNull { m ->
            parseSizeMatch(m.groupValues[1], m.groupValues[2])
        }.toList()
        val hasPackMarker = rawText.contains("📦")
        if (hasPackMarker && episodeDiskSizes.isNotEmpty()) {
            return episodeDiskSizes.max()
        }

        val textSizes = SIZE.findAll(rawText).mapNotNull { m ->
            parseSizeMatch(m.groupValues[1], m.groupValues[2])
        }.toList()

        val bestStructured = structuredSizes.maxOrNull()
        val bestText = (episodeDiskSizes + textSizes).maxOrNull()
        return when {
            bestStructured != null && bestText != null -> maxOf(bestStructured, bestText)
            bestStructured != null -> bestStructured
            else -> bestText
        }
    }

    private fun parseSizeMatch(numStr: String, unitStr: String): Long? {
        // Handle thousands separator commas like "1,450 MB" vs decimal commas like "14,2 GB"
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
    private val HEVC = Regex("(?<![a-z0-9])(hevc|x265|h\\.?265)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val AVC = Regex("(?<![a-z0-9])(avc|x264|h\\.?264)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HDR = Regex("(?<![a-z0-9])(hdr|hdr10|hdr10\\+|hdr10plus|dv|dovi|dolby ?vision)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val REMUX = Regex("(?<![a-z0-9])remux(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val BLURAY = Regex("(?<![a-z0-9])(blu-?ray|bdrip|brrip|bd)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val WEB = Regex("(?<![a-z0-9])(web|web-?dl|web-?rip|webrip)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val ATMOS_DTS = Regex("(?<![a-z0-9])(atmos|dts|dts-?hd|dts-?x|truehd)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    // Match file sizes (e.g. "14.2 GB", "14.2GiB", "850 MB") but NOT transfer speeds like "25 MB/s" or "25 Mbps"
    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB)(?![a-z/])", RegexOption.IGNORE_CASE)
    private val EPISODE_DISK_SIZE = Regex("💾\\s*(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB)(?![a-z/])", RegexOption.IGNORE_CASE)
    // Note: Do NOT include ⬇️ here because addons like MediaFusion/Comet/AIOStreams use ⬇️ for file size or seeders!
    private val UNCACHED_MARKER = Regex("(\\[[a-z]{2,4}\\s*download\\]|\\buncached\\b|⏳|❌|magnet:)", RegexOption.IGNORE_CASE)
    private val CACHED_MARKER = Regex("(\\[[a-z]{2,4}\\+\\]|⚡|\\binstant\\b|\\bcached\\b)", RegexOption.IGNORE_CASE)
}
