package com.dlang.homewx.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelNewsBinding
import com.dlang.homewx.news.LoggingWebViewClient
import com.dlang.homewx.news.NewsItem
import com.dlang.homewx.news.NewsSourceId
import com.google.android.material.tabs.TabLayout

/** Null unless ACCESS_FINE_LOCATION is granted - Android ties real WiFi SSID lookups to location
 *  permission, returning "<unknown ssid>" without it. */
private fun currentWifiSsid(context: Context): String? {
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasLocationPermission) return null
    val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    val ssid = wifiManager.connectionInfo?.ssid?.trim('"')
    return ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
}

/** Tab-tag sentinel for the Drought sub-tab, distinguishing it from a [NewsSourceId] tag on the
 *  same [TabLayout]. */
private object DroughtTabTag

/** Tab-tag sentinel for the Stocks sub-tab, same purpose as [DroughtTabTag]. */
private object StocksTabTag

/** News tabs + list, plus two extra sub-tabs: "Drought" shows the US Drought Monitor NH map,
 *  and "Stocks" shows a TradingView Market Overview widget, both in a WebView instead of a
 *  news source. Inflates itself into [container] and owns its own tab/adapter/WebView wiring. */
class NewsPanel(container: ViewGroup, onArticleClick: (NewsItem) -> Unit) {

    private val binding = PanelNewsBinding.inflate(LayoutInflater.from(container.context), container, false)
    val root: View get() = binding.root

    private val adapter = NewsAdapter(onItemClick = onArticleClick)
    private var selectedSource = NewsSourceId.values().first()
    private var latestItemsBySource: Map<NewsSourceId, List<NewsItem>> = emptyMap()
    private var droughtMonitorLoaded = false
    private var stocksWidgetLoaded = false

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

        binding.stocksWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        binding.stocksWebView.webViewClient = LoggingWebViewClient(container.context)

        binding.newsTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (val tag = tab.tag) {
                    is NewsSourceId -> {
                        selectedSource = tag
                        showNewsList()
                    }
                    DroughtTabTag -> showDroughtMonitor()
                    StocksTabTag -> showStocks()
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
        binding.newsTabLayout.addTab(
            binding.newsTabLayout.newTab().setText(R.string.news_tab_stocks).apply { tag = StocksTabTag }
        )
    }

    fun onStateUpdated(itemsBySource: Map<NewsSourceId, List<NewsItem>>) {
        latestItemsBySource = itemsBySource
        refresh()
    }

    private fun showNewsList() {
        binding.newsListContainer.visibility = View.VISIBLE
        binding.droughtMonitorWebView.visibility = View.GONE
        binding.stocksWebView.visibility = View.GONE
        refresh()
    }

    /** Loads the map once on first visit to this sub-tab, not on every selection - it's a
     *  slow-changing daily map, not something that needs a fresh network fetch each time. */
    private fun showDroughtMonitor() {
        binding.newsListContainer.visibility = View.GONE
        binding.droughtMonitorWebView.visibility = View.VISIBLE
        binding.stocksWebView.visibility = View.GONE
        if (!droughtMonitorLoaded) {
            binding.droughtMonitorWebView.loadUrl(DROUGHT_MONITOR_URL)
            droughtMonitorLoaded = true
        }
    }

    /** Loads the widget once on first visit, same rationale as [showDroughtMonitor]. The widget
     *  self-refreshes its quotes over its own websocket once loaded, so a reload isn't needed. */
    private fun showStocks() {
        binding.newsListContainer.visibility = View.GONE
        binding.droughtMonitorWebView.visibility = View.GONE
        binding.stocksWebView.visibility = View.VISIBLE
        if (!stocksWidgetLoaded) {
            binding.stocksWebView.loadDataWithBaseURL(
                "https://s3.tradingview.com/",
                STOCKS_WIDGET_HTML,
                "text/html",
                "utf-8",
                null
            )
            stocksWidgetLoaded = true
        }
    }

    private fun refresh() {
        val items = latestItemsBySource[selectedSource].orEmpty()
        adapter.submit(items)
        binding.newsEmptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            val context = binding.root.context
            binding.newsEmptyText.text = currentWifiSsid(context)?.let {
                context.getString(R.string.news_no_data_with_wifi, it)
            } ?: context.getString(R.string.news_no_data)
        }
    }

    companion object {
        private const val DROUGHT_MONITOR_URL = "https://droughtmonitor.unl.edu/CurrentMap/StateDroughtMonitor.aspx?NH"

        /** Free, ad-free TradingView "Market Overview" widget - no API key needed. Colors are
         *  hand-matched to this app's dark palette (bg_root/text_primary/accent_cool) rather than
         *  using the widget's own theme presets, since those don't line up with our exact hues. */
        private val STOCKS_WIDGET_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>html,body{margin:0;padding:0;background:#0B0F14;height:100%;}</style>
            </head>
            <body>
              <div class="tradingview-widget-container">
                <div class="tradingview-widget-container__widget"></div>
              </div>
              <script type="text/javascript" src="https://s3.tradingview.com/external-embedding/embed-widget-market-overview.js" async>
              {
                "colorTheme": "dark",
                "dateRange": "12M",
                "showChart": true,
                "locale": "en",
                "largeChartUrl": "",
                "isTransparent": true,
                "showSymbolLogo": true,
                "showFloatingTooltip": false,
                "width": "100%",
                "height": "100%",
                "plotLineColorGrowing": "rgba(138, 180, 248, 1)",
                "plotLineColorFalling": "rgba(138, 180, 248, 1)",
                "gridLineColor": "rgba(30, 42, 56, 1)",
                "scaleFontColor": "rgba(154, 167, 180, 1)",
                "belowLineFillColorGrowing": "rgba(138, 180, 248, 0.12)",
                "belowLineFillColorFalling": "rgba(138, 180, 248, 0.12)",
                "belowLineFillColorGrowingBottom": "rgba(138, 180, 248, 0)",
                "belowLineFillColorFallingBottom": "rgba(138, 180, 248, 0)",
                "symbolActiveColor": "rgba(138, 180, 248, 0.12)",
                "tabs": [
                  {
                    "title": "Indices",
                    "originalTitle": "Indices",
                    "symbols": [
                      { "s": "FOREXCOM:SPXUSD", "d": "S&P 500" },
                      { "s": "FOREXCOM:NSXUSD", "d": "Nasdaq 100" },
                      { "s": "FOREXCOM:DJI", "d": "Dow 30" },
                      { "s": "INDEX:RUT", "d": "Russell 2000" },
                      { "s": "CBOE:VIX", "d": "VIX" }
                    ]
                  },
                  {
                    "title": "Stocks",
                    "originalTitle": "Stocks",
                    "symbols": [
                      { "s": "NASDAQ:AAPL", "d": "Apple" },
                      { "s": "NASDAQ:MSFT", "d": "Microsoft" },
                      { "s": "NASDAQ:GOOGL", "d": "Alphabet" },
                      { "s": "NASDAQ:AMZN", "d": "Amazon" },
                      { "s": "NASDAQ:NVDA", "d": "Nvidia" },
                      { "s": "NASDAQ:TSLA", "d": "Tesla" }
                    ]
                  }
                ]
              }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
