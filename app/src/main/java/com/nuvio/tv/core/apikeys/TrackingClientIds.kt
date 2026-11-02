package com.nuvio.tv.core.apikeys

import android.content.Context
import com.nuvio.tv.BuildConfig

/**
 * Resolves OAuth Client IDs for Trakt, Simkl, and MDBList from:
 * 1) User-configured runtime overrides (stored in SharedPreferences),
 * 2) Compiled BuildConfig values (populated automatically during CI from official Nuvio releases or local.properties).
 */
object TrackingClientIds {
    private const val PREFS_NAME = "nuvio_tracking_client_ids"
    private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
    private const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
    private const val KEY_SIMKL_CLIENT_ID = "simkl_client_id"
    private const val KEY_MDBLIST_CLIENT_ID = "mdblist_client_id"

    @Volatile
    private var cachedTraktClientId: String = BuildConfig.TRAKT_CLIENT_ID.trim()

    @Volatile
    private var cachedTraktClientSecret: String = BuildConfig.TRAKT_CLIENT_SECRET.trim()

    @Volatile
    private var cachedSimklClientId: String = BuildConfig.SIMKL_CLIENT_ID.trim()

    @Volatile
    private var cachedMdbListClientId: String = BuildConfig.MDBLIST_CLIENT_ID.trim()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_TRAKT_CLIENT_ID, null)?.trim()?.takeIf { it.isNotBlank() }?.let {
            cachedTraktClientId = it
        }
        prefs.getString(KEY_TRAKT_CLIENT_SECRET, null)?.trim()?.takeIf { it.isNotBlank() }?.let {
            cachedTraktClientSecret = it
        }
        prefs.getString(KEY_SIMKL_CLIENT_ID, null)?.trim()?.takeIf { it.isNotBlank() }?.let {
            cachedSimklClientId = it
        }
        prefs.getString(KEY_MDBLIST_CLIENT_ID, null)?.trim()?.takeIf { it.isNotBlank() }?.let {
            cachedMdbListClientId = it
        }
    }

    val traktClientId: String
        get() = cachedTraktClientId.ifBlank { BuildConfig.TRAKT_CLIENT_ID.trim() }

    val traktClientSecret: String
        get() = cachedTraktClientSecret.ifBlank { BuildConfig.TRAKT_CLIENT_SECRET.trim() }

    val simklClientId: String
        get() = cachedSimklClientId.ifBlank { BuildConfig.SIMKL_CLIENT_ID.trim() }

    val mdbListClientId: String
        get() = cachedMdbListClientId.ifBlank { BuildConfig.MDBLIST_CLIENT_ID.trim() }

    fun setTraktClientId(context: Context, clientId: String, clientSecret: String = "") {
        val trimmedId = clientId.trim()
        val trimmedSecret = clientSecret.trim()
        cachedTraktClientId = trimmedId
        if (trimmedSecret.isNotBlank()) cachedTraktClientSecret = trimmedSecret
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRAKT_CLIENT_ID, trimmedId)
            .apply {
                if (trimmedSecret.isNotBlank()) putString(KEY_TRAKT_CLIENT_SECRET, trimmedSecret)
            }
            .apply()
    }

    fun setSimklClientId(context: Context, clientId: String) {
        val trimmedId = clientId.trim()
        cachedSimklClientId = trimmedId
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SIMKL_CLIENT_ID, trimmedId)
            .apply()
    }

    fun setMdbListClientId(context: Context, clientId: String) {
        val trimmedId = clientId.trim()
        cachedMdbListClientId = trimmedId
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MDBLIST_CLIENT_ID, trimmedId)
            .apply()
    }
}
