package com.dlang.homewx.news

data class NewsItem(
    val title: String,
    val link: String,
    val description: String,
    val publishedAtMillis: Long,
    val imageUrl: String?
)

/** WMUR has no dedicated "breaking news" feed - topstories-rss is its continuous news stream. */
enum class NewsSourceId(val label: String, val feedUrl: String) {
    WMUR("WMUR", "https://www.wmur.com/topstories-rss"),

    /** cbsnews.com/boston's "breaking-news" feed is empty except during live events, so
     *  local-news (a steady, image-backed stream) is used instead - confirmed with Dennis. */
    WBZ("WBZ", "https://www.cbsnews.com/boston/latest/rss/local-news")
}
