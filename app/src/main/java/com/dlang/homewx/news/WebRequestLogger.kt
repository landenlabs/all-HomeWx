package com.dlang.homewx.news

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Domains seen in article WebView requests, appended to a file so they can be pulled via
 * `adb pull` and turned into an ad-domain blocklist. The in-memory cache means a domain that
 * fires on every article (ad/analytics networks) is written once per app run, not once per hit.
 */
object WebRequestLogger {

    const val LOG_FILE_NAME = "webview_request_domains.log"

    private val loggedDomains = mutableSetOf<String>()

    // App-specific external storage (no permission needed) so the file is reachable by plain
    // `adb pull` - internal filesDir is app-private and adb can't traverse into it without root.
    fun logFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FILE_NAME)

    @Synchronized
    fun logDomainIfNew(context: Context, url: String) {
        val domain = Uri.parse(url).host ?: return
        if (!loggedDomains.add(domain)) return
        try {
            logFile(context).appendText("$domain\n")
        } catch (e: IOException) {
            Log.w("WebRequestLogger", "Failed to write to ${LOG_FILE_NAME}", e)
        }
    }
}
