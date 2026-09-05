package com.dlang.homewx.news

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dlang.homewx.settings.AppSettings

/**
 * Logs the domain of every request the article WebView makes, gated by a settings toggle -
 * this is reconnaissance for building an ad-domain blocklist, it doesn't block anything yet.
 */
open class LoggingWebViewClient(private val context: Context) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (AppSettings.isWebViewRequestLoggingEnabled(context)) {
            WebRequestLogger.logDomainIfNew(context, request.url.toString())
        }
        return super.shouldInterceptRequest(view, request)
    }
}
