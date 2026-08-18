package com.dlang.homewx.news

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Minimal RSS 2.0 parser covering the two shapes seen from WMUR (plain
 * <enclosure>) and CBS Boston (<media:content>/<media:thumbnail>) - picks
 * whichever image tag is present per item.
 */
object RssFeedParser {

    fun parse(input: InputStream): List<NewsItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val items = mutableListOf<NewsItem>()
        var inItem = false
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var pubDate: String? = null
        var imageUrl: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = null; link = null; description = null; pubDate = null; imageUrl = null
                    }
                    "title" -> if (inItem) title = parser.nextTextSafe()
                    "link" -> if (inItem) link = parser.nextTextSafe()
                    "description" -> if (inItem) description = parser.nextTextSafe()
                    "pubDate" -> if (inItem) pubDate = parser.nextTextSafe()
                    "enclosure" -> if (inItem && imageUrl == null) {
                        val type = parser.getAttributeValue(null, "type")
                        if (type == null || type.startsWith("image")) {
                            imageUrl = parser.getAttributeValue(null, "url")
                        }
                    }
                    "media:content", "media:thumbnail" -> if (inItem && imageUrl == null) {
                        imageUrl = parser.getAttributeValue(null, "url")
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    inItem = false
                    val itemTitle = title
                    val itemLink = link
                    if (itemTitle != null && itemLink != null) {
                        items.add(
                            NewsItem(
                                title = stripHtml(itemTitle),
                                link = itemLink,
                                description = description?.let { stripHtml(it) }.orEmpty(),
                                publishedAtMillis = pubDate?.let(::parsePubDate) ?: System.currentTimeMillis(),
                                imageUrl = imageUrl
                            )
                        )
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun stripHtml(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private val PUB_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz"
    )

    private fun parsePubDate(value: String): Long? =
        PUB_DATE_PATTERNS.firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(value)?.time }.getOrNull()
        }

    /** [XmlPullParser.nextText] but tolerant of any unexpected nested markup. */
    private fun XmlPullParser.nextTextSafe(): String? =
        runCatching { nextText() }.getOrNull()?.trim()
}
