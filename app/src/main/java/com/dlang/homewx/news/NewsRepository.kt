package com.dlang.homewx.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NewsFeedException(message: String) : Exception(message)

class NewsRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchFeed(source: NewsSourceId): List<NewsItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(source.feedUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw NewsFeedException("${source.label} feed request failed: HTTP ${response.code}")
            }
            response.body.byteStream().use { RssFeedParser.parse(it) }
        }
    }
}
