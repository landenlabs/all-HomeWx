package com.dlang.homewx.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Small self-contained image loader for news thumbnails - the project has no
 * Coil/Glide dependency, and a handful of small RSS thumbnails don't warrant adding one.
 */
object NewsImageLoader {

    private val client = OkHttpClient()
    private val cache = LruCache<String, Bitmap>(32)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun load(url: String, target: ImageView) {
        target.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetchBitmap(url) }
            // The view may have been rebound to a different item by the time this returns.
            if (bitmap != null && target.tag == url) {
                cache.put(url, bitmap)
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.byteStream().use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()
}
