package com.nuvio.tv.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PopupGlassMode(val id: String, val label: String) {
    DARK_FROSTED("dark_frosted", "Dark Frosted Glass"),
    WHITE_FROSTED("white_frosted", "White Frosted Glass"),
    TRANSPARENT("transparent", "Transparent Glass");

    fun next(): PopupGlassMode = when (this) {
        DARK_FROSTED -> WHITE_FROSTED
        WHITE_FROSTED -> TRANSPARENT
        TRANSPARENT -> DARK_FROSTED
    }

    companion object {
        fun fromId(raw: String?): PopupGlassMode =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: DARK_FROSTED
    }
}

data class PopupGlassTokens(
    val mode: PopupGlassMode,
    val fill: Brush,
    val border: Brush,
    val primaryText: Color,
    val secondaryText: Color,
    val divider: Color,
    val itemUnfocusedBg: Color,
    val captionBg: Color,
    val badgeBg: Color
)

object PopupGlassStyleStore {
    private const val PREFS_NAME = "nuvio_popup_glass_style"
    private const val KEY_MODE = "popup_glass_mode"

    @Volatile
    private var initialized = false
    private val _mode = MutableStateFlow(PopupGlassMode.DARK_FROSTED)
    val mode: StateFlow<PopupGlassMode> = _mode.asStateFlow()

    fun ensureLoaded(context: Context): PopupGlassMode {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    _mode.value = PopupGlassMode.fromId(prefs.getString(KEY_MODE, null))
                    initialized = true
                }
            }
        }
        return _mode.value
    }

    fun setMode(context: Context, mode: PopupGlassMode) {
        initialized = true
        _mode.value = mode
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.id)
            .apply()
    }
}

@Composable
fun rememberPopupGlassTokens(accent: Color): PopupGlassTokens {
    val context = LocalContext.current
    remember(context) { PopupGlassStyleStore.ensureLoaded(context) }
    val mode by PopupGlassStyleStore.mode.collectAsState()
    return remember(mode, accent) {
        when (mode) {
            PopupGlassMode.DARK_FROSTED -> PopupGlassTokens(
                mode = mode,
                fill = Brush.verticalGradient(
                    listOf(Color(0xE2161B26), Color(0xEC0B0E15), Color(0xF0080B10))
                ),
                border = Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.88f), Color.White.copy(alpha = 0.30f), accent.copy(alpha = 0.55f))
                ),
                primaryText = Color.White,
                secondaryText = Color(0xFFB4BCC9),
                divider = Color.White.copy(alpha = 0.18f),
                itemUnfocusedBg = Color.White.copy(alpha = 0.08f),
                captionBg = Color(0xCC0B0E15),
                badgeBg = Color.White.copy(alpha = 0.12f)
            )

            PopupGlassMode.WHITE_FROSTED -> PopupGlassTokens(
                mode = mode,
                fill = Brush.verticalGradient(
                    listOf(Color(0xE6F4F7FB), Color(0xDCE4ECF5), Color(0xE2D7E2EE))
                ),
                border = Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.92f), Color.White.copy(alpha = 0.95f), accent.copy(alpha = 0.65f))
                ),
                primaryText = Color(0xFF0E131B),
                secondaryText = Color(0xFF3E4A5C),
                divider = Color(0xFF0E131B).copy(alpha = 0.16f),
                itemUnfocusedBg = Color(0xFF0E131B).copy(alpha = 0.07f),
                captionBg = Color(0xDCEAF0F7),
                badgeBg = Color(0xFF0E131B).copy(alpha = 0.10f)
            )

            PopupGlassMode.TRANSPARENT -> PopupGlassTokens(
                mode = mode,
                fill = Brush.verticalGradient(
                    listOf(Color(0x4A141A26), Color(0x5A0A0E16), Color(0x6606090F))
                ),
                border = Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.92f), Color.White.copy(alpha = 0.55f), accent.copy(alpha = 0.65f))
                ),
                primaryText = Color.White,
                secondaryText = Color.White.copy(alpha = 0.78f),
                divider = Color.White.copy(alpha = 0.26f),
                itemUnfocusedBg = Color.White.copy(alpha = 0.10f),
                captionBg = Color(0x66080B12),
                badgeBg = Color.White.copy(alpha = 0.16f)
            )
        }
    }
}
