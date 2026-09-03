package com.dlang.homewx

import android.app.Application
import com.dlang.homewx.weather.wxdata.WxDataWeatherProvider
import com.squareup.picasso.LruCache
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

class HomeWxApp : Application() {

    // Picasso.get() has no public evictAll()/cache accessor, so we build the singleton with our
    // own LruCache and keep a reference here - it's the only way to clear it from a low-memory callback.
    private lateinit var picassoMemoryCache: LruCache

    override fun onCreate() {
        super.onCreate()
        // A disk cache (on top of Picasso's in-memory LRU) keeps RSS thumbnails
        // available across app/process restarts instead of re-fetching every time.
        val client = OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "picasso_http_cache"), PICASSO_DISK_CACHE_BYTES))
            .build()
        picassoMemoryCache = LruCache(this)
        Picasso.setSingletonInstance(
            Picasso.Builder(this)
                .downloader(OkHttp3Downloader(client))
                .memoryCache(picassoMemoryCache)
                .build()
        )

        // Once at app startup regardless of which weather source is currently active - see
        // WxDataWeatherProvider.initialize().
        WxDataWeatherProvider.initialize(this)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        picassoMemoryCache.clear()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_RUNNING_LOW and above (including UI_HIDDEN/BACKGROUND/MODERATE/COMPLETE)
        // all signal the system wants memory back.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            picassoMemoryCache.clear()
        }
    }

    companion object {
        private const val PICASSO_DISK_CACHE_BYTES = 20L * 1024 * 1024
    }
}
