package com.dlang.homewx.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.util.Log
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
 *  the main frame trivially "succeeds" and the real dependency is a specific sub-resource.
 *
 *  A failed main-frame navigation still gets followed by [onPageFinished] for the WebView's own
 *  native error page - without [loadFailed] to remember that, that later callback would stomp
 *  the FAILED state back to LOADED and reveal the native "Webpage not available" page instead of
 *  [errorText], which is what left Drought/Stocks/Wyze stuck showing that page forever with
 *  nothing left to trigger a retry. */
private fun loadTrackingClient(
    context: Context,
    errorText: TextView,
    isRelevantFailure: (WebResourceRequest) -> Boolean,
    onFinished: (url: String?) -> Unit,
    onFailed: () -> Unit
): LoggingWebViewClient = object : LoggingWebViewClient(context) {
    private var loadFailed = false

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        loadFailed = false
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (loadFailed) return
        errorText.visibility = View.GONE
        view.visibility = View.VISIBLE
        onFinished(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (!isRelevantFailure(request)) return
        loadFailed = true
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

    /** Guards against requesting [BuildConfig.WYZE_CAMERAS] more than once per login attempt, in
     *  case the auth domain fires onPageFinished more than once after submit - reset alongside
     *  [wyzeLoginSubmitted] in [loadWyze]. */
    private var wyzeCamerasRequested = false

    /** Counts [loadWyze] attempts so a login that never actually completes - e.g. the site
     *  bouncing back to its own login page, which combined with [AppState.networkRecovered]
     *  retriggering [loadWyze] on every network blip turned into an unbounded auto-fill/submit
     *  cycle - trips [wyzeLoginBroken] instead of retrying forever. */
    private var wyzeLoginAttempts = 0

    /** Set once [wyzeLoginAttempts] exceeds [MAX_WYZE_LOGIN_ATTEMPTS]; makes [loadWyze] a no-op
     *  (showing [R.string.wyze_login_loop_detected] instead) until the user taps to retry, which
     *  clears this and resets the counter. */
    private var wyzeLoginBroken = false

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
        // Only meaningful once wyzeLoginBroken has tripped and shown R.string.wyze_login_loop_detected -
        // otherwise this text is the plain no-network message with nothing useful to retry here.
        binding.wyzeErrorText.setOnClickListener {
            if (wyzeLoginBroken) {
                Log.d(TAG, "Wyze login breaker manually reset by user tap")
                wyzeLoginBroken = false
                wyzeLoginAttempts = 0
                wyzeState = WebViewLoadState.NOT_LOADED
                loadWyze()
            }
        }

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

    /** Opens the Wyze camera portal in a [FullScreenWebViewDialog] instead of the shared
     *  half-screen [binding.wyzeContainer] the other sub-tabs use - a live camera view is worth
     *  full screen real estate, unlike Drought/Stocks which [FullScreenWebViewDialog] could host
     *  the same way later if that changes. Loads the portal once on first visit same as before -
     *  the camera portal keeps its own session alive once logged in, so a reload isn't needed on
     *  every revisit, only after a failure or a network recovery. On dismiss, falls back to the
     *  first News tab rather than leaving this tab selected over an emptied-out container. */
    private fun showWyze() {
        if (wyzeState != WebViewLoadState.LOADED) loadWyze()
        FullScreenWebViewDialog(
            context = binding.root.context,
            webView = binding.wyzeWebView,
            errorText = binding.wyzeErrorText,
            originalParent = binding.wyzeContainer
        ).apply {
            setOnDismissListener { binding.newsTabLayout.getTabAt(0)?.select() }
            show()
        }
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

    /** Starts (or restarts) a Wyze login attempt, unless [wyzeLoginBroken] has already tripped -
     *  see that field and [MAX_WYZE_LOGIN_ATTEMPTS] for why this is capped instead of unconditional
     *  like [loadDroughtMonitor]/[loadStocks]. This is the single choke point for every caller
     *  (first visit, tab reselect after a failure, [AppState.networkRecovered]), so the cap holds
     *  regardless of what triggered the retry. */
    private fun loadWyze() {
        if (wyzeLoginBroken) {
            showWyzeLoginBrokenError()
            return
        }
        wyzeLoginAttempts++
        Log.d(TAG, "loadWyze: attempt $wyzeLoginAttempts/$MAX_WYZE_LOGIN_ATTEMPTS")
        if (wyzeLoginAttempts > MAX_WYZE_LOGIN_ATTEMPTS) {
            tripWyzeLoginBreaker(
                "Wyze auto-login retried $wyzeLoginAttempts times without completing - stopping to avoid a reload loop"
            )
            return
        }
        wyzeLoginSubmitted = false
        wyzeCamerasRequested = false
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
                Log.d(TAG, "handleWyzePageFinished: login page loaded, submitting: $currentUrl")
                wyzeLoginSubmitted = true
                binding.wyzeWebView.evaluateJavascript(wyzeAutofillScript()) { result ->
                    Log.d(TAG, "wyzeAutofillScript result: $result")
                }
            }
            wyzeLoginSubmitted && !wyzeCamerasRequested && currentUrl.contains("auth.wyze.com") -> {
                Log.d(TAG, "handleWyzePageFinished: auth domain reached, opening cameras page: $currentUrl")
                wyzeCamerasRequested = true
                binding.wyzeWebView.loadUrl(BuildConfig.WYZE_CAMERAS)
            }
            // Reaching the cameras page itself (not just requesting it above) is the actual
            // success signal - reset the counter so occasional legitimate re-logins across a long
            // session (e.g. after real network drops) don't eat into the same budget as a login
            // that's genuinely stuck bouncing back to itself.
            currentUrl.startsWith(BuildConfig.WYZE_CAMERAS) && wyzeLoginAttempts != 0 -> {
                Log.d(TAG, "handleWyzePageFinished: cameras page loaded, login succeeded - resetting attempt counter")
                wyzeLoginAttempts = 0
            }
        }
    }

    /** Logs to logcat, records to the Settings > Errors panel (see [AppState.recordError]) so
     *  it's visible on-device without a debugger attached, and shows [showWyzeLoginBrokenError]
     *  in place of the WebView. */
    private fun tripWyzeLoginBreaker(reason: String) {
        Log.w(TAG, "Wyze auto-login disabled: $reason")
        AppState.recordError("Wyze", reason)
        wyzeLoginBroken = true
        showWyzeLoginBrokenError()
    }

    private fun showWyzeLoginBrokenError() {
        binding.wyzeWebView.visibility = View.GONE
        binding.wyzeErrorText.text = binding.root.context.getString(
            R.string.wyze_login_loop_detected, wyzeLoginAttempts
        )
        binding.wyzeErrorText.visibility = View.VISIBLE
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
     *  own state in sync, so a plain assignment gets silently overwritten back to empty.
     *
     *  Wyze also shows a cookie-consent banner ("Accept" / "Deny Non-Essential") on first load
     *  that sits on top of the form - submitting without dismissing it bounces the page back to
     *  login instead of signing in, so [clickButtonWithText] dismisses it first when present,
     *  giving the page a moment to settle before filling in. Returns a short status string,
     *  logged by the caller, so which path ran is visible without attaching a debugger. */
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
                function clickButtonWithText(text) {
                    var buttons = document.querySelectorAll('button');
                    for (var i = 0; i < buttons.length; i++) {
                        if (buttons[i].textContent.trim().toLowerCase() === text) {
                            buttons[i].click();
                            return true;
                        }
                    }
                    return false;
                }
                function fillAndSubmit() {
                    var emailField = document.querySelector('input[type="email"], input[name="email"], #email');
                    var passwordField = document.querySelector('input[type="password"], input[name="password"], #password');
                    setNativeValue(emailField, $email);
                    setNativeValue(passwordField, $password);
                    var submitButton = document.querySelector('button[type="submit"]');
                    if (submitButton) submitButton.click();
                }
                if (clickButtonWithText('accept')) {
                    setTimeout(fillAndSubmit, 300);
                    return 'dismissing_cookie_banner_then_will_fill';
                }
                fillAndSubmit();
                return 'filled_without_cookie_banner';
            })();
        """.trimIndent()
    }

    companion object {
        private const val TAG = "NewsPanel"

        /** Caps [wyzeLoginAttempts] before [tripWyzeLoginBreaker] gives up - see that field for
         *  why an unbounded retry loop was possible. A couple of retries covers a genuine
         *  transient hiccup; more than that means the login itself isn't completing. */
        private const val MAX_WYZE_LOGIN_ATTEMPTS = 3

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
