package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dlang.homewx.databinding.PanelArticleBinding
import com.dlang.homewx.news.LoggingWebViewClient
import com.dlang.homewx.news.NewsItem

/** Full-story article viewer. Inflates itself into [container]. */
class ArticlePanel(container: ViewGroup, onBack: () -> Unit) {

    private val binding = PanelArticleBinding.inflate(LayoutInflater.from(container.context), container, false)
    val root: View get() = binding.root

    init {
        container.addView(root)
        binding.articleWebView.settings.javaScriptEnabled = true
        // News sites' JS frameworks commonly assume localStorage/sessionStorage is available
        // and error out ("Something went wrong") when it's not - WebView disables it by default.
        binding.articleWebView.settings.domStorageEnabled = true
        // Without a client, the WebView hands off navigation to an external browser app
        // instead of keeping the tapped story inside this same panel.
        binding.articleWebView.webViewClient = LoggingWebViewClient(container.context)
        binding.articleBackButton.setOnClickListener { onBack() }
    }

    fun load(item: NewsItem) {
        binding.articleTitleText.text = item.title
        binding.articleWebView.loadUrl(item.link)
    }

    fun close() {
        binding.articleWebView.stopLoading()
        binding.articleWebView.loadUrl("about:blank")
    }
}
