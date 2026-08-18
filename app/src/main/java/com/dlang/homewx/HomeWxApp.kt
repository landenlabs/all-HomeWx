package com.dlang.homewx

import android.app.Application
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

class HomeWxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // A disk cache (on top of Picasso's default in-memory LRU) keeps RSS thumbnails
        // available across app/process restarts instead of re-fetching every time.
        val client = OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "picasso_http_cache"), PICASSO_DISK_CACHE_BYTES))
            .build()
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(OkHttp3Downloader(client)).build())
    }

    companion object {
        private const val PICASSO_DISK_CACHE_BYTES = 20L * 1024 * 1024
    }
}
