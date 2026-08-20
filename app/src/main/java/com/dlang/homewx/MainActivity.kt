package com.dlang.homewx

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.dlang.homewx.news.NewsItem
import com.dlang.homewx.power.ScreenPowerController
import com.dlang.homewx.service.HomeWxMonitorService
import com.dlang.homewx.settings.AppSettings
import com.dlang.homewx.settings.SettingsActivity
import com.dlang.homewx.state.AppState
import com.dlang.homewx.ui.ArticlePanel
import com.dlang.homewx.ui.ForecastPanel
import com.dlang.homewx.ui.NewsPanel
import com.dlang.homewx.ui.SensorAdapter
import com.dlang.homewx.ui.SensorChartPanel
import com.dlang.homewx.ui.SensorGraphsPanel
import com.dlang.homewx.ui.weatherBackgroundRes
import com.dlang.homewx.ui.weatherIconRes
import com.dlang.homewx.weather.DailyExtreme
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.HomeLocation
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.isSameDay
import com.dlang.homewx.weather.startOfDay
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
    private lateinit var newsPanel: NewsPanel
    private lateinit var sensorChartPanel: SensorChartPanel
    private lateinit var articlePanel: ArticlePanel
    private lateinit var forecastPanel: ForecastPanel
    private lateinit var sensorGraphsPanel: SensorGraphsPanel
    private val sensorHistoryStore by lazy { SensorHistoryStore(applicationContext) }
    private val weatherMetricsHistoryStore by lazy { WeatherMetricsHistoryStore(applicationContext) }
    private val dailySnapshotStore by lazy { DailySnapshotStore(applicationContext) }

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
    /** Tracks the previous tick's light mode so a QUIET->ACTIVE transition can be detected in [observeState]. */
    private var lastLightMode: LightMode? = null
    /** Running while auto-cycling tabs after a light-triggered wake; cancelled on any user tab tap. */
    private var autoCycleJob: Job? = null

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
                    // Past is to the left, future to the right: swiping left reveals what's
                    // further right (the future), swiping right reveals what's further left (the past).
                    if (deltaX < 0) goToNewerDay() else goToOlderDay()
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

        newsPanel = NewsPanel(binding.infoPanelContainer, onArticleClick = ::showNewsArticle)
        sensorChartPanel = SensorChartPanel(binding.infoPanelContainer)
        articlePanel = ArticlePanel(binding.infoPanelContainer, onBack = ::closeArticle)
        forecastPanel = ForecastPanel(binding.infoPanelContainer)
        sensorGraphsPanel = SensorGraphsPanel(binding.infoPanelContainer)

        binding.infoPanelTabBar.newsTabButton.setOnClickListener { selectTab(InfoPanelView.NEWS) }
        binding.infoPanelTabBar.forecastTabButton.setOnClickListener { selectTab(InfoPanelView.FORECAST) }
        binding.infoPanelTabBar.sensorGraphsTabButton.setOnClickListener { selectTab(InfoPanelView.SENSOR_GRAPHS) }
        showInfoPanel(InfoPanelView.NEWS)

        HomeWxMonitorService.start(this)

        observeState()
        startClock()
    }

    override fun onResume() {
        super.onResume()
        binding.weatherBackgroundScrim.alpha = AppSettings.getBackgroundDarkenPercent(this) / 100f
        screenPowerController.refresh()
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

    private fun showNewsArticle(item: NewsItem) {
        activeSensorHistoryId = null
        showInfoPanel(InfoPanelView.ARTICLE)
        articlePanel.load(item)
    }

    private fun closeArticle() {
        articlePanel.close()
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
                handleLightModeTransition(state.lightMode)
                binding.currentLuxText.text = state.currentLux?.let { "${it.roundToInt()} lux" } ?: "-- lux"
                sensorAdapter.submit(visibleSensors(state.sensors))
                sensorsUpdatedAtMillis = state.sensorsUpdatedAtMillis
                updateSensorsTitle()
                newsPanel.onStateUpdated(state.newsItemsBySource)
                latestForecast = state.weatherForecast
                refreshOpenPanelIfNeeded()

                if (viewingDayOffset == 0) {
                    bindLiveWeather(state)
                }

                val error = state.lastError
                binding.sensorErrorText.text = error?.let { "Sensor error: $it" }
                binding.sensorErrorText.visibility = if (error != null) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Forces the info panel to News and starts the 5-minute tab auto-cycle whenever the room
     * light wakes the tablet from QUIET; stops the cycle on the way back to QUIET so a later
     * wake always starts a fresh rotation instead of resuming a stale one.
     */
    private fun handleLightModeTransition(newMode: LightMode) {
        val previousMode = lastLightMode
        lastLightMode = newMode
        if (previousMode == LightMode.QUIET && newMode == LightMode.ACTIVE) {
            selectTab(InfoPanelView.NEWS, isUserAction = false)
            startAutoCycle()
        } else if (newMode == LightMode.QUIET) {
            stopAutoCycle()
        }
    }

    private fun startAutoCycle() {
        autoCycleJob?.cancel()
        autoCycleJob = lifecycleScope.launch {
            var index = 0
            while (true) {
                delay(AUTO_CYCLE_INTERVAL_MS)
                index = (index + 1) % AUTO_CYCLE_TABS.size
                selectTab(AUTO_CYCLE_TABS[index], isUserAction = false)
            }
        }
    }

    private fun stopAutoCycle() {
        autoCycleJob?.cancel()
        autoCycleJob = null
    }

    /** Re-renders whichever chart-based panel is currently open, so its data doesn't go
     *  stale while left visible. News/Forecast already self-refresh via [observeState] above. */
    private fun refreshOpenPanelIfNeeded() {
        when (currentInfoPanel) {
            InfoPanelView.SENSOR_GRAPHS -> refreshSensorGraphs()
            InfoPanelView.SENSOR_CHART -> activeSensorHistoryId?.let { refreshSensorChart(it) }
            InfoPanelView.FORECAST -> refreshForecastPanel()
            else -> Unit
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
        binding.windHighValueText.text = entry.windMaxMph?.roundToInt()?.let { "$it mph" } ?: "--"
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
        newsPanel.root.visibility = if (panel == InfoPanelView.NEWS) View.VISIBLE else View.GONE
        sensorChartPanel.root.visibility = if (panel == InfoPanelView.SENSOR_CHART) View.VISIBLE else View.GONE
        articlePanel.root.visibility = if (panel == InfoPanelView.ARTICLE) View.VISIBLE else View.GONE
        forecastPanel.root.visibility = if (panel == InfoPanelView.FORECAST) View.VISIBLE else View.GONE
        sensorGraphsPanel.root.visibility = if (panel == InfoPanelView.SENSOR_GRAPHS) View.VISIBLE else View.GONE
        updateTabSelection(panel)
    }

    /** Tints whichever of the three tab bar icons matches [panel]; SENSOR_CHART/ARTICLE aren't
     *  tabs, so none of the three show selected while either of those is showing. */
    private fun updateTabSelection(panel: InfoPanelView) {
        val selectedColor = getColor(R.color.accent_cool)
        val unselectedColor = getColor(R.color.text_secondary)
        binding.infoPanelTabBar.newsTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.NEWS) selectedColor else unselectedColor)
        binding.infoPanelTabBar.forecastTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.FORECAST) selectedColor else unselectedColor)
        binding.infoPanelTabBar.sensorGraphsTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.SENSOR_GRAPHS) selectedColor else unselectedColor)
    }

    /** Switches the info panel to [panel]. [isUserAction] is false when the switch comes from
     *  the auto-cycle timer rather than an actual tap, so it doesn't cancel its own cycle. */
    private fun selectTab(panel: InfoPanelView, isUserAction: Boolean = true) {
        if (isUserAction) stopAutoCycle()
        activeSensorHistoryId = null
        showInfoPanel(panel)
        when (panel) {
            InfoPanelView.SENSOR_GRAPHS -> refreshSensorGraphs()
            InfoPanelView.FORECAST -> refreshForecastPanel()
            else -> Unit
        }
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
        sensorChartPanel.setTitle("${reading.roomName} — temperature")
        refreshSensorChart(reading.id)
    }

    private fun refreshSensorChart(sensorId: String) {
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            val points = withContext(Dispatchers.IO) {
                sensorHistoryStore.getHistorySince(sensorId, sinceMillis)
                    .mapNotNull { point -> point.tempF?.let { point.timestampMillis to it } }
            }
            // The sensor may no longer be the active one if the user already switched to a
            // different sensor (or closed the chart) before this returned.
            if (activeSensorHistoryId == sensorId) {
                sensorChartPanel.render(points)
            }
        }
    }

    /** Forecast panel's "Past" range shows the same recorded weather-metrics history the old
     *  top-level graphs tab used to - fetched here regardless of which of its 3 sub-tabs is
     *  currently showing, same as [latestForecast] already is. */
    private fun refreshForecastPanel() {
        val forecast = latestForecast ?: return
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            val pastPoints = withContext(Dispatchers.IO) { weatherMetricsHistoryStore.getHistorySince(sinceMillis) }
            if (currentInfoPanel != InfoPanelView.FORECAST) return@launch
            forecastPanel.render(forecast, pastPoints)
        }
    }

    /** One temperature chart per sensor currently shown in the SENSORS list (i.e. not hidden
     *  via settings) - mirrors [visibleSensors]'s filtering so the graphs match the list. */
    private fun refreshSensorGraphs() {
        val sensors = visibleSensors(AppState.uiState.value.sensors)
        sensorGraphsPanel.setSensors(sensors)
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            sensors.forEach { sensor ->
                val points = withContext(Dispatchers.IO) {
                    sensorHistoryStore.getHistorySince(sensor.id, sinceMillis)
                        .mapNotNull { point -> point.tempF?.let { point.timestampMillis to it } }
                }
                if (currentInfoPanel != InfoPanelView.SENSOR_GRAPHS) return@launch
                sensorGraphsPanel.render(sensor.id, points)
            }
        }
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
        private const val AUTO_CYCLE_INTERVAL_MS = 5 * 60 * 1000L
        private val AUTO_CYCLE_TABS = listOf(
            InfoPanelView.NEWS, InfoPanelView.FORECAST, InfoPanelView.SENSOR_GRAPHS
        )
    }
}

/** Which of the mutually-exclusive views is showing in the info panel. NEWS/FORECAST/
 *  SENSOR_GRAPHS are the three tab bar destinations; SENSOR_CHART/ARTICLE are reached other
 *  ways (tapping a sensor row / a news item) and leave the tab bar showing nothing selected. */
private enum class InfoPanelView { NEWS, SENSOR_CHART, ARTICLE, FORECAST, SENSOR_GRAPHS }
