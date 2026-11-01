package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Per-profile store for user-selected custom backdrop URLs (keyed by content ID).
 * Keeps an in-memory mirror in [Companion] so synchronous callers on Home and Details
 * can resolve overrides immediately without suspending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CustomBackdropStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val overridesFlow: Flow<Map<String, String>> = profileManager.activeProfileId
        .flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map(::readOverrides)
        }
        .onEach { map ->
            memoryMirror.clear()
            memoryMirror.putAll(map)
        }

    val overrides: StateFlow<Map<String, String>> = overridesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun setBackdrop(contentId: String, backdropUrl: String?) {
        val cleanId = contentId.trim()
        if (cleanId.isEmpty()) return
        val cleanUrl = backdropUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUrl == null) {
            memoryMirror.remove(cleanId)
        } else {
            memoryMirror[cleanId] = cleanUrl
        }
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val key = stringPreferencesKey(KEY_PREFIX + cleanId)
            if (cleanUrl == null) {
                prefs.remove(key)
            } else {
                prefs[key] = cleanUrl
            }
        }
    }

    private fun readOverrides(prefs: Preferences): Map<String, String> {
        val result = HashMap<String, String>()
        for ((key, value) in prefs.asMap()) {
            val rawUrl = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val name = key.name
            if (name.startsWith(KEY_PREFIX)) {
                val id = name.removePrefix(KEY_PREFIX).trim()
                if (id.isNotEmpty()) {
                    result[id] = rawUrl
                }
            }
        }
        return result
    }

    companion object {
        private const val FEATURE = "custom_backdrops"
        private const val KEY_PREFIX = "backdrop:"
        private val memoryMirror = ConcurrentHashMap<String, String>()

        /** Returns the user's custom backdrop for [contentId] if one is set, or [defaultBackdrop]. */
        fun resolve(contentId: String?, defaultBackdrop: String?): String? {
            if (!contentId.isNullOrBlank()) {
                memoryMirror[contentId.trim()]?.let { return it }
            }
            return defaultBackdrop
        }

        fun getOverride(contentId: String?): String? {
            if (contentId.isNullOrBlank()) return null
            return memoryMirror[contentId.trim()]
        }
    }
}
