package com.nuvio.tv.core.player

import com.nuvio.tv.core.config.AppFeaturePolicy
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreams>,
        installedAddonOrder: List<String>
    ): List<AddonStreams> {
        if (groups.isEmpty()) return emptyList()

        val indexMap = installedAddonOrder
            .mapIndexed { index, name -> name to index }
            .toMap()

        return groups.withIndex()
            .sortedWith(
                compareBy<IndexedValue<AddonStreams>> {
                    indexMap[it.value.addonName] ?: Int.MAX_VALUE
                }.thenBy { it.index }
            )
            .map { it.value }
    }

    private fun isPlayable(stream: Stream): Boolean {
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED -> return false
            StreamDebridCacheState.CACHED,
            StreamDebridCacheState.UNKNOWN,
            null -> Unit
        }

        if (stream.isDirectDebrid()) {
            return true
        }

        return stream.getStreamUrl() != null ||
            stream.isTorrent() ||
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
        val bySource = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY ->
                streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY ->
                streams.filter { it.addonName !in installedAddonNames }
        }
        return bySource.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                when (source) {
                    StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> false
                    else -> selectedAddons.isEmpty() || stream.addonName in selectedAddons
                }
            } else {
                when (source) {
                    StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> false
                    else -> selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
                }
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
        selectedPlugins: Set<String> = emptySet(),
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = true,
        bingeGroupOnly: Boolean = false,
        preferences: Set<String> = emptySet()
    ): Stream? {
        if (streams.isEmpty()) return null

        val candidateStreams = scopedCandidates(streams, source, installedAddonNames, selectedAddons, selectedPlugins)
            .ifEmpty { streams }
        if (candidateStreams.isEmpty()) return null

        val playableCandidates = candidateStreams.filter { isPlayable(it) }
        if (playableCandidates.isEmpty()) return null

        if (bingeGroupOnly) return null

        if (mode == StreamAutoPlayMode.MANUAL) return null

        val effectivePrefs = (preferences - AutoPlayPreferences.Pill.SMALLEST.key) +
            setOf(AutoPlayPreferences.Pill.Q1080.key, AutoPlayPreferences.Pill.LARGEST.key)

        return AutoPlayPreferences.rank(playableCandidates, effectivePrefs).firstOrNull()
    }
}
