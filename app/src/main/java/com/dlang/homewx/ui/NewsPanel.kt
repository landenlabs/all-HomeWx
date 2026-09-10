package com.dlang.homewx.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dlang.homewx.BuildConfig
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelNewsBinding
import com.dlang.homewx.news.LoggingWebViewClient
import com.dlang.homewx.news.NewsItem
import com.dlang.homewx.news.NewsSourceId
import com.dlang.homewx.state.AppState
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import org.json.JSONObject

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

/** A [LoggingWebViewClient] that also flips [errorText]/[view] visibility and reports success
 *  or failure back to the panel, so a failed load can be retried later instead of the WebView
 *  just sitting on a blank/error page forever. [isRelevantFailure] picks out which failing
 *  request actually means "this tab's content didn't load" - for a real page navigation that's
 *  the main frame, but for content assembled from a local HTML shell (like the stocks widget)
 *  the main frame trivially "succeeds" and the real dependency is a specific sub-resource. */
private fun loadTrackingClient(
    context: Context,
    errorText: TextView,
    isRelevantFailure: (WebResourceRequest) -> Boolean,
    onFinished: (url: String?) -> Unit,
    onFailed: () -> Unit
): LoggingWebViewClient = object : LoggingWebViewClient(context) {
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        errorText.visibility = View.GONE
        view.visibility = View.VISIBLE
        onFinished(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (!isRelevantFailure(request)) return
        view.visibility = View.GONE
        errorText.text = context.getString(R.string.webview_load_failed)
        errorText.visibility = View.VISIBLE
        onFailed()
    }
}

/** Tab-tag sentinel for the Drought sub-tab, distinguishing it from a [NewsSourceId] tag on the
 *  same [TabLayout]. */
private object DroughtTabTag

/** Tab-tag sentinel for the Stocks sub-tab, same purpose as [DroughtTabTag]. */
private object StocksTabTag

/** Tab-tag sentinel for the Wyze sub-tab, same purpose as [DroughtTabTag]. */
private object WyzeTabTag

/** Tracks whether a lazily-loaded WebView tab has never been loaded, is showing content, or
 *  failed to load - [FAILED] is what lets a network-recovery signal or a later tab visit retry
 *  it, instead of the load-once flag this replaced getting permanently stuck after a failure. */
private enum class WebViewLoadState { NOT_LOADED, LOADED, FAILED }

/** News tabs + list, plus three extra sub-tabs: "Drought" shows the US Drought Monitor NH map,
 *  "Stocks" shows a TradingView Market Overview widget, and "Wyze" auto-logs into the Wyze
 *  camera portal, all in a WebView instead of a news source. Inflates itself into [container]
 *  and owns its own tab/adapter/WebView wiring. */
class NewsPanel(
    container: ViewGroup,
    private val lifecycleScope: LifecycleCoroutineScope,
    onArticleClick: (NewsItem) -> Unit
) {

    private val binding = PanelNewsBinding.inflate(LayoutInflater.from(container.context), container, false)
    val root: View get() = binding.root

    private val adapter = NewsAdapter(onItemClick = onArticleClick)
    private var selectedSource = NewsSourceId.values().first()
    private var latestItemsBySource: Map<NewsSourceId, List<NewsItem>> = emptyMap()
    private var droughtState = WebViewLoadState.NOT_LOADED
    private var stocksState = WebViewLoadState.NOT_LOADED
    private var wyzeState = WebViewLoadState.NOT_LOADED

    /** Guards against re-submitting the Wyze login form on every onPageFinished within the same
     *  load attempt (e.g. the auth page firing more than once) - reset each time [loadWyze] starts
     *  a fresh attempt. */
    private var wyzeLoginSubmitted = false

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
        binding.droughtMonitorWebView.webViewClient = loadTrackingClient(
            context = container.context,
            errorText = binding.droughtErrorText,
            isRelevantFailure = { it.isForMainFrame },
            onFinished = { droughtState = WebViewLoadState.LOADED },
            onFailed = { droughtState = WebViewLoadState.FAILED }
        )

        binding.stocksWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        binding.stocksWebView.webViewClient = loadTrackingClient(
            context = container.context,
            errorText = binding.stocksErrorText,
            // The widget's own HTML loads locally via loadDataWithBaseURL, so it's never the
            // main frame that fails - the actual dependency is this script tag, a sub-resource.
            isRelevantFailure = { it.isForMainFrame || it.url.toString().contains("tradingview.com") },
            onFinished = { stocksState = WebViewLoadState.LOADED },
            onFailed = { stocksState = WebViewLoadState.FAILED }
        )

        binding.wyzeWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        binding.wyzeWebView.webViewClient = loadTrackingClient(
            context = container.context,
            errorText = binding.wyzeErrorText,
            isRelevantFailure = { it.isForMainFrame },
            onFinished = { url ->
                wyzeState = WebViewLoadState.LOADED
                handleWyzePageFinished(url)
            },
            onFailed = { wyzeState = WebViewLoadState.FAILED }
        )

        // Retries whichever of these ever failed to load, the moment the network comes back -
        // without this, a failure during an outage left the WebView stuck on its error page
        // forever, since nothing else ever calls loadUrl/loadDataWithBaseURL again.
        lifecycleScope.launch {
            AppState.networkRecovered.collect {
                if (droughtState == WebViewLoadState.FAILED) loadDroughtMonitor()
                if (stocksState == WebViewLoadState.FAILED) loadStocks()
                if (wyzeState == WebViewLoadState.FAILED) loadWyze()
            }
        }

        binding.newsTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (val tag = tab.tag) {
                    is NewsSourceId -> {
                        selectedSource = tag
                        showNewsList()
                    }
                    DroughtTabTag -> showDroughtMonitor()
                    StocksTabTag -> showStocks()
                    WyzeTabTag -> showWyze()
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
        binding.newsTabLayout.addTab(
            binding.newsTabLayout.newTab().setText(R.string.news_tab_wyze).apply { tag = WyzeTabTag }
        )
    }

    fun onStateUpdated(itemsBySource: Map<NewsSourceId, List<NewsItem>>) {
        latestItemsBySource = itemsBySource
        refresh()
    }

    private fun showNewsList() {
        binding.newsListContainer.visibility = View.VISIBLE
        binding.droughtMonitorContainer.visibility = View.GONE
        binding.stocksContainer.visibility = View.GONE
        binding.wyzeContainer.visibility = View.GONE
        refresh()
    }

    /** Loads the map once on first successful visit to this sub-tab, not on every selection -
     *  it's a slow-changing daily map, not something that needs a fresh network fetch each
     *  time. A failed load retries on the next visit (and immediately on network recovery, see
     *  init) rather than being stuck showing the failure forever. */
    private fun showDroughtMonitor() {
        binding.newsListContainer.visibility = View.GONE
        binding.droughtMonitorContainer.visibility = View.VISIBLE
        binding.stocksContainer.visibility = View.GONE
        binding.wyzeContainer.visibility = View.GONE
        if (droughtState != WebViewLoadState.LOADED) loadDroughtMonitor()
    }

    /** Loads the widget once on first successful visit, same rationale as [showDroughtMonitor].
     *  The widget self-refreshes its quotes over its own websocket once loaded, so a reload
     *  isn't needed after that. */
    private fun showStocks() {
        binding.newsListContainer.visibility = View.GONE
        binding.droughtMonitorContainer.visibility = View.GONE
        binding.stocksContainer.visibility = View.VISIBLE
        binding.wyzeContainer.visibility = View.GONE
        if (stocksState != WebViewLoadState.LOADED) loadStocks()
    }

    /** Loads the Wyze portal once on first successful visit, same rationale as
     *  [showDroughtMonitor]. The camera portal keeps its own session alive once logged in, so a
     *  reload isn't needed on every revisit - only after a failure or a network recovery. */
    private fun showWyze() {
        binding.newsListContainer.visibility = View.GONE
        binding.droughtMonitorContainer.visibility = View.GONE
        binding.stocksContainer.visibility = View.GONE
        binding.wyzeContainer.visibility = View.VISIBLE
        if (wyzeState != WebViewLoadState.LOADED) loadWyze()
    }

    private fun loadDroughtMonitor() {
        binding.droughtErrorText.visibility = View.GONE
        binding.droughtMonitorWebView.visibility = View.VISIBLE
        binding.droughtMonitorWebView.loadUrl(DROUGHT_MONITOR_URL)
    }

    private fun loadStocks() {
        binding.stocksErrorText.visibility = View.GONE
        binding.stocksWebView.visibility = View.VISIBLE
        binding.stocksWebView.loadDataWithBaseURL(
            "https://s3.tradingview.com/",
            STOCKS_WIDGET_HTML,
            "text/html",
            "utf-8",
            null
        )
    }

    private fun loadWyze() {
        wyzeLoginSubmitted = false
        binding.wyzeErrorText.visibility = View.GONE
        binding.wyzeWebView.visibility = View.VISIBLE
        binding.wyzeWebView.loadUrl(BuildConfig.WYZE_LOGIN)
    }

    /** Drives the two-step Wyze sign-in: fill+submit the login form once it finishes loading,
     *  then - if login succeeds but leaves the WebView parked on the auth domain instead of
     *  auto-redirecting - explicitly open the camera page as the second step. */
    private fun handleWyzePageFinished(url: String?) {
        val currentUrl = url ?: return
        when {
            currentUrl.startsWith(BuildConfig.WYZE_LOGIN) && !wyzeLoginSubmitted -> {
                wyzeLoginSubmitted = true
                binding.wyzeWebView.evaluateJavascript(wyzeAutofillScript(), null)
            }
            wyzeLoginSubmitted && currentUrl.contains("auth.wyze.com") -> {
                binding.wyzeWebView.loadUrl(BuildConfig.WYZE_CAMERAS)
            }
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

    /** Builds the JS injected into the Wyze login page to fill and submit the sign-in form.
     *  Sets values through the native setter rather than the plain `.value` property - Wyze's
     *  login page is a React app, and React intercepts the `value` property setter to keep its
     *  own state in sync, so a plain assignment gets silently overwritten back to empty. */
    private fun wyzeAutofillScript(): String {
        val email = JSONObject.quote(BuildConfig.WYZE_USER)
        val password = JSONObject.quote(BuildConfig.WYZE_PWD)
        return """
            (function() {
                function setNativeValue(el, value) {
                    if (!el) return;
                    var proto = Object.getPrototypeOf(el);
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                    setter.call(el, value);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                var emailField = document.querySelector('input[type="email"], input[name="email"], #email');
                var passwordField = document.querySelector('input[type="password"], input[name="password"], #password');
                setNativeValue(emailField, $email);
                setNativeValue(passwordField, $password);
                var submitButton = document.querySelector('button[type="submit"]');
                if (submitButton) submitButton.click();
            })();
        """.trimIndent()
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
                "dateRange": "1M",
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
