package com.dlang.homewx

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dlang.homewx.data.DailySnapshot
import com.dlang.homewx.data.DailySnapshotStore
import com.dlang.homewx.data.SensorHistoryStore
import com.dlang.homewx.data.WeatherMetricsHistoryStore
import com.dlang.homewx.databinding.ActivityMainBinding
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.model.SensorReading
import com.dlang.homewx.model.UiState
import com.dlang.homewx.news.LoggingWebViewClient
import com.dlang.homewx.news.NewsItem
import com.dlang.homewx.news.NewsSourceId
import com.dlang.homewx.power.ScreenPowerController
import com.dlang.homewx.service.HomeWxMonitorService
import com.dlang.homewx.settings.AppSettings
import com.dlang.homewx.settings.SettingsActivity
import com.dlang.homewx.state.AppState
import com.dlang.homewx.ui.NewsAdapter
import com.dlang.homewx.ui.SensorAdapter
import com.dlang.homewx.ui.weatherBackgroundRes
import com.dlang.homewx.ui.weatherIconRes
import com.dlang.homewx.weather.DailyExtreme
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.HomeLocation
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.isSameDay
import com.dlang.homewx.weather.startOfDay
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var screenPowerController: ScreenPowerController
    private val sensorAdapter = SensorAdapter(onSensorClick = ::showSensorHistory)
    private val newsAdapter = NewsAdapter(onItemClick = ::showNewsArticle)
    private val sensorHistoryStore by lazy { SensorHistoryStore(applicationContext) }
    private val weatherMetricsHistoryStore by lazy { WeatherMetricsHistoryStore(applicationContext) }
    private val dailySnapshotStore by lazy { DailySnapshotStore(applicationContext) }

    private var selectedNewsSource = NewsSourceId.values().first()

    private val forecastDayFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())
    private val weatherDateTimeFormat = SimpleDateFormat("dd MMM, EEE hh:mm a", Locale.getDefault())
    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val historicalDayFormat = SimpleDateFormat("dd MMM, EEE", Locale.getDefault())

    private var latestForecast: WeatherForecast? = null
    private var sensorsUpdatedAtMillis: Long? = null
    private var currentInfoPanel = InfoPanelView.NEWS
    /** Room id whose strip chart is currently showing, only meaningful while currentInfoPanel == SENSOR_CHART. */
    private var activeSensorHistoryId: String? = null
    /**
     * 0 = today (live), negative = that many days in the past (a frozen [DailySnapshot]),
     * positive = that many days into the forecast (a [DailyForecastEntry]).
     */
    private var viewingDayOffset = 0
    /** True while a tap-to-wake override is forcing the screen ACTIVE despite the light sensor saying QUIET. */
    private var wakeOverrideActive = false
    private var wakeOverrideJob: Job? = null

    private val weatherGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showForecastDialog()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val startX = e1?.x ?: return false
                val deltaX = e2.x - startX
                val deltaY = e2.y - e1.y
                if (abs(deltaX) > abs(deltaY) && abs(deltaX) > SWIPE_THRESHOLD_PX) {
                    if (deltaX < 0) goToOlderDay() else goToNewerDay()
                    return true
                }
                return false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ enforces edge-to-edge, so content draws behind the status/nav
        // bars unless we pad for it ourselves; android:fitsSystemWindows is ignored.
        // ConstraintLayout resolves "parent" anchors/guideline percentages against the
        // padded content area, so padding the root keeps every panel clear of the
        // status/nav bars without needing to special-case per-panel containers.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsetPadding(binding.root)

        screenPowerController = ScreenPowerController(this)
        binding.sensorRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sensorRecyclerView.adapter = sensorAdapter
        binding.weatherBackgroundImage.setOnTouchListener { _, event -> weatherGestureDetector.onTouchEvent(event) }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.weatherGraphsButton.setOnClickListener { toggleWeatherGraphs() }
        binding.articleBackButton.setOnClickListener { closeArticle() }
        setUpNewsTabs()
        setUpStripChart()
        setUpWeatherGraphs()
        setUpArticleWebView()

        HomeWxMonitorService.start(this)

        observeState()
        startClock()
    }

    override fun onResume() {
        super.onResume()
        binding.weatherBackgroundScrim.alpha = AppSettings.getBackgroundDarkenPercent(this) / 100f
        sensorAdapter.submit(visibleSensors(AppState.uiState.value.sensors))
    }

    /** A tap anywhere on screen while QUIET wakes the display and holds it ACTIVE for a few minutes. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (AppState.uiState.value.lightMode == LightMode.QUIET) {
            startWakeOverride()
        }
    }

    private fun startWakeOverride() {
        wakeOverrideActive = true
        screenPowerController.apply(LightMode.ACTIVE)
        wakeOverrideJob?.cancel()
        wakeOverrideJob = lifecycleScope.launch {
            delay(WAKE_OVERRIDE_DURATION_MS)
            wakeOverrideActive = false
            screenPowerController.apply(AppState.uiState.value.lightMode)
        }
    }

    private fun setUpNewsTabs() {
        binding.newsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.newsRecyclerView.adapter = newsAdapter

        binding.newsTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedNewsSource = tab.tag as NewsSourceId
                refreshNewsList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        NewsSourceId.values().forEach { source ->
            binding.newsTabLayout.addTab(binding.newsTabLayout.newTab().setText(source.label).apply { tag = source })
        }
    }

    private fun refreshNewsList() {
        newsAdapter.submit(AppState.uiState.value.newsItemsBySource[selectedNewsSource].orEmpty())
    }

    private fun setUpArticleWebView() {
        binding.articleWebView.settings.javaScriptEnabled = true
        // News sites' JS frameworks commonly assume localStorage/sessionStorage is available
        // and error out ("Something went wrong") when it's not - WebView disables it by default.
        binding.articleWebView.settings.domStorageEnabled = true
        // Without a client, the WebView hands off navigation to an external browser app
        // instead of keeping the tapped story inside this same panel.
        binding.articleWebView.webViewClient = LoggingWebViewClient(this)
    }

    private fun showNewsArticle(item: NewsItem) {
        activeSensorHistoryId = null
        showInfoPanel(InfoPanelView.ARTICLE)
        binding.articleTitleText.text = item.title
        binding.articleWebView.loadUrl(item.link)
    }

    private fun closeArticle() {
        binding.articleWebView.stopLoading()
        binding.articleWebView.loadUrl("about:blank")
        showInfoPanel(InfoPanelView.NEWS)
    }

    private fun visibleSensors(sensors: List<SensorReading>): List<SensorReading> {
        val hiddenIds = AppSettings.getHiddenSensorIds(this)
        return sensors.filter { it.id !in hiddenIds }
    }

    /** Pads [view] by the system bars on all four sides, added on top of its existing padding. */
    private fun applySystemBarInsetPadding(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
            insets
        }
    }

    // Not gated to STARTED: the light-triggered "wake the screen" action must
    // apply its window flags even while the activity is merely STOPPED
    // (screen off but not destroyed), otherwise the tablet never wakes back up.
    private fun observeState() {
        lifecycleScope.launch {
            AppState.uiState.collect { state ->
                if (!wakeOverrideActive) {
                    screenPowerController.apply(state.lightMode)
                }
                binding.currentLuxText.text = state.currentLux?.let { "${it.roundToInt()} lux" } ?: "-- lux"
                sensorAdapter.submit(visibleSensors(state.sensors))
                sensorsUpdatedAtMillis = state.sensorsUpdatedAtMillis
                updateSensorsTitle()
                refreshNewsList()
                latestForecast = state.weatherForecast

                if (viewingDayOffset == 0) {
                    bindLiveWeather(state)
                }

                val error = state.lastError
                binding.sensorErrorText.text = error?.let { "Sensor error: $it" }
                binding.sensorErrorText.visibility = if (error != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun bindLiveWeather(state: UiState) {
        val conditions = state.currentWeather
        binding.currentTempText.text = state.weatherError
            ?: conditions?.temperatureF?.roundToInt()?.let { "$it°F" }
            ?: getString(R.string.weather_placeholder)
        binding.tempTrendText.text = formatSignedDelta(state.tempTrendNextHourF, "°F")

        if (conditions != null) {
            binding.weatherIcon.setImageResource(weatherIconRes(conditions.iconKey))
            binding.weatherBackgroundImage.setImageResource(weatherBackgroundRes(conditions.iconKey, conditions.windSpeedMph))
            binding.conditionValueText.text = conditions.conditionText
            binding.humidityValueText.text = conditions.humidityPct?.roundToInt()?.let { "$it%" } ?: "--"
            binding.windSpeedValueText.text = conditions.windSpeedMph?.roundToInt()?.let { "$it mph" } ?: "--"
            binding.windDirectionValueText.text = formatWindDirection(conditions.windDirectionDeg)
            binding.precipitationValueText.text = conditions.precipitationIn?.let { "%.2f in".format(it) } ?: "--"
            binding.pressureValueText.text = formatPressure(conditions.pressureInHg, state.pressureTrend6hInHg)
        }

        val extremes = state.dailyExtremes
        binding.tempHighValueText.text = formatExtreme(extremes.tempHighF, "°F")
        binding.tempLowValueText.text = formatExtreme(extremes.tempLowF, "°F")
        binding.windHighValueText.text = formatExtreme(extremes.windHighMph, " mph")
        binding.windLowValueText.text = formatExtreme(extremes.windLowMph, " mph")

        val historicalAverage = state.historicalTempAverage
        binding.lyMaxValueText.text = historicalAverage?.avgHighF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.lyMinValueText.text = historicalAverage?.avgLowF?.roundToInt()?.let { "$it°F" } ?: "--"
    }

    private fun bindSnapshotWeather(snapshot: DailySnapshot) {
        binding.weatherIcon.setImageResource(weatherIconRes(snapshot.iconKey ?: "wx_sun_44d"))
        binding.weatherBackgroundImage.setImageResource(weatherBackgroundRes(snapshot.iconKey ?: "wx_sun_44d", snapshot.windSpeedMph))
        binding.currentTempText.text = snapshot.tempF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.tempTrendText.text = ""
        binding.conditionValueText.text = snapshot.conditionText ?: "--"
        binding.humidityValueText.text = snapshot.humidityPct?.roundToInt()?.let { "$it%" } ?: "--"
        binding.windSpeedValueText.text = snapshot.windSpeedMph?.roundToInt()?.let { "$it mph" } ?: "--"
        binding.windDirectionValueText.text = formatWindDirection(snapshot.windDirectionDeg)
        binding.precipitationValueText.text = snapshot.precipitationIn?.let { "%.2f in".format(it) } ?: "--"
        binding.pressureValueText.text = snapshot.pressureInHg?.let { "%.2f in".format(it) } ?: "--"
        binding.tempHighValueText.text = formatExtreme(toExtreme(snapshot.tempHighF, snapshot.tempHighAtMillis), "°F")
        binding.tempLowValueText.text = formatExtreme(toExtreme(snapshot.tempLowF, snapshot.tempLowAtMillis), "°F")
        binding.windHighValueText.text = formatExtreme(toExtreme(snapshot.windHighMph, snapshot.windHighAtMillis), " mph")
        binding.windLowValueText.text = formatExtreme(toExtreme(snapshot.windLowMph, snapshot.windLowAtMillis), " mph")
        binding.lyMaxValueText.text = snapshot.lyAvgHighF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.lyMinValueText.text = snapshot.lyAvgLowF?.roundToInt()?.let { "$it°F" } ?: "--"
    }

    /** Only the weather provider's own forecast fields are used here - no sensor override, unlike [bindLiveWeather]. */
    private fun bindForecastWeather(entry: DailyForecastEntry) {
        binding.weatherIcon.setImageResource(weatherIconRes(entry.iconKey))
        binding.weatherBackgroundImage.setImageResource(weatherBackgroundRes(entry.iconKey, windSpeedMph = null))
        binding.currentTempText.text = entry.highF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.tempTrendText.text = entry.lowF?.roundToInt()?.let { "Low $it°F" } ?: ""
        binding.conditionValueText.text = entry.conditionText
        binding.humidityValueText.text = "--"
        binding.windSpeedValueText.text = "--"
        binding.windDirectionValueText.text = "--"
        binding.precipitationValueText.text = entry.precipitationChancePct?.let { "$it% chance" } ?: "--"
        binding.pressureValueText.text = "--"
        binding.tempHighValueText.text = entry.highF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.tempLowValueText.text = entry.lowF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.windHighValueText.text = "--"
        binding.windLowValueText.text = "--"
        binding.lyMaxValueText.text = "--"
        binding.lyMinValueText.text = "--"
    }

    private fun toExtreme(value: Double?, atMillis: Long?): DailyExtreme? =
        if (value != null && atMillis != null) DailyExtreme(value, atMillis) else null

    private fun forecastEntryForOffset(offsetDays: Int): DailyForecastEntry? {
        val targetDayMillis = startOfDay(System.currentTimeMillis()) + offsetDays * DAY_MILLIS
        return latestForecast?.daily?.firstOrNull { isSameDay(it.dateMillis, targetDayMillis) }
    }

    private fun goToOlderDay() {
        viewingDayOffset -= 1
        loadViewedDay()
    }

    private fun goToNewerDay() {
        val nextOffset = viewingDayOffset + 1
        if (nextOffset > 0 && forecastEntryForOffset(nextOffset) == null) {
            Toast.makeText(this, "No forecast data available", Toast.LENGTH_SHORT).show()
            return
        }
        viewingDayOffset = nextOffset
        loadViewedDay()
    }

    private fun loadViewedDay() {
        if (viewingDayOffset == 0) {
            bindLiveWeather(AppState.uiState.value)
            binding.weatherDateTimeText.text = weatherDateTimeFormat.format(Date())
            return
        }
        val dayMillis = startOfDay(System.currentTimeMillis()) + viewingDayOffset * DAY_MILLIS
        if (viewingDayOffset > 0) {
            val entry = forecastEntryForOffset(viewingDayOffset)
            if (entry == null) {
                Toast.makeText(this, "No forecast data available", Toast.LENGTH_SHORT).show()
                viewingDayOffset -= 1
                return
            }
            bindForecastWeather(entry)
            binding.weatherDateTimeText.text = "${historicalDayFormat.format(Date(dayMillis))} (forecast)"
            return
        }
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { dailySnapshotStore.getSnapshot(dayMillis) }
            if (snapshot == null) {
                // Nothing saved that far back yet - bounce back to the nearest day that has data.
                Toast.makeText(this@MainActivity, "No saved data for that day yet", Toast.LENGTH_SHORT).show()
                viewingDayOffset += 1
                return@launch
            }
            bindSnapshotWeather(snapshot)
            binding.weatherDateTimeText.text = historicalDayFormat.format(Date(dayMillis))
        }
    }

    private fun formatSignedDelta(delta: Double?, unit: String): String =
        delta?.roundToInt()?.let { "%+d%s".format(it, unit) } ?: ""

    private fun formatWindDirection(degrees: Double?): String {
        if (degrees == null) return "--"
        val directions = listOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
        val index = ((degrees / 22.5) + 0.5).toInt().mod(directions.size)
        return "${directions[index]} (${degrees.roundToInt()}°)"
    }

    private fun formatPressure(pressureInHg: Double?, trend6hInHg: Double?): CharSequence {
        val pressure = pressureInHg?.let { "%.2f in".format(it) } ?: "--"
        val trend = trend6hInHg?.let { " (%+.2f)".format(it) }.orEmpty()
        return shrinkParenthetical("$pressure$trend")
    }

    private fun formatExtreme(extreme: DailyExtreme?, unit: String): CharSequence {
        if (extreme == null) return "--"
        val hour = hourOnlyFormat.format(Date(extreme.atMillis))
        return shrinkParenthetical("${extreme.value.roundToInt()}$unit ($hour)")
    }

    /** Renders a trailing "(...)" annotation at 75% of the surrounding text size. */
    private fun shrinkParenthetical(text: String): CharSequence {
        val start = text.indexOf('(')
        val end = text.indexOf(')', start.coerceAtLeast(0))
        if (start == -1 || end == -1) return text
        return SpannableString(text).apply {
            setSpan(RelativeSizeSpan(0.75f), start, end + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showInfoPanel(panel: InfoPanelView) {
        currentInfoPanel = panel
        binding.newsGroup.visibility = if (panel == InfoPanelView.NEWS) View.VISIBLE else View.GONE
        binding.stripChartGroup.visibility = if (panel == InfoPanelView.SENSOR_CHART) View.VISIBLE else View.GONE
        binding.weatherGraphsPanel.visibility = if (panel == InfoPanelView.WEATHER_GRAPHS) View.VISIBLE else View.GONE
        binding.articleGroup.visibility = if (panel == InfoPanelView.ARTICLE) View.VISIBLE else View.GONE
    }

    private fun showSensorHistory(reading: SensorReading) {
        if (currentInfoPanel == InfoPanelView.SENSOR_CHART && activeSensorHistoryId == reading.id) {
            // Tapping the same row again closes the chart and returns to the news panel.
            activeSensorHistoryId = null
            showInfoPanel(InfoPanelView.NEWS)
            return
        }
        activeSensorHistoryId = reading.id
        showInfoPanel(InfoPanelView.SENSOR_CHART)
        binding.stripChartTitleText.text = "${reading.roomName} — temperature"
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            val points = withContext(Dispatchers.IO) {
                sensorHistoryStore.getHistorySince(reading.id, sinceMillis)
                    .mapNotNull { point -> point.tempF?.let { point.timestampMillis to it } }
            }
            // The reading tapped may no longer be the active one if the user already
            // switched to a different sensor (or closed the chart) before this returned.
            if (activeSensorHistoryId == reading.id) {
                renderLineChart(binding.stripChartView, points, R.color.accent_warm)
            }
        }
    }

    private fun toggleWeatherGraphs() {
        if (currentInfoPanel == InfoPanelView.WEATHER_GRAPHS) {
            showInfoPanel(InfoPanelView.NEWS)
            return
        }
        activeSensorHistoryId = null
        showInfoPanel(InfoPanelView.WEATHER_GRAPHS)
        refreshWeatherGraphs()
    }

    private fun refreshWeatherGraphs() {
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            val points = withContext(Dispatchers.IO) { weatherMetricsHistoryStore.getHistorySince(sinceMillis) }
            if (currentInfoPanel != InfoPanelView.WEATHER_GRAPHS) return@launch
            renderLineChart(
                binding.windSpeedChartView,
                points.mapNotNull { p -> p.windSpeedMph?.let { p.timestampMillis to it } },
                R.color.accent_cool
            )
            renderLineChart(
                binding.precipitationChartView,
                points.mapNotNull { p -> p.precipitationIn?.let { p.timestampMillis to it } },
                R.color.accent_cool
            )
            renderLineChart(
                binding.pressureChartView,
                points.mapNotNull { p -> p.pressureInHg?.let { p.timestampMillis to it } },
                R.color.accent_cool
            )
        }
    }

    private fun setUpStripChart() {
        configureLineChart(binding.stripChartView, description = null)
        binding.stripChartView.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = "${value.toInt()}°"
        }
    }

    private fun setUpWeatherGraphs() {
        configureLineChart(binding.windSpeedChartView, getString(R.string.weather_graph_wind_speed))
        configureLineChart(binding.precipitationChartView, getString(R.string.weather_graph_precipitation))
        configureLineChart(binding.pressureChartView, getString(R.string.weather_graph_pressure))
    }

    /** Shared axis/touch setup for every [LineChart] in the info panel (sensor history + weather graphs). */
    private fun configureLineChart(chart: LineChart, description: String?) {
        val axisTextColor = ContextCompat.getColor(this, R.color.text_secondary)
        val gridLineColor = ContextCompat.getColor(this, R.color.divider)

        chart.legend.isEnabled = false
        chart.setNoDataText(getString(R.string.strip_chart_no_data))
        chart.setNoDataTextColor(axisTextColor)
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        if (description != null) {
            chart.description.apply {
                isEnabled = true
                text = description
                textColor = axisTextColor
                textSize = 12f
            }
        } else {
            chart.description.isEnabled = false
        }

        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            textColor = axisTextColor
            this.gridColor = gridLineColor
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = axisTextColor
            this.gridColor = gridLineColor
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    hourOnlyFormat.format(Date(value.toLong() * 1000L))
            }
        }
    }

    private fun renderLineChart(chart: LineChart, points: List<Pair<Long, Double>>, colorRes: Int) {
        if (points.size < 2) {
            chart.clear()
            return
        }
        val entries = points.map { (timeMillis, value) -> Entry(timeMillis / 1000f, value.toFloat()) }
        val dataSet = LineDataSet(entries, null).apply {
            color = ContextCompat.getColor(this@MainActivity, colorRes)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun showForecastDialog() {
        val forecast = latestForecast
        val message = if (forecast == null || forecast.daily.isEmpty()) {
            "Forecast not available yet."
        } else {
            forecast.daily.joinToString(separator = "\n") { day ->
                val high = day.highF?.roundToInt()?.let { "$it°F" } ?: "--"
                val low = day.lowF?.roundToInt()?.let { "$it°F" } ?: "--"
                val rain = day.precipitationChancePct?.let { " ($it% rain)" }.orEmpty()
                "${forecastDayFormat.format(Date(day.dateMillis))}: $high / $low — ${day.conditionText}$rain"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Forecast for ${HomeLocation.CURRENT.label}")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun startClock() {
        lifecycleScope.launch {
            while (true) {
                if (viewingDayOffset == 0) {
                    binding.weatherDateTimeText.text = weatherDateTimeFormat.format(Date())
                }
                updateSensorsTitle()
                delay(30_000L)
            }
        }
    }

    private fun updateSensorsTitle() {
        val updatedAt = sensorsUpdatedAtMillis
        val suffix = if (updatedAt == null) {
            ""
        } else {
            val elapsedMinutes = (System.currentTimeMillis() - updatedAt) / 60_000L
            if (elapsedMinutes < 1) " (just now)" else " ($elapsedMinutes min ago)"
        }
        binding.sensorsTitleText.text = "SENSORS$suffix"
    }

    companion object {
        private const val SWIPE_THRESHOLD_PX = 80
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val WAKE_OVERRIDE_DURATION_MS = 5 * 60 * 1000L
    }
}

/** Which of the three mutually-exclusive views is showing in the bottom info panel. */
private enum class InfoPanelView { NEWS, SENSOR_CHART, WEATHER_GRAPHS, ARTICLE }
