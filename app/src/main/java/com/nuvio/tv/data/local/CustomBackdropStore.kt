package com.nuvio.tv.data.local

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Per-profile store for user-selected custom backdrop / Continue Watching image URLs (keyed by content ID).
 * Keeps an in-memory mirror and reactive [StateFlow] in [Companion] so callers on Home and Details
 * resolve and recompose overrides immediately.
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
            memoryMirror.putAll(map)
            _reactiveMirror.value = HashMap(memoryMirror)
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
        _reactiveMirror.value = HashMap(memoryMirror)
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
        private const val CW_PREFS_NAME = "nuvio_cw_poster_overrides"
        private val memoryMirror = ConcurrentHashMap<String, String>()
        private val _reactiveMirror = MutableStateFlow<Map<String, String>>(emptyMap())
        val reactiveOverrides: StateFlow<Map<String, String>> = _reactiveMirror.asStateFlow()

        fun initFromPrefs(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(CW_PREFS_NAME, Context.MODE_PRIVATE)
            for ((k, v) in prefs.all) {
                val url = (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                memoryMirror[k] = url
            }
            _reactiveMirror.value = HashMap(memoryMirror)
        }

        fun setDirectOverride(context: Context, contentId: String, url: String?) {
            val cleanId = contentId.trim()
            if (cleanId.isEmpty()) return
            val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() }
            val prefs = context.applicationContext.getSharedPreferences(CW_PREFS_NAME, Context.MODE_PRIVATE)
            if (cleanUrl == null) {
                memoryMirror.remove(cleanId)
                prefs.edit().remove(cleanId).apply()
            } else {
                memoryMirror[cleanId] = cleanUrl
                prefs.edit().putString(cleanId, cleanUrl).apply()
            }
            _reactiveMirror.value = HashMap(memoryMirror)
        }

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
