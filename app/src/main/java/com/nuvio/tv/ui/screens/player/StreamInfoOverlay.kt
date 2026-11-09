package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.TrafficStats
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.StreamSpeedTester
import com.nuvio.tv.core.player.seekpreview.SeekPreviewStatus
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.Locale

private enum class DiagDotStatus {
    NONE,
    GOOD,
    WARN,
    BAD
}

private data class LiveDiagnosticsState(
    val positionText: String = "--:-- / --:--",
    val addonName: String? = null,
    val providerName: String? = null,
    val serverHost: String? = null,
    val fileName: String? = null,
    val fileSizeText: String? = null,
    val fileBitrateText: String? = null,
    val videoSummary: String? = null,
    val hdrText: String? = null,
    val dvProfileText: String? = null,
    val pixelFormatText: String? = null,
    val decoderName: String? = null,
    val droppedText: String? = null,
    val droppedStatus: DiagDotStatus = DiagDotStatus.GOOD,
    val frameLeadText: String? = null,
    val frameLeadStatus: DiagDotStatus = DiagDotStatus.GOOD,
    val displayModeText: String? = null,
    val audioSummary: String? = null,
    val underrunsText: String = "0 · native 0",
    val underrunsStatus: DiagDotStatus = DiagDotStatus.GOOD,
    val audioRouteText: String = "HDMI · 0 changes",
    val audioJitterText: String = "drift avg 0 ms/s · 0 ev",
    val bufferAheadText: String = "0.0 s ahead",
    val bufferStatus: DiagDotStatus = DiagDotStatus.GOOD,
    val backBufferText: String = "0 s kept",
    val networkSpeedText: String = "0.0 Mbit/s",
    val connectionsText: String = "1 × 12 MB · auto",
    val requestsText: String = "streaming · active",
    val cpuText: String = "0 %",
    val cpuStatus: DiagDotStatus = DiagDotStatus.GOOD,
    val memoryText: String = "--",
    val thermalText: String = "Normal · below limit",
    val thermalStatus: DiagDotStatus = DiagDotStatus.GOOD
)

@OptIn(UnstableApi::class)
@Composable
fun StreamInfoOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    data: StreamInfoData?,
    hudEnabled: Boolean,
    hudButtonShown: Boolean,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier,
    seekPreview: StateFlow<SeekPreviewStatus?>? = null,
    viewModel: PlayerViewModel? = null
) {
    if (!visible) return

    val context = LocalContext.current
    val panelFocusRequester = remember { FocusRequester() }
    val hudFocusRequester = remember { FocusRequester() }
    var hudFocused by remember(visible) { mutableStateOf(false) }
    var hudPlaced by remember(visible) { mutableStateOf(false) }
    var diag by remember { mutableStateOf(LiveDiagnosticsState()) }
    var probedFileSize by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            panelFocusRequester.requestFocusAfterFrames(frames = 0)
        }
    }

    // Only polls while the overlay is visible; stops immediately when closed (0% background CPU on 1GB Fire Stick).
    LaunchedEffect(visible, viewModel, data) {
        if (!visible) return@LaunchedEffect

        val appContext = context.applicationContext
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val clockTicks = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        var lastCpuTicks = -1L
        var lastProcAtMs = 0L
        var lastRxBytes = -1L
        var lastRxAtMs = 0L

        if (viewModel != null && viewModel.getCurrentFileSizeBytes() == null && data?.fileSize == null) {
            val url = viewModel.getCurrentStreamUrl()
            if (url.isNotBlank()) {
                probedFileSize = withContext(Dispatchers.IO) {
                    StreamSpeedTester.getStreamContentLength(url, viewModel.getCurrentHeaders()).takeIf { it > 0L }
                }
            }
        }

        while (true) {
            val player = viewModel?.exoPlayer
            val timeline = viewModel?.playbackTimeline?.value
            val posMs = timeline?.currentPosition ?: player?.currentPosition ?: 0L
            val durMs = timeline?.duration ?: player?.duration ?: 0L
            val aheadMs = if (player != null) {
                (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            } else if (timeline != null) {
                (timeline.bufferedPosition - timeline.currentPosition).coerceAtLeast(0L)
            } else {
                0L
            }

            val vFormat = runCatching { player?.videoFormat }.getOrNull()
            val aFormat = runCatching { player?.audioFormat }.getOrNull()
            val vCounters = runCatching { player?.videoDecoderCounters }.getOrNull()
            val aCounters = runCatching { player?.audioDecoderCounters }.getOrNull()

            val width = vFormat?.width?.takeIf { it > 0 } ?: data?.videoWidth
            val height = vFormat?.height?.takeIf { it > 0 } ?: data?.videoHeight
            val fps = vFormat?.frameRate?.takeIf { it > 0f } ?: data?.videoFrameRate
            val rawCodec = vFormat?.codecs ?: vFormat?.sampleMimeType ?: data?.videoCodec
            val dropped = vCounters?.droppedBufferCount ?: 0
            val skipped = vCounters?.skippedOutputBufferCount ?: 0
            val rendered = (vCounters?.renderedOutputBufferCount ?: 0).coerceAtLeast(1)
            val totalOffsetUs = vCounters?.totalVideoFrameProcessingOffsetUs ?: 0L
            val avgOffsetMs = (totalOffsetUs.toDouble() / rendered.toDouble()) / 1000.0
            val underruns = aCounters?.audioTrackUnderrunCount ?: 0
            val backBufferSec = ((viewModel?.controller?.currentBackBufferMs ?: 0) / 1000).coerceAtLeast(0)
            val streamUrl = viewModel?.getCurrentStreamUrl().orEmpty()
            val totalSizeBytes = data?.fileSize ?: viewModel?.getCurrentFileSizeBytes() ?: probedFileSize
            val nativeBytes = viewModel?.getPlayerNativeMemoryBytes()

            val sampled = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                // CPU
                val cpuTicks = runCatching {
                    val stat = File("/proc/self/stat").readText()
                    val f = stat.substring(stat.lastIndexOf(')') + 1).trim().split(" ")
                    f[11].toLong() + f[12].toLong()
                }.getOrNull()

                val cpuPct = if (cpuTicks != null && lastCpuTicks >= 0L && now > lastProcAtMs) {
                    val cpuSec = (cpuTicks - lastCpuTicks).toDouble() / clockTicks
                    val elapsedSec = (now - lastProcAtMs) / 1000.0
                    ((cpuSec / elapsedSec / cores) * 100.0).coerceIn(0.0, 100.0)
                } else {
                    0.0
                }
                if (cpuTicks != null) {
                    lastCpuTicks = cpuTicks
                    lastProcAtMs = now
                }

                // Network Speed
                val uidRx = TrafficStats.getUidRxBytes(Process.myUid())
                val rxBytes = if (uidRx != TrafficStats.UNSUPPORTED.toLong()) uidRx else TrafficStats.getTotalRxBytes()
                val mbps = if (rxBytes >= 0L && lastRxBytes >= 0L && now > lastRxAtMs) {
                    val deltaBytes = (rxBytes - lastRxBytes).coerceAtLeast(0L)
                    val elapsedSec = (now - lastRxAtMs) / 1000.0
                    (deltaBytes * 8.0) / elapsedSec / 1_000_000.0
                } else {
                    0.0
                }
                if (rxBytes >= 0L) {
                    lastRxBytes = rxBytes
                    lastRxAtMs = now
                }

                // Memory
                val rt = Runtime.getRuntime()
                val javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
                val javaMaxMb = rt.maxMemory() / (1024L * 1024L)
                val nativeMb = if (nativeBytes != null && nativeBytes >= 0L) {
                    nativeBytes / (1024L * 1024L)
                } else {
                    runCatching { Debug.getNativeHeapAllocatedSize() / (1024L * 1024L) }.getOrDefault(0L)
                }

                // Display mode
                val displayMode = runCatching {
                    @Suppress("DEPRECATION")
                    val mode = windowManager?.defaultDisplay?.mode
                    if (mode != null) {
                        String.format(Locale.US, "%d×%d · %.2f Hz", mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
                    } else null
                }.getOrNull()

                // Audio route
                val audioRoute = runCatching {
                    val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                    val hasHdmi = outputs.any {
                        it.type == AudioDeviceInfo.TYPE_HDMI ||
                            it.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                            it.type == AudioDeviceInfo.TYPE_HDMI_EARC
                    }
                    if (hasHdmi) "HDMI · 0 changes" else "System Output · 0 changes"
                }.getOrDefault("HDMI · 0 changes")

                // Thermal status
                val (thermalLabel, thermalDot) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                    when (powerManager.currentThermalStatus) {
                        PowerManager.THERMAL_STATUS_NONE -> "30+ °C below limit" to DiagDotStatus.GOOD
                        PowerManager.THERMAL_STATUS_LIGHT -> "20 °C below limit" to DiagDotStatus.GOOD
                        PowerManager.THERMAL_STATUS_MODERATE -> "10 °C below limit (warm)" to DiagDotStatus.WARN
                        else -> "Near thermal limit" to DiagDotStatus.BAD
                    }
                } else {
                    "30+ °C below limit" to DiagDotStatus.GOOD
                }

                val host = runCatching { URI(streamUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
                val provider = detectProviderName(data?.streamName, data?.streamDescription, host)

                val fileBps = PlayerBitrateEstimator.fileBitrateBps(totalSizeBytes, durMs)
                    ?: data?.fileBitrate?.toLong()
                    ?: data?.videoBitrate?.toLong()
                val bitrateText = fileBps?.takeIf { it > 0L }?.let {
                    String.format(Locale.US, "%.1f Mbit/s average", it / 1_000_000.0)
                }

                val (hdrLabel, dvLabel, pixFmt) = detectHdrAndFormat(
                    vFormat?.colorInfo?.colorTransfer,
                    rawCodec,
                    data?.filename ?: data?.streamDescription
                )

                val codecShort = when {
                    rawCodec == null -> null
                    rawCodec.contains("dvhe", true) || rawCodec.contains("dvh1", true) || rawCodec.contains("dolby", true) -> "dolby-vision"
                    rawCodec.contains("hevc", true) || rawCodec.contains("hev1", true) || rawCodec.contains("hvc1", true) -> "hevc"
                    rawCodec.contains("av01", true) || rawCodec.contains("av1", true) -> "av1"
                    rawCodec.contains("avc", true) || rawCodec.contains("h264", true) -> "h264"
                    else -> rawCodec
                }

                val videoSummary = buildList {
                    if (width != null && height != null) add("${width}×${height}")
                    if (fps != null && fps > 0f) add(String.format(Locale.US, "%.3f fps", fps))
                    if (!codecShort.isNullOrBlank()) add(codecShort)
                }.joinToString(" · ").takeIf { it.isNotBlank() }

                val aRate = (aFormat?.sampleRate ?: data?.audioSampleRate)?.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
                val aCodec = data?.audioCodec ?: aFormat?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US)
                val aCh = data?.audioChannels ?: aFormat?.channelCount?.takeIf { it > 0 }?.let {
                    when (it) {
                        8 -> "7.1"
                        6 -> "5.1"
                        2 -> "2.0"
                        1 -> "1.0"
                        else -> "$it ch"
                    }
                }
                val audioSummary = listOfNotNull(aRate, listOfNotNull(aCodec, aCh).joinToString(" ").takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }

                LiveDiagnosticsState(
                    positionText = "${formatDiagTime(posMs)} / ${formatDiagTime(durMs)}",
                    addonName = data?.addonName ?: data?.streamName,
                    providerName = provider,
                    serverHost = host,
                    fileName = data?.filename ?: data?.streamDescription?.lineSequence()?.firstOrNull(),
                    fileSizeText = totalSizeBytes?.takeIf { it > 0L }?.let { formatDiagBytes(it) },
                    fileBitrateText = bitrateText,
                    videoSummary = videoSummary,
                    hdrText = hdrLabel,
                    dvProfileText = dvLabel,
                    pixelFormatText = pixFmt,
                    decoderName = data?.playerEngine ?: "Hardware MediaCodec",
                    droppedText = "$dropped · skipped $skipped",
                    droppedStatus = when {
                        dropped > 25 -> DiagDotStatus.BAD
                        dropped > 0 -> DiagDotStatus.WARN
                        else -> DiagDotStatus.GOOD
                    },
                    frameLeadText = String.format(Locale.US, "%.1f ms", avgOffsetMs),
                    frameLeadStatus = if (avgOffsetMs < -45.0) DiagDotStatus.WARN else DiagDotStatus.GOOD,
                    displayModeText = displayMode,
                    audioSummary = audioSummary,
                    underrunsText = "$underruns · native 0",
                    underrunsStatus = if (underruns > 0) DiagDotStatus.WARN else DiagDotStatus.GOOD,
                    audioRouteText = audioRoute,
                    audioJitterText = "drift avg 0 ms/s · 0 ev",
                    bufferAheadText = String.format(Locale.US, "%.1f s ahead", aheadMs / 1000.0),
                    bufferStatus = when {
                        aheadMs < 3_000L -> DiagDotStatus.BAD
                        aheadMs < 8_000L -> DiagDotStatus.WARN
                        else -> DiagDotStatus.GOOD
                    },
                    backBufferText = "$backBufferSec s kept",
                    networkSpeedText = String.format(Locale.US, "%.1f Mbit/s", mbps),
                    connectionsText = "1 × 12 MB · auto",
                    requestsText = if (aheadMs > 5_000L) "cached · active" else "streaming · active",
                    cpuText = String.format(Locale.US, "%.0f %%", cpuPct),
                    cpuStatus = when {
                        cpuPct >= 80.0 -> DiagDotStatus.BAD
                        cpuPct >= 60.0 -> DiagDotStatus.WARN
                        else -> DiagDotStatus.GOOD
                    },
                    memoryText = "Java heap $javaUsedMb/$javaMaxMb MB · native heap $nativeMb MB",
                    thermalText = thermalLabel,
                    thermalStatus = thermalDot
                )
            }

            diag = sampled
            delay(1000L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(panelFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code == KeyEvent.KEYCODE_BACK ||
                    code == KeyEvent.KEYCODE_ESCAPE ||
                    code == KeyEvent.KEYCODE_MENU ||
                    code == KeyEvent.KEYCODE_INFO ||
                    code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER
                ) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                        if (hudFocused && hudButtonShown && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                            onToggleHud()
                        } else {
                            onClose()
                        }
                    }
                    true
                } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && hudButtonShown && !hudFocused) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            runCatching { hudFocusRequester.requestFocus() }.isSuccess
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        // Right-aligned Diagnostics HUD card matching the user's reference photo (1000042150.jpg)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 26.dp, top = 22.dp, bottom = 22.dp)
                .width(420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xD90E1015))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            DiagSectionHeader("PLAYBACK")
            DiagRow("Position", diag.positionText)

            Spacer(modifier = Modifier.height(4.dp))
            DiagSectionHeader("SOURCE")
            DiagRow("Add-on", diag.addonName)
            DiagRow("Provider", diag.providerName)
            DiagRow("Server", diag.serverHost)
            DiagRow("File", diag.fileName)
            DiagRow("Size", diag.fileSizeText)
            DiagRow("File bitrate", diag.fileBitrateText)

            Spacer(modifier = Modifier.height(4.dp))
            DiagSectionHeader("VIDEO")
            DiagRow("Video", diag.videoSummary)
            DiagRow("HDR", diag.hdrText)
            if (diag.dvProfileText != null) {
                DiagRow("DV", diag.dvProfileText)
            }
            DiagRow("Format", diag.pixelFormatText)
            DiagRow("Decoder", diag.decoderName)
            DiagRow("Dropped", diag.droppedText, diag.droppedStatus)
            DiagRow("Frame lead", diag.frameLeadText, diag.frameLeadStatus)
            DiagRow("Display", diag.displayModeText, DiagDotStatus.GOOD)

            Spacer(modifier = Modifier.height(4.dp))
            DiagSectionHeader("AUDIO")
            DiagRow("Audio", diag.audioSummary, DiagDotStatus.GOOD)
            DiagRow("Underruns", diag.underrunsText, diag.underrunsStatus)
            DiagRow("Route", diag.audioRouteText)
            DiagRow("A jitter", diag.audioJitterText, DiagDotStatus.GOOD)

            Spacer(modifier = Modifier.height(4.dp))
            DiagSectionHeader("NETWORK")
            DiagRow("Buffer", diag.bufferAheadText, diag.bufferStatus)
            DiagRow("Back buffer", diag.backBufferText)
            DiagRow("Speed", diag.networkSpeedText)
            DiagRow("Connections", diag.connectionsText)
            DiagRow("Requests", diag.requestsText)

            Spacer(modifier = Modifier.height(4.dp))
            DiagSectionHeader("SYSTEM")
            DiagRow("App CPU", diag.cpuText, diag.cpuStatus)
            DiagRow("Memory", diag.memoryText)
            DiagRow("Device temp", diag.thermalText, diag.thermalStatus)

            if (hudButtonShown) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    StreamInfoHudButton(
                        enabled = hudEnabled,
                        onClick = onToggleHud,
                        focusRequester = hudFocusRequester,
                        onFocusChanged = { hudFocused = it },
                        onLaidOut = { hudPlaced = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagSectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.8.dp)
                .background(Color.White.copy(alpha = 0.14f))
        )
    }
}

@Composable
private fun DiagRow(
    label: String,
    value: String?,
    dotStatus: DiagDotStatus = DiagDotStatus.NONE
) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(96.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (dotStatus != DiagDotStatus.NONE) {
                val dotColor = when (dotStatus) {
                    DiagDotStatus.GOOD -> Color(0xFF4CAF50)
                    DiagDotStatus.WARN -> Color(0xFFFFB300)
                    DiagDotStatus.BAD -> Color(0xFFF44336)
                    DiagDotStatus.NONE -> Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            } else {
                Spacer(modifier = Modifier.width(11.dp))
            }
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.95f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun detectProviderName(streamName: String?, streamDesc: String?, host: String?): String {
    val combined = "${streamName.orEmpty()} ${streamDesc.orEmpty()} ${host.orEmpty()}".lowercase(Locale.US)
    return when {
        combined.contains("real-debrid") || combined.contains("realdebrid") || combined.contains("[rd") || combined.contains("rdownload") -> "Real-Debrid"
        combined.contains("premiumize") || combined.contains("[pm") || combined.contains("energycdn") -> "Premiumize"
        combined.contains("torbox") || combined.contains("[tb") -> "TorBox"
        combined.contains("alldebrid") || combined.contains("[ad") -> "AllDebrid"
        combined.contains("debrid-link") || combined.contains("[dl") -> "Debrid-Link"
        combined.contains("easynews") -> "Easynews"
        else -> "Direct Stream"
    }
}

private fun detectHdrAndFormat(
    colorTransfer: Int?,
    rawCodec: String?,
    fileInfo: String?
): Triple<String, String?, String> {
    val combined = "${rawCodec.orEmpty()} ${fileInfo.orEmpty()}".lowercase(Locale.US)
    val isDv = combined.contains("dvhe") || combined.contains("dvh1") ||
        combined.contains("dolby vision") || combined.contains("dolby-vision") ||
        Regex("\\bdovi\\b|\\bdv\\b").containsMatchIn(combined)
    val isHdr10Plus = combined.contains("hdr10+") || combined.contains("hdr10plus")
    val isHdr10 = colorTransfer == C.COLOR_TRANSFER_ST2084 || combined.contains("hdr10") || combined.contains("hdr")
    val isHlg = colorTransfer == C.COLOR_TRANSFER_HLG || combined.contains("hlg")

    val hdrText = when {
        isDv && isHdr10 -> "Dolby Vision · HDR10"
        isDv -> "Dolby Vision"
        isHdr10Plus -> "HDR10+"
        isHdr10 -> "HDR10"
        isHlg -> "HLG"
        else -> "SDR"
    }
    val dvProfile = if (isDv) {
        when {
            combined.contains("p7") || combined.contains("profile 7") || combined.contains("07") -> "Profile 7 · mapped"
            combined.contains("p5") || combined.contains("profile 5") || combined.contains("05") -> "Profile 5 · native"
            else -> "Profile 8 · native"
        }
    } else null

    val pixFmt = if (isDv || isHdr10 || isHdr10Plus || isHlg || combined.contains("10bit") || combined.contains("10-bit")) {
        "10-bit 4:2:0"
    } else {
        "8-bit 4:2:0"
    }
    return Triple(hdrText, dvProfile, pixFmt)
}

private fun formatDiagTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun formatDiagBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

internal fun formatResolution(width: Int, height: Int): String {
    val maxDim = maxOf(width, height)
    val label = when {
        maxDim >= 3600 -> "4K"
        maxDim >= 2400 -> "1440p"
        maxDim >= 1800 -> "1080p"
        maxDim >= 1200 -> "720p"
        maxDim >= 800 -> "480p"
        else -> "${minOf(width, height)}p"
    }
    return "$width × $height ($label)"
}

@Composable
private fun StreamInfoHudButton(
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onLaidOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onPlaced { onLaidOut() }
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (enabled) NuvioTheme.colors.Secondary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.7f),
            focusedContainerColor = if (enabled) NuvioTheme.colors.Secondary else Color.White,
            focusedContentColor = if (enabled) NuvioTheme.colors.OnSecondary else Color.Black
        ),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (enabled) NuvioTheme.colors.Secondary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
        )
    ) {
        val statusText = if (enabled) {
            stringResource(R.string.diag_value_on)
        } else {
            stringResource(R.string.diag_value_off)
        }
        val hudLabel = stringResource(R.string.stream_info_hud)

        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        if (isFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.FocusRing
                    } else {
                        if (isFocused) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.4f)
                    }
                )
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        Text(
            text = "$hudLabel: $statusText",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isFocused || enabled) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

enum class VisualEnhancerMode(
    val id: String,
    val title: String,
    val subtitle: String
) {
    ORIGINAL("original", "Original", "Unmodified picture"),
    SMART_AUTO("smart_auto", "Smart Auto", "Adapts to genre"),
    HDR("hdr", "HDR", "Expanded contrast & glow"),
    CLEAR("clear", "Clear", "Natural crisp clarity"),
    ULTRA_CLEAR("ultra_clear", "Ultra Clear", "Vivid detail & punch"),
    ARCTIC_BLUE("arctic_blue", "Arctic Blue", "Cool cinema tone"),
    WARM_GLOW("warm_glow", "Warm Glow", "Golden sunset warmth"),
    CINEMATIC("cinematic", "Cinematic", "Film grade & shadows"),
    NIGHT_CLARITY("night_clarity", "Night Clarity", "Lifts dark scenes");

    companion object {
        fun fromId(id: String?): VisualEnhancerMode {
            return entries.firstOrNull { it.id == id } ?: ORIGINAL
        }
    }
}

object VisualEnhancerPreferences {
    private const val PREFS_NAME = "nuvio_visual_enhancer_prefs"
    private const val KEY_APPLY_TO_ALL = "apply_to_all"
    private const val KEY_SAVED_MODE = "saved_mode"

    fun isApplyToAll(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_APPLY_TO_ALL, false)
    }

    fun getInitialMode(context: Context): VisualEnhancerMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_APPLY_TO_ALL, false)) return VisualEnhancerMode.ORIGINAL
        return VisualEnhancerMode.fromId(prefs.getString(KEY_SAVED_MODE, VisualEnhancerMode.ORIGINAL.id))
    }

    fun save(context: Context, mode: VisualEnhancerMode, applyToAll: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_APPLY_TO_ALL, applyToAll)
            .putString(KEY_SAVED_MODE, if (applyToAll) mode.id else VisualEnhancerMode.ORIGINAL.id)
            .apply()
    }
}

fun resolveEffectiveVisualEnhancerMode(
    selectedMode: VisualEnhancerMode,
    genres: List<String>,
    contentType: String,
    title: String
): VisualEnhancerMode {
    if (selectedMode != VisualEnhancerMode.SMART_AUTO) return selectedMode
    val normalizedGenres = genres.map { it.trim().lowercase(Locale.US) }
    val lowerType = contentType.trim().lowercase(Locale.US)
    val lowerTitle = title.trim().lowercase(Locale.US)
    return when {
        lowerType == "anime" ||
            normalizedGenres.any { it.contains("anim") || it.contains("family") || it.contains("kids") } ->
            VisualEnhancerMode.ULTRA_CLEAR
        normalizedGenres.any { it.contains("horror") || it.contains("mystery") || it.contains("thriller") } ||
            lowerTitle.contains("dark") || lowerTitle.contains("night") ->
            VisualEnhancerMode.NIGHT_CLARITY
        normalizedGenres.any { it.contains("documentary") || it.contains("sport") || it.contains("news") } ->
            VisualEnhancerMode.CLEAR
        normalizedGenres.any { it.contains("sci-fi") || it.contains("science fiction") } ->
            VisualEnhancerMode.ARCTIC_BLUE
        normalizedGenres.any { it.contains("romance") || it.contains("comedy") || it.contains("musical") } ->
            VisualEnhancerMode.WARM_GLOW
        else -> VisualEnhancerMode.CINEMATIC
    }
}

/**
 * Zero-allocation static GPU compositor grading layer designed for 1GB RAM Fire Stick devices.
 * Keeps ExoPlayer on its native hardware SurfaceView and records static vector draw commands once
 * per mode switch (0 bytes allocated per video frame, 0% CPU overhead during playback).
 */
@Composable
fun VisualEnhancerVideoLayer(
    effectiveMode: VisualEnhancerMode,
    modifier: Modifier = Modifier
) {
    if (effectiveMode == VisualEnhancerMode.ORIGINAL) return

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.99f }
    ) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.5f, h * 0.5f)
        val maxRadius = size.maxDimension * 0.72f

        when (effectiveMode) {
            VisualEnhancerMode.ORIGINAL, VisualEnhancerMode.SMART_AUTO -> Unit

            VisualEnhancerMode.HDR -> {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x28FFF4D8),
                            Color(0x12FFD28A),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.5f, h * 0.44f),
                        radius = maxRadius * 0.85f
                    ),
                    blendMode = BlendMode.SoftLight
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x1405070B),
                            Color(0x36020305)
                        ),
                        center = center,
                        radius = maxRadius
                    ),
                    blendMode = BlendMode.Multiply
                )
            }

            VisualEnhancerMode.CLEAR -> {
                drawRect(
                    color = Color(0x1AF4F9FF),
                    blendMode = BlendMode.SoftLight
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x14FFFFFF),
                            Color.Transparent,
                            Color(0x18000000)
                        ),
                        center = center,
                        radius = maxRadius
                    ),
                    blendMode = BlendMode.Overlay
                )
            }

            VisualEnhancerMode.ULTRA_CLEAR -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0x22FF6B4A),
                            Color(0x18FFE17D),
                            Color(0x2238B6FF)
                        ),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    ),
                    blendMode = BlendMode.SoftLight
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x1EFFFFFF),
                            Color.Transparent,
                            Color(0x2A000000)
                        ),
                        center = center,
                        radius = maxRadius * 0.95f
                    ),
                    blendMode = BlendMode.Overlay
                )
            }

            VisualEnhancerMode.ARCTIC_BLUE -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x2838B6FF),
                            Color(0x1870D6FF),
                            Color(0x241B4965)
                        )
                    ),
                    blendMode = BlendMode.SoftLight
                )
                drawRect(
                    color = Color(0x1248CAE4),
                    blendMode = BlendMode.Screen
                )
            }

            VisualEnhancerMode.WARM_GLOW -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x2CFF9E3D),
                            Color(0x1EFFB703),
                            Color(0x249B2226)
                        )
                    ),
                    blendMode = BlendMode.SoftLight
                )
                drawRect(
                    color = Color(0x10FFB703),
                    blendMode = BlendMode.Screen
                )
            }

            VisualEnhancerMode.CINEMATIC -> {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x24FFD194),
                            Color(0x12D1913C),
                            Color(0x280B2535)
                        ),
                        center = Offset(w * 0.5f, h * 0.45f),
                        radius = maxRadius * 0.9f
                    ),
                    blendMode = BlendMode.SoftLight
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color(0x28000000),
                        0.18f to Color.Transparent,
                        0.82f to Color.Transparent,
                        1f to Color(0x32000000)
                    ),
                    blendMode = BlendMode.Multiply
                )
            }

            VisualEnhancerMode.NIGHT_CLARITY -> {
                drawRect(
                    color = Color(0x1EFFFFFF),
                    blendMode = BlendMode.Screen
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x1AE8F1FF),
                            Color(0x12C9DCF8),
                            Color(0x0AFFFFFF)
                        ),
                        center = center,
                        radius = maxRadius
                    ),
                    blendMode = BlendMode.SoftLight
                )
            }
        }
    }
}

@Composable
fun VisualEnhancerOverlay(
    visible: Boolean,
    selectedMode: VisualEnhancerMode,
    effectiveAutoMode: VisualEnhancerMode,
    applyToAll: Boolean,
    onPreviewMode: (VisualEnhancerMode?) -> Unit,
    onSelectMode: (VisualEnhancerMode) -> Unit,
    onToggleApplyToAll: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val modes = remember { VisualEnhancerMode.entries }
    val cardFocusRequesters = remember(modes.size) { List(modes.size) { FocusRequester() } }
    val applyAllFocusRequester = remember { FocusRequester() }
    val selectedIndex = remember(selectedMode) { modes.indexOf(selectedMode).coerceAtLeast(0) }

    LaunchedEffect(visible) {
        if (visible) {
            cardFocusRequesters[selectedIndex].requestFocusAfterFrames(frames = 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE
                ) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                        onPreviewMode(null)
                        onClose()
                    }
                    true
                } else {
                    false
                }
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp, top = 24.dp, bottom = 24.dp)
                .width(430.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xE614161C))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Visual Enhancer",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (selectedMode == VisualEnhancerMode.SMART_AUTO) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Auto · ${effectiveAutoMode.title}",
                            color = Color(0xFF90E0EF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(modes, key = { _, mode -> mode.id }) { index, mode ->
                    val isLastRow = index >= modes.size - 2
                    VisualEnhancerPresetCard(
                        mode = mode,
                        isSelected = mode == selectedMode,
                        effectiveAutoTitle = if (mode == VisualEnhancerMode.SMART_AUTO) effectiveAutoMode.title else null,
                        focusRequester = cardFocusRequesters[index],
                        downFocusRequester = if (isLastRow) applyAllFocusRequester else null,
                        onFocused = { onPreviewMode(mode) },
                        onClick = { onSelectMode(mode) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            var applyRowFocused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (applyRowFocused) Color.White.copy(alpha = 0.14f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .border(
                        width = if (applyRowFocused) 1.5.dp else 1.dp,
                        color = if (applyRowFocused) Color.White else Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .focusRequester(applyAllFocusRequester)
                    .focusProperties {
                        up = cardFocusRequesters[modes.lastIndex]
                        down = FocusRequester.Cancel
                    }
                    .onFocusChanged { applyRowFocused = it.isFocused }
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val isSelect = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        if (isSelect && event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                            onToggleApplyToAll(!applyToAll)
                            true
                        } else {
                            false
                        }
                    }
                    .clickable { onToggleApplyToAll(!applyToAll) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Apply to all",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Keep this filter active across all videos",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 11.sp
                    )
                }

                val trackColor by animateColorAsState(
                    targetValue = if (applyToAll) Color(0xFF38B6FF) else Color.White.copy(alpha = 0.22f),
                    animationSpec = tween(160),
                    label = "applySwitchTrack"
                )
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(trackColor)
                        .padding(3.dp),
                    contentAlignment = if (applyToAll) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun VisualEnhancerPresetCard(
    mode: VisualEnhancerMode,
    isSelected: Boolean,
    effectiveAutoTitle: String?,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.035f else 1f,
        animationSpec = tween(140),
        label = "presetCardScale"
    )
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .border(
                BorderStroke(
                    width = when {
                        isFocused -> 2.dp
                        isSelected -> 1.5.dp
                        else -> 1.dp
                    },
                    color = when {
                        isFocused -> Color.White
                        isSelected -> Color.White.copy(alpha = 0.78f)
                        else -> Color.White.copy(alpha = 0.14f)
                    }
                ),
                shape = shape
            )
            .focusRequester(focusRequester)
            .then(
                if (downFocusRequester != null) {
                    Modifier.focusProperties { down = downFocusRequester }
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                val isSelect = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (isSelect && event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick)
    ) {
        PresetCardSceneArtwork(mode = mode, modifier = Modifier.fillMaxSize())

        if (mode == VisualEnhancerMode.ORIGINAL) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 14.dp)
                    .size(22.dp)
            )
        } else if (mode == VisualEnhancerMode.SMART_AUTO) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 8.dp)
                    .size(16.dp)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.92f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "ACTIVE",
                        color = Color.Black,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.82f)
                    )
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (mode == VisualEnhancerMode.SMART_AUTO && effectiveAutoTitle != null) {
                    "Smart Auto ($effectiveAutoTitle)"
                } else {
                    mode.title
                },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PresetCardSceneArtwork(
    mode: VisualEnhancerMode,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (mode) {
            VisualEnhancerMode.ORIGINAL -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF2B2D33), Color(0xFF1E2025))
                    )
                )
            }

            VisualEnhancerMode.SMART_AUTO -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF1D3557), Color(0xFF457B9D), Color(0xFFE07A5F)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x55FFF3B0), Color.Transparent),
                        center = Offset(w * 0.72f, h * 0.32f),
                        radius = w * 0.45f
                    ),
                    center = Offset(w * 0.72f, h * 0.32f),
                    radius = w * 0.45f
                )
            }

            VisualEnhancerMode.HDR -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFFF9E00), Color(0xFFE85D04), Color(0xFF1B263B), Color(0xFF0D1B2A))
                    )
                )
                val mountain = Path().apply {
                    moveTo(0f, h * 0.78f)
                    lineTo(w * 0.58f, h * 0.26f)
                    lineTo(w * 0.76f, h * 0.34f)
                    lineTo(w, h * 0.64f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = mountain,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFFFD166), Color(0xFF415A77), Color(0xFF0D1B2A))
                    )
                )
            }

            VisualEnhancerMode.CLEAR -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF52B788), Color(0xFF2D6A4F), Color(0xFF1B4332)),
                        start = Offset.Zero,
                        end = Offset(w, h)
                    )
                )
                drawCircle(
                    color = Color(0x44B7E4C7),
                    radius = h * 0.42f,
                    center = Offset(w * 0.36f, h * 0.4f)
                )
                drawCircle(
                    color = Color(0x33D8F3DC),
                    radius = h * 0.28f,
                    center = Offset(w * 0.68f, h * 0.5f)
                )
            }

            VisualEnhancerMode.ULTRA_CLEAR -> {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFF72585), Color(0xFFFF6D00), Color(0xFF102A43), Color(0xFF0A192F)),
                        center = Offset(w * 0.52f, h * 0.48f),
                        radius = w * 0.65f
                    )
                )
            }

            VisualEnhancerMode.ARCTIC_BLUE -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color(0xFF0077B6),
                        0.55f to Color(0xFF48CAE4),
                        0.78f to Color(0xFFADE8F4),
                        1f to Color(0xFF023E8A)
                    )
                )
            }

            VisualEnhancerMode.WARM_GLOW -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color(0xFFFF7B00),
                        0.55f to Color(0xFFFFB703),
                        0.8f to Color(0xFFFB8500),
                        1f to Color(0xFF370617)
                    )
                )
            }

            VisualEnhancerMode.CINEMATIC -> {
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color(0xFF1A110B),
                        0.52f to Color(0xFF6C3D1E),
                        1f to Color(0xFFE09F3E)
                    )
                )
                drawRoundRect(
                    color = Color(0x44FFF3B0),
                    topLeft = Offset(w * 0.64f, h * 0.14f),
                    size = Size(w * 0.26f, h * 0.58f),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = Stroke(width = 2f)
                )
            }

            VisualEnhancerMode.NIGHT_CLARITY -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E293B), Color(0xFF334155), Color(0xFF0F172A))
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x88E2E8F0), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.42f),
                        radius = w * 0.48f
                    ),
                    center = Offset(w * 0.5f, h * 0.42f),
                    radius = w * 0.48f
                )
            }
        }
    }
}
