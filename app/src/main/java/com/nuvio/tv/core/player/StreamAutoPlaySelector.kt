package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = streams.partition {
            it.streams.any { stream -> stream.isDirectDebrid() }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries
        val (addonEntries, pluginEntries) = remainingEntries.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN -> return false
            StreamDebridCacheState.CACHED,
            null -> Unit
        }
        return stream.getStreamUrl() != null ||
            stream.isTorrent() ||
            stream.isDirectDebrid() ||
            // A YouTube id is resolved to a playable URL on the device when the stream is played.
            // Builds without in-app YouTube playback open the watch page instead, an external link.
            (AppFeaturePolicy.inAppTrailerPlaybackEnabled && stream.youTubeIdToResolve() != null)
    }

    /** Streams auto-play may pick from: the configured source scope and addon/plugin selection. */
    private fun scopedCandidates(
        streams: List<Stream>,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>
    ): List<Stream> {
        val effectiveSource = if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        } else {
            source
        }

        val sourceScopedStreams = when (effectiveSource) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        return sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
    }

    /**
     * Beta source fallback: auto-play is on but every addon it is limited to came back empty
     * (failed, timed out or had nothing for this title). Rather than dropping to the manual list,
     * play the best ranked playable stream from the remaining addons.
     */
    fun fallbackWhenPreferredSourcesEmpty(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>
    ): Stream? {
        if (mode == StreamAutoPlayMode.MANUAL || streams.isEmpty()) return null
        if (scopedCandidates(streams, source, installedAddonNames, selectedAddons, selectedPlugins).isNotEmpty()) {
            return null
        }
        val playable = streams.filter { isPlayable(it) && !it.isTorrent() }
        return AutoPlayPreferences.rank(playable, setOf(AutoPlayPreferences.Pill.Q1080.key, AutoPlayPreferences.Pill.LARGEST.key)).firstOrNull()
    }

    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        /** Preferred-sources pills (AutoPlayPreferences); when any is picked they're used instead of [regexPattern]. */
        preferences: Set<String> = emptySet()
    ): Stream? {
        if (streams.isEmpty()) return null

        val candidateStreams = scopedCandidates(streams, source, installedAddonNames, selectedAddons, selectedPlugins)
            .ifEmpty { streams }
        if (candidateStreams.isEmpty()) return null

        val playableCandidates = candidateStreams.filter { isPlayable(it) }
        if (playableCandidates.isEmpty()) return null

        // Do not allow early bingeGroupOnly checks to lock in a smaller stream before all addons finish.
        if (bingeGroupOnly) return null

        if (mode == StreamAutoPlayMode.MANUAL) return null

        val effectivePrefs = (preferences - AutoPlayPreferences.Pill.SMALLEST.key) +
            setOf(AutoPlayPreferences.Pill.Q1080.key, AutoPlayPreferences.Pill.LARGEST.key)

        // Always rank all playable candidates by cached debrid -> 1080p -> largest byte size descending!
        return AutoPlayPreferences.rank(playableCandidates, effectivePrefs).firstOrNull()
    }
}
