package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelNewsBinding
import com.dlang.homewx.news.LoggingWebViewClient
import com.dlang.homewx.news.NewsItem
import com.dlang.homewx.news.NewsSourceId
import com.google.android.material.tabs.TabLayout

/** Tab-tag sentinel for the Drought sub-tab, distinguishing it from a [NewsSourceId] tag on the
 *  same [TabLayout]. */
private object DroughtTabTag

/** News tabs + list, plus one extra "Drought" sub-tab that shows the US Drought Monitor NH map
 *  in a WebView instead of a news source. Inflates itself into [container] and owns its own
 *  tab/adapter/WebView wiring. */
class NewsPanel(container: ViewGroup, onArticleClick: (NewsItem) -> Unit) {

    private val binding = PanelNewsBinding.inflate(LayoutInflater.from(container.context), container, false)
    val root: View get() = binding.root

    private val adapter = NewsAdapter(onItemClick = onArticleClick)
    private var selectedSource = NewsSourceId.values().first()
    private var latestItemsBySource: Map<NewsSourceId, List<NewsItem>> = emptyMap()
    private var droughtMonitorLoaded = false

    init {
        container.addView(root)
        binding.newsRecyclerView.layoutManager = LinearLayoutManager(container.context)
        binding.newsRecyclerView.adapter = adapter

        binding.droughtMonitorWebView.settings.apply {
            javaScriptEnabled = true
            // Same domStorage gotcha as ArticlePanel's WebView - some sites error out without it.
            domStorageEnabled = true
            // The Drought Monitor page isn't mobile-optimized - without these it renders at
            // desktop width and gets tiny-and-zoomed-out instead of filling the panel.
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        binding.droughtMonitorWebView.webViewClient = LoggingWebViewClient(container.context)

        binding.newsTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (val tag = tab.tag) {
                    is NewsSourceId -> {
                        selectedSource = tag
                        showNewsList()
                    }
                    DroughtTabTag -> showDroughtMonitor()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        NewsSourceId.values().forEach { source ->
            binding.newsTabLayout.addTab(binding.newsTabLayout.newTab().setText(source.label).apply { tag = source })
        }
        binding.newsTabLayout.addTab(
            binding.newsTabLayout.newTab().setText(R.string.news_tab_drought).apply { tag = DroughtTabTag }
        )
    }

    fun onStateUpdated(itemsBySource: Map<NewsSourceId, List<NewsItem>>) {
        latestItemsBySource = itemsBySource
        refresh()
    }

    private fun showNewsList() {
        binding.newsRecyclerView.visibility = View.VISIBLE
        binding.droughtMonitorWebView.visibility = View.GONE
        refresh()
    }

    /** Loads the map once on first visit to this sub-tab, not on every selection - it's a
     *  slow-changing daily map, not something that needs a fresh network fetch each time. */
    private fun showDroughtMonitor() {
        binding.newsRecyclerView.visibility = View.GONE
        binding.droughtMonitorWebView.visibility = View.VISIBLE
        if (!droughtMonitorLoaded) {
            binding.droughtMonitorWebView.loadUrl(DROUGHT_MONITOR_URL)
            droughtMonitorLoaded = true
        }
    }

    private fun refresh() {
        adapter.submit(latestItemsBySource[selectedSource].orEmpty())
    }

    companion object {
        private const val DROUGHT_MONITOR_URL = "https://droughtmonitor.unl.edu/CurrentMap/StateDroughtMonitor.aspx?NH"
    }
}
