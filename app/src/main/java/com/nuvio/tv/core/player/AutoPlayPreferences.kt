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

    /**
     * [streams] best first for [selected]: CAM and TS releases left out with "No CAM/TS"; then those matching the most
     * picked groups (quality counts most); then low-quality sources last; then, when no picked quality matched, the
     * qualities nearest a picked one (see [distance]); then, with a size pill, the
     * smallest (or largest) first, sizes not stated last; otherwise in their own order.
     */
    fun isUncachedOrDownload(stream: Stream): Boolean {
        val state = stream.debridCacheStatus?.state
        if (state == com.nuvio.tv.domain.model.StreamDebridCacheState.NOT_CACHED ||
            state == com.nuvio.tv.domain.model.StreamDebridCacheState.CHECKING
        ) return true
        val combined = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        if (UNCACHED_MARKER.containsMatchIn(combined)) return true
        if (stream.isTorrent() && stream.getStreamUrl() == null && !stream.isDirectDebrid()) return true
        return false
    }

    fun isCachedDebrid(stream: Stream): Boolean {
        if (isUncachedOrDownload(stream)) return false
        if (stream.debridCacheStatus?.state == com.nuvio.tv.domain.model.StreamDebridCacheState.CACHED) return true
        if (stream.isDirectDebrid()) return true
        val combined = listOfNotNull(stream.name, stream.title).joinToString(" ")
        if (CACHED_MARKER.containsMatchIn(combined)) return true
        return stream.getStreamUrl() != null && !stream.isTorrent()
    }

    fun rank(streams: List<Stream>, selected: Set<String>): List<Stream> {
        val effectiveSelected = if (selected.isEmpty()) setOf(Pill.Q1080.key, Pill.LARGEST.key) else selected
        val pills = effectiveSelected.mapNotNull(Pill::of).toSet()
        if (pills.isEmpty()) return streams
        val pool = if (Pill.NO_CAM in pills) streams.filterNot { CAM.containsMatchIn(text(it)) } else streams
        val size = pills.firstOrNull { it.group == Group.SIZE } ?: Pill.LARGEST
        val picked = pills.mapNotNull { it.quality() }.ifEmpty { listOf(StreamQuality.P1080) }
        return pool.withIndex()
            .sortedWith(
                // 1. Always prefer cached debrid HTTP links over uncached/P2P torrents
                compareByDescending<IndexedValue<Stream>> { isCachedDebrid(it.value) }
                    .thenBy { isUncachedOrDownload(it.value) }
                    // 2. Exact target quality match first (e.g. 1080p = distance 0)
                    .thenBy { distance(StreamQualityParser.parse(it.value), picked) }
                    .thenBy { isLowQuality(it.value) }
                    // 3. When Largest is active, sort strictly by largest file size in bytes descending
                    //    so a larger 1080p cached file ALWAYS beats a smaller 1080p file!
                    .thenBy { entry ->
                        val bytes = sizeOf(entry.value)
                        when {
                            size == Pill.SMALLEST -> bytes ?: Long.MAX_VALUE
                            else -> Long.MAX_VALUE - (bytes ?: 0L)
                        }
                    }
                    // 4. Secondary preference score (Remux / Codec / HDR / Audio)
                    .thenByDescending { score(it.value, pills) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    /** How well [stream] fits: each picked group it matches counts, quality most of all. */
    private fun score(stream: Stream, pills: Set<Pill>): Int {
        var score = 0
        val text by lazy { text(stream) }
        val qualities = pills.filter { it.group == Group.QUALITY }
        if (qualities.isNotEmpty()) {
            val quality = StreamQualityParser.parse(stream)
            if (qualities.any { it.quality() == quality }) score += 1000
        }
        pills.firstOrNull { it.group == Group.CODEC }?.let { pill ->
            if ((if (pill == Pill.HEVC) HEVC else AVC).containsMatchIn(text)) score += 10
        }
        pills.firstOrNull { it.group == Group.PICTURE }?.let { pill ->
            if (HDR.containsMatchIn(text) == (pill == Pill.HDR)) score += 10
        }
        val releases = pills.filter { it.group == Group.RELEASE }
        if (releases.any { release ->
                when (release) {
                    Pill.REMUX -> REMUX.containsMatchIn(text)
                    Pill.BLURAY -> BLURAY.containsMatchIn(text)
                    else -> WEB.containsMatchIn(text)
                }
            }
        ) score += 10
        if (Pill.ATMOS_DTS in pills && ATMOS_DTS.containsMatchIn(text)) score += 5
        return score
    }

    /** Whether [stream] matches every picked group but size (for preparing debrid sources ahead). */
    fun matches(stream: Stream, selected: Set<String>): Boolean {
        val pills = selected.mapNotNull(Pill::of).toSet()
        if (Pill.NO_CAM in pills && CAM.containsMatchIn(text(stream))) return false
        val groups = pills.map { it.group }.filter { it != Group.SIZE && it != Group.CAM }.toSet()
        if (groups.isEmpty()) return true
        val expected = (if (Group.QUALITY in groups) 1000 else 0) +
            listOf(Group.CODEC, Group.PICTURE, Group.RELEASE).count { it in groups } * 10 +
            (if (Group.AUDIO in groups) 5 else 0)
        return score(stream, pills) >= expected
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

    /** The file size the addon states, or one written in its text ("2.4 GB"). */
    fun sizeOf(stream: Stream): Long? {
        val rawText = text(stream)
        // If both pack size (📦) and episode/file size (💾) are present, prefer the 💾 file size
        EPISODE_DISK_SIZE.find(rawText)?.let { m ->
            parseSizeMatch(m.groupValues[1], m.groupValues[2])?.let { return it }
        }
        val hintSize = stream.behaviorHints?.videoSize?.takeIf { it > 0L }
        val textSizes = SIZE.findAll(rawText).mapNotNull { m ->
            parseSizeMatch(m.groupValues[1], m.groupValues[2])
        }.toList()
        if (hintSize != null && textSizes.isEmpty()) return hintSize
        if (hintSize != null && textSizes.isNotEmpty()) {
            return maxOf(hintSize, textSizes.max())
        }
        return textSizes.maxOrNull()
    }

    private fun parseSizeMatch(numStr: String, unitStr: String): Long? {
        val value = numStr.replace(',', '.').toDoubleOrNull() ?: return null
        val unit = unitStr.lowercase()
        val factor = when {
            unit.startsWith("t") -> 1_000_000_000_000.0
            unit.startsWith("g") -> 1_000_000_000.0
            else -> 1_000_000.0
        }
        return (value * factor).toLong()
    }

    private fun text(stream: Stream): String = listOfNotNull(
        stream.name, stream.title, stream.description, stream.behaviorHints?.filename,
    ).joinToString(" ")

    private val CAM = Regex("(?<![a-z0-9])(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HEVC = Regex("(?<![a-z0-9])(hevc|x265|h\\.?265)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val AVC = Regex("(?<![a-z0-9])(avc|x264|h\\.?264)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HDR = Regex("(?<![a-z0-9])(hdr|hdr10|hdr10\\+|hdr10plus|dv|dovi|dolby ?vision)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val REMUX = Regex("(?<![a-z0-9])remux(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val BLURAY = Regex("(?<![a-z0-9])(blu-?ray|bdrip|brrip|bd)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val WEB = Regex("(?<![a-z0-9])(web|web-?dl|web-?rip|webrip)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val ATMOS_DTS = Regex("(?<![a-z0-9])(atmos|dts|dts-?hd|dts-?x|truehd)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB)(?![a-z])", RegexOption.IGNORE_CASE)
    private val EPISODE_DISK_SIZE = Regex("💾\\s*(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB)(?![a-z])", RegexOption.IGNORE_CASE)
    private val UNCACHED_MARKER = Regex("(\\[[a-z]{2,4}\\s*download\\]|\\buncached\\b|⏳|⬇️|magnet:)", RegexOption.IGNORE_CASE)
    private val CACHED_MARKER = Regex("(\\[[a-z]{2,4}\\+\\]|⚡|\\binstant\\b)", RegexOption.IGNORE_CASE)
}
