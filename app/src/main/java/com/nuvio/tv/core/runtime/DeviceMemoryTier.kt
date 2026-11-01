package com.nuvio.tv.core.runtime

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Detects low-RAM Android TV devices (specifically 1 GB RAM Fire TV Stick / Lite / Gen 2 class devices
 * and 32-bit constrained heaps) and exposes runtime memory-saving policies.
 *
 * Users can also override this behavior in Settings -> Advanced -> Performance via [overrideMode]:
 * - [MODE_AUTO]: Enabled automatically when device RAM <= 1.4 GB, `ActivityManager.isLowRamDevice` is true,
 *   or Java max heap <= 256 MB.
 * - [MODE_ON]: Always enforce Fire Stick 1 GB optimizations.
 * - [MODE_OFF]: Disable automatic 1 GB restrictions.
 */
object DeviceMemoryTier {
    const val MODE_AUTO = 0
    const val MODE_ON = 1
    const val MODE_OFF = 2

    private const val FIRE_STICK_1GB_RAM_CEILING_MB = 1400L
    private const val LOW_RAM_HEAP_CEILING_MB = 260L

    @Volatile
    var detectedLowRamDevice: Boolean = false
        private set

    @Volatile
    var totalDeviceRamMb: Long = 2048L
        private set

    @Volatile
    var maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        private set

    @Volatile
    var overrideMode: Int = MODE_AUTO

    fun init(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val ramMb = if (memInfo.totalMem > 0L) memInfo.totalMem / (1024L * 1024L) else 2048L
            val heapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            totalDeviceRamMb = ramMb
            maxHeapMb = heapMb
            detectedLowRamDevice = (am?.isLowRamDevice == true) ||
                ramMb <= FIRE_STICK_1GB_RAM_CEILING_MB ||
                heapMb <= LOW_RAM_HEAP_CEILING_MB
        }
    }

    /** True when Fire Stick 1 GB RAM optimizations should be active (either auto-detected or forced on). */
    val isFireStick1GbMode: Boolean
        get() = when (overrideMode) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> detectedLowRamDevice
        }

    /** Max backdrop width in pixels to decode into memory (720p on 1 GB sticks saves 55% RAM vs 1080p). */
    fun clampBackdropWidth(screenWidthPx: Int): Int =
        if (isFireStick1GbMode) screenWidthPx.coerceAtMost(1280) else screenWidthPx

    /** Max backdrop height in pixels to decode into memory. */
    fun clampBackdropHeight(screenHeightPx: Int): Int =
        if (isFireStick1GbMode) screenHeightPx.coerceAtMost(720) else screenHeightPx
}

/** CompositionLocal indicating whether 1 GB Fire Stick low-RAM UI optimizations are active. */
val LocalLowRamMode = staticCompositionLocalOf { DeviceMemoryTier.isFireStick1GbMode }
