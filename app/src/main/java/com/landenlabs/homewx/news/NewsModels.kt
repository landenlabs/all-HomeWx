package com.landenlabs.homewx.news

data class NewsItem(
    val title: String,
    val link: String,
    val description: String,
    val publishedAtMillis: Long,
    val imageUrl: String?
)

/** One configured RSS feed, shown as a tab in the News panel. Equality is by value, so it works as
 *  a map key and a tab tag; editing a feed in Settings yields a new key for its items. */
data class NewsSource(val label: String, val feedUrl: String) {
    companion object {
        /** WMUR has no dedicated "breaking news" feed - topstories-rss is its continuous news stream. */
        val DEFAULT_FEED_1 = NewsSource("WMUR", "https://www.wmur.com/topstories-rss")

        /** cbsnews.com/boston's "breaking-news" feed is empty except during live events, so
         *  local-news (a steady, image-backed stream) is used instead - confirmed with Dennis. */
        val DEFAULT_FEED_2 = NewsSource("WBZ", "https://www.cbsnews.com/boston/latest/rss/local-news")
    }
}
