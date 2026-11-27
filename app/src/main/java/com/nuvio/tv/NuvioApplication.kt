package com.nuvio.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.SizeResolver
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.image.StaleWhileRevalidateCacheStrategy
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Downscales oversized 4K TMDB image URLs (`/t/p/original/`) to 1080p-friendly sizes and caps
 * unbounded Coil decode requests to 1920x1080 on 1GB RAM / 1080p Fire Stick setups, saving 25+ MB
 * of bitmap heap per backdrop with zero visual loss on a 1080p projector.
 */
private class FireStick1080pImageCapInterceptor(
    private val isLowRamDevice: Boolean
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val req = chain.request
        val rawData = req.data
        var builder: coil3.request.ImageRequest.Builder? = null

        if (rawData is String && rawData.contains("image.tmdb.org/t/p/")) {
            val isLogoAsset = rawData.endsWith(".png", ignoreCase = true) || rawData.endsWith(".svg", ignoreCase = true)
            val optimizedUrl = when {
                rawData.contains("/t/p/original/") ->
                    rawData.replace("/t/p/original/", if (isLowRamDevice) "/t/p/w1280/" else "/t/p/w1920_and_h1080_multi_faces/")
                !isLogoAsset && rawData.contains("/t/p/w500/") ->
                    rawData.replace("/t/p/w500/", "/t/p/w342/")
                isLowRamDevice && !isLogoAsset && rawData.contains("/t/p/w780/") ->
                    rawData.replace("/t/p/w780/", "/t/p/w500/")
                else -> rawData
            }
            if (optimizedUrl != rawData) {
                builder = (builder ?: req.newBuilder()).data(optimizedUrl)
            }
        }

        if (req.sizeResolver == SizeResolver.ORIGINAL) {
            val urlStr = rawData as? String ?: ""
            val isPoster = urlStr.contains("/poster/") || urlStr.contains("/w500/") || urlStr.contains("/w342/") || urlStr.contains("/w780/")
            val cappedSize = if (isPoster) {
                Size(Dimension(342), Dimension(513))
            } else if (isLowRamDevice) {
                Size(Dimension(1280), Dimension(720))
            } else {
                Size(Dimension(1920), Dimension(1080))
            }
            builder = (builder ?: req.newBuilder()).size(cappedSize)
        }

        val finalRequest = builder?.build() ?: req
        return chain.withRequest(finalRequest).proceed()
    }
}

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var imagePerformancePreferences: ImagePerformancePreferences
    @Inject lateinit var customBackdropStore: com.nuvio.tv.data.local.CustomBackdropStore
    @Inject lateinit var tmdbMetadataService: dagger.Lazy<com.nuvio.tv.core.tmdb.TmdbMetadataService>
    @Inject lateinit var browseRepository: dagger.Lazy<com.nuvio.tv.core.browse.BrowseRepository>
    @Inject lateinit var trailerPlayerPool: dagger.Lazy<com.nuvio.tv.core.player.TrailerPlayerPool>
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder
    @Inject lateinit var streamPrefetcher: dagger.Lazy<com.nuvio.tv.core.streams.StreamPrefetcher>
    private val startupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    companion object {
        @Volatile
        private var appInstance: NuvioApplication? = null

        @Volatile
        private var sharedImageDispatcher: Dispatcher? = null

        /**
         * Throttles background image downloads while video playback is active so 100% of the
         * 1GB Fire Stick's Wi-Fi bandwidth and CPU stay dedicated to the playing stream.
         */
        fun setVideoPlaybackActive(active: Boolean) {
            val is1Gb = com.nuvio.tv.core.runtime.DeviceMemoryTier.isFireStick1GbMode
            sharedImageDispatcher?.apply {
                if (active) {
                    maxRequests = 2
                    maxRequestsPerHost = 2
                } else {
                    maxRequests = if (is1Gb) 12 else 32
                    maxRequestsPerHost = if (is1Gb) 6 else 16
                }
            }
        }

        /**
         * Aggressive RAM eviction (#5): called when entering PlayerScreen to flush in-memory Home/Details
         * bitmap caches, ambient color-wash caches, idle trailer decoders, and secondary TMDB caches
         * (while preserving disk-cached posters so pressing Back remains instant).
         */
        fun onPlayerEntered(context: Context) {
            setVideoPlaybackActive(true)
            runCatching { SingletonImageLoader.get(context.applicationContext).memoryCache?.clear() }
            runCatching { com.nuvio.tv.ui.newlook.home.clearAmbientWashCache() }
            runCatching { com.nuvio.tv.ui.newlook.home.clearAmbientTintCache() }
            val app = appInstance
            if (app != null) {
                runCatching { app.tmdbMetadataService.get().trimMemoryCaches() }
                runCatching { app.browseRepository.get().trimMemoryCache() }
                runCatching { app.trailerPlayerPool.get().yield() }
            }
        }

        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        appInstance = this
        // Before anything else: the TMDB and Gemini keys and low-RAM tier are read synchronously from here on.
        com.nuvio.tv.core.apikeys.ApiKeys.init(this)
        com.nuvio.tv.core.runtime.DeviceMemoryTier.init(this)
        super.onCreate()
        com.nuvio.tv.core.runtime.DeviceMemoryTier.overrideMode = imagePerformancePreferences.lowRamMode
        // Eagerly touch customBackdropStore so its StateFlow populates the in-memory mirror.
        customBackdropStore.overrides.value
        PluginRuntimeHooks.onApplicationCreate(this)
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""

        // Fast Cold-Start (#14): Defer Sentry init, Android TV channel sync, and addon host warmup
        // until 5 seconds after launch so 100% of CPU goes to rendering Profile Selection and Home first.
        startupScope.launch {
            kotlinx.coroutines.delay(5_000L)
            runCatching { SentryInitializer.start(this@NuvioApplication, sentrySettingsDataStore) }
            runCatching { androidTvChannelSyncService.start() }
            runCatching { streamPrefetcher.get().warmUpAddonHosts() }
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val is1GbMode = com.nuvio.tv.core.runtime.DeviceMemoryTier.isFireStick1GbMode
        val imageOkHttpClient by lazy {
            val imageDispatcher = Dispatcher().apply {
                maxRequests = if (is1GbMode) 12 else 32
                maxRequestsPerHost = if (is1GbMode) 6 else 16
            }
            sharedImageDispatcher = imageDispatcher
            OkHttpClient.Builder()
                .dispatcher(imageDispatcher)
                .dns(IPv4FirstDns())
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.SocketTimeoutException) {
                        chain.withConnectTimeout(3, TimeUnit.SECONDS)
                            .withReadTimeout(4, TimeUnit.SECONDS)
                            .proceed(chain.request())
                    }
                }
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        val imageLoaderRef: () -> ImageLoader = { SingletonImageLoader.get(this) }

        return ImageLoader.Builder(this)
            .components {
                add(FireStick1080pImageCapInterceptor(isLowRamDevice = is1GbMode))
                add(CustomPosterFallbackInterceptor())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        cacheStrategy = {
                            StaleWhileRevalidateCacheStrategy(
                                revalidationClient = { imageOkHttpClient },
                                imageLoaderProvider = imageLoaderRef,
                            )
                        },
                    )
                )
            }
            .memoryCache {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
                val cachePercent = when {
                    is1GbMode || totalRamMb <= 1400 -> 0.10
                    totalRamMb <= 2048 -> 0.15
                    totalRamMb <= 3072 -> 0.20
                    else -> 0.25
                }
                val memoryCacheBuilder = MemoryCache.Builder()
                if (is1GbMode || totalRamMb <= 1400) {
                    val maxHeapBytes = Runtime.getRuntime().maxMemory()
                    val targetBytes = (maxHeapBytes * cachePercent).toLong()
                        .coerceIn(8L * 1024 * 1024, 18L * 1024 * 1024)
                    memoryCacheBuilder.maxSizeBytes(targetBytes).build()
                } else {
                    memoryCacheBuilder.maxSizePercent(context, cachePercent).build()
                }
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(if (is1GbMode) 64L * 1024 * 1024 else 200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(imagePerformancePreferences.hardwareBitmapsEnabled)
            .allowRgb565(if (is1GbMode) true else imagePerformancePreferences.rgb565Enabled)
            .bitmapFactoryMaxParallelism(if (is1GbMode) 2 else 4)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_UI_HIDDEN) {
            runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
            com.nuvio.tv.ui.newlook.home.clearAmbientWashCache()
            com.nuvio.tv.ui.newlook.home.clearAmbientTintCache()
        }
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) {
            runCatching { tmdbMetadataService.get().trimMemoryCaches() }
            runCatching { browseRepository.get().trimMemoryCache() }
            // Only yield trailer decoders when the app is actually backgrounded or not actively playing
            if (level >= TRIM_MEMORY_UI_HIDDEN || !trailerPlayerPool.get().isActivelyPlaying()) {
                runCatching { trailerPlayerPool.get().yield() }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
        com.nuvio.tv.ui.newlook.home.clearAmbientWashCache()
        com.nuvio.tv.ui.newlook.home.clearAmbientTintCache()
        runCatching { tmdbMetadataService.get().trimMemoryCaches() }
        runCatching { browseRepository.get().trimMemoryCache() }
        runCatching { trailerPlayerPool.get().yield() }
    }
}
