package com.dlang.homewx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.dlang.homewx.news.NewsItem
import com.dlang.homewx.power.ScreenPowerController
import com.dlang.homewx.rivers.RiverGaugeSettings
import com.dlang.homewx.data.RiverHistoryStore
import com.dlang.homewx.service.HomeWxMonitorService
import com.dlang.homewx.settings.AppSettings
import com.dlang.homewx.settings.SettingsActivity
import com.dlang.homewx.state.AppState
import com.dlang.homewx.ui.ArticlePanel
import com.dlang.homewx.ui.ForecastPanel
import com.dlang.homewx.ui.NewsPanel
import com.dlang.homewx.ui.RadarPanel
import com.dlang.homewx.ui.RiverGraphsPanel
import com.dlang.homewx.ui.SensorAdapter
import com.dlang.homewx.ui.SensorChartPanel
import com.dlang.homewx.ui.SensorGraphsPanel
import com.dlang.homewx.ui.weatherBackgroundRes
import com.dlang.homewx.ui.weatherIconRes
import com.dlang.homewx.weather.DailyExtreme
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.WeatherSourceConfig
import com.dlang.homewx.weather.WeatherSourceId
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
    // Only needed so NewsPanel's "no data" message can name the connected WiFi network -
    // Android ties real SSID lookups to location permission. Re-render on grant so the
    // message picks up the SSID immediately instead of waiting for the next news poll.
    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) newsPanel.onStateUpdated(AppState.uiState.value.newsItemsBySource)
    }
    private val sensorAdapter = SensorAdapter(onSensorClick = ::showSensorHistory)
    private lateinit var newsPanel: NewsPanel
    private lateinit var sensorChartPanel: SensorChartPanel
    private lateinit var articlePanel: ArticlePanel
    private lateinit var forecastPanel: ForecastPanel
    private lateinit var sensorGraphsPanel: SensorGraphsPanel
    private lateinit var radarPanel: RadarPanel
    private lateinit var riverGraphsPanel: RiverGraphsPanel
    private val sensorHistoryStore by lazy { SensorHistoryStore(applicationContext) }
    private val weatherMetricsHistoryStore by lazy { WeatherMetricsHistoryStore(applicationContext) }
    private val dailySnapshotStore by lazy { DailySnapshotStore(applicationContext) }
    private val riverHistoryStore by lazy { RiverHistoryStore(applicationContext) }

    private val weatherDateTimeFormat = SimpleDateFormat("dd MMM, EEE hh:mm a", Locale.getDefault())
    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val historicalDayFormat = SimpleDateFormat("dd MMM, EEE", Locale.getDefault())

    private var latestForecast: WeatherForecast? = null
    private var sensorsUpdatedAtMillis: Long? = null
    private var currentInfoPanel = InfoPanelView.NEWS
    /** Room id whose strip chart is currently showing, only meaningful while currentInfoPanel == SENSOR_CHART.
     *  Mirrored onto [sensorAdapter] so the tapped row stays highlighted while its chart is open. */
    private var activeSensorHistoryId: String? = null
        set(value) {
            field = value
            sensorAdapter.selectedSensorId = value
        }
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
        radarPanel = RadarPanel(binding.infoPanelContainer, lifecycleScope)
        riverGraphsPanel = RiverGraphsPanel(binding.infoPanelContainer)

        binding.infoPanelTabBar.newsTabButton.setOnClickListener { selectTab(InfoPanelView.NEWS) }
        binding.infoPanelTabBar.forecastTabButton.setOnClickListener { selectTab(InfoPanelView.FORECAST) }
        binding.infoPanelTabBar.sensorGraphsTabButton.setOnClickListener { selectTab(InfoPanelView.SENSOR_GRAPHS) }
        binding.infoPanelTabBar.radarTabButton.setOnClickListener { selectTab(InfoPanelView.RADAR) }
        binding.infoPanelTabBar.riversTabButton.setOnClickListener { selectTab(InfoPanelView.RIVERS) }
        showInfoPanel(InfoPanelView.NEWS)

        HomeWxMonitorService.start(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        observeState()
        startClock()
    }

    override fun onResume() {
        super.onResume()
        binding.weatherBackgroundScrim.alpha = AppSettings.getBackgroundDarkenPercent(this) / 100f
        screenPowerController.refresh()
        sensorAdapter.submit(visibleSensors(AppState.uiState.value.sensors))
        updateRiverTabVisibility()
    }

    /** The Rivers tab only shows once the feature is enabled and gauges are selected in
     *  Settings - both only change while this activity is paused (Settings is a separate
     *  Activity), so re-checking here on every resume is enough, no need to watch AppState. */
    private fun updateRiverTabVisibility() {
        val showRiversTab = RiverGaugeSettings.isEnabled(this) && RiverGaugeSettings.getSelectedGauges(this).isNotEmpty()
        binding.infoPanelTabBar.riversTabButton.visibility = if (showRiversTab) View.VISIBLE else View.GONE
        if (!showRiversTab && currentInfoPanel == InfoPanelView.RIVERS) {
            selectTab(InfoPanelView.NEWS, isUserAction = false)
        }
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
        return sensors
            .filter { it.id !in hiddenIds }
            .map { sensor ->
                val label = AppSettings.getSensorLabel(this, sensor.id)
                if (label != null) sensor.copy(roomName = label) else sensor
            }
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

                binding.infoPanelTabBar.root.setBackgroundColor(
                    getColor(if (state.networkReachable) R.color.bg_panel_bottom else R.color.red)
                )
                updateWeatherDateTimeBackground(state)
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
            InfoPanelView.RIVERS -> refreshRiverGraphs()
            else -> Unit
        }
    }

    private fun bindLiveWeather(state: UiState) {
        val conditions = state.currentWeather
        // A raw exception message (e.g. a connect-timeout string) must never land in this
        // 44sp display - stale data is treated the same as no data rather than shown as if live.
        val isStale = conditions != null &&
            System.currentTimeMillis() - conditions.observedAtMillis > STALE_WEATHER_THRESHOLD_MS
        val liveConditions = conditions.takeUnless { isStale }

        binding.currentTempText.text = liveConditions?.temperatureF?.roundToInt()?.let { "$it°F" }
            ?: getString(if (isStale) R.string.weather_unavailable else R.string.weather_placeholder)
        binding.tempTrendText.text = if (liveConditions != null) formatSignedDelta(state.tempTrendNextHourF, "°F/1hr") else ""
        binding.tempTrend4hText.text = if (liveConditions != null) formatSignedDelta(state.tempTrendNext4HourF, "°F/4hr") else ""

        if (liveConditions != null) {
            binding.weatherIcon.setImageResource(weatherIconRes(liveConditions.iconKey))
            binding.weatherBackgroundImage.setImageResource(weatherBackgroundRes(liveConditions.iconKey, liveConditions.windSpeedMph))
            binding.conditionValueText.text = liveConditions.conditionText
            binding.humidityValueText.text = liveConditions.humidityPct?.roundToInt()?.let { "$it%" } ?: "--"
            binding.windSpeedValueText.text = liveConditions.windSpeedMph?.roundToInt()?.let { "$it mph" } ?: "--"
            binding.windDirectionValueText.text = formatWindDirection(liveConditions.windDirectionDeg)
            binding.precipitationValueText.text = liveConditions.precipitationIn?.let { "%.2f in".format(it) } ?: "--"
            binding.pressureValueText.text = formatPressure(liveConditions.pressureInHg, state.pressureTrend6hInHg)
        } else {
            binding.conditionValueText.text = if (isStale) getString(R.string.weather_unavailable) else "--"
            binding.humidityValueText.text = "--"
            binding.windSpeedValueText.text = "--"
            binding.windDirectionValueText.text = "--"
            binding.precipitationValueText.text = "--"
            binding.pressureValueText.text = "--"
        }

        val extremes = state.dailyExtremes
        binding.tempHighValueText.text = formatExtreme(extremes.tempHighF, "°F")
        binding.tempLowValueText.text = formatExtreme(extremes.tempLowF, "°F")
        binding.windHighValueText.text = formatExtreme(extremes.windHighMph, " mph")
        binding.windLowValueText.text = formatExtreme(extremes.windLowMph, " mph")

        val historicalAverage = state.historicalTempAverage
        updateLastYearLabel()
        binding.lyMaxValueText.text = historicalAverage?.avgHighF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.lyMinValueText.text = historicalAverage?.avgLowF?.roundToInt()?.let { "$it°F" } ?: "--"
    }

    /**
     * The lyMax/lyMinValueText fields mean different things depending on the active weather
     * source: Open-Meteo backs them with last year's real observed average
     * ([com.dlang.homewx.weather.openmeteo.OpenMeteoWeatherProvider.getHistoricalDailyAverage]),
     * while WxData backs them with a 10-30 year NCDC climate normal for the same calendar days
     * ([com.dlang.homewx.weather.wxdata.WxDataWeatherProvider.getHistoricalDailyAverage]) -
     * different statistics, so the label switches to stay honest about which one is showing.
     */
    private fun updateLastYearLabel() {
        val isNormal = WeatherSourceConfig.getActiveSource(this) == WeatherSourceId.WXDATA
        binding.lyMaxLabelText.setText(if (isNormal) R.string.wx_lbl_normal_max else R.string.wx_lbl_ly_max)
        binding.lyMinLabelText.setText(if (isNormal) R.string.wx_lbl_normal_min else R.string.wx_lbl_ly_min)
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
        updateLastYearLabel()
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
            updateWeatherDateTimeBackground()
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
            updateWeatherDateTimeBackground()
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
            updateWeatherDateTimeBackground()
        }
    }

    /** Red overrides the current/forecast/past mode color whenever the device is offline or the
     *  last weather fetch failed, so the title row itself flags the problem instead of only the
     *  (larger, easier-to-miss) tab bar - and instead of dumping the raw error into the weather display. */
    private fun updateWeatherDateTimeBackground(state: UiState = AppState.uiState.value) {
        val networkFailing = !state.networkReachable || state.weatherError != null
        val colorRes = if (networkFailing) {
            R.color.red
        } else when {
            viewingDayOffset > 0 -> R.color.weather_title_bg_forecast
            viewingDayOffset < 0 -> R.color.weather_title_bg_past
            else -> R.color.weather_title_bg_current
        }
        binding.weatherDateTimeText.setBackgroundColor(getColor(colorRes))
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
        radarPanel.root.visibility = if (panel == InfoPanelView.RADAR) View.VISIBLE else View.GONE
        riverGraphsPanel.root.visibility = if (panel == InfoPanelView.RIVERS) View.VISIBLE else View.GONE
        if (panel != InfoPanelView.RADAR) radarPanel.stopAnimation()
        updateTabSelection(panel)
    }

    /** Tints whichever of the five tab bar icons matches [panel]; SENSOR_CHART/ARTICLE aren't
     *  tabs, so none of the five show selected while either of those is showing. */
    private fun updateTabSelection(panel: InfoPanelView) {
        val selectedColor = getColor(R.color.accent_cool)
        val unselectedColor = getColor(R.color.text_secondary)
        binding.infoPanelTabBar.newsTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.NEWS) selectedColor else unselectedColor)
        binding.infoPanelTabBar.forecastTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.FORECAST) selectedColor else unselectedColor)
        binding.infoPanelTabBar.sensorGraphsTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.SENSOR_GRAPHS) selectedColor else unselectedColor)
        binding.infoPanelTabBar.radarTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.RADAR) selectedColor else unselectedColor)
        binding.infoPanelTabBar.riversTabButton.imageTintList =
            ColorStateList.valueOf(if (panel == InfoPanelView.RIVERS) selectedColor else unselectedColor)
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
            InfoPanelView.RADAR -> radarPanel.refresh()
            InfoPanelView.RIVERS -> refreshRiverGraphs()
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
        refreshSensorChart(reading.id)
    }

    private fun refreshSensorChart(sensorId: String) {
        // Live values (and the room name, in case it's since changed in Settings) refresh
        // synchronously from the current state; the history plot needs an async DB read.
        AppState.uiState.value.sensors.firstOrNull { it.id == sensorId }?.let { reading ->
            sensorChartPanel.setSensor(reading.roomName, reading.tempF, reading.humidityPct)
        }
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            val history = withContext(Dispatchers.IO) {
                sensorHistoryStore.getHistorySince(sensorId, sinceMillis)
            }
            // The sensor may no longer be the active one if the user already switched to a
            // different sensor (or closed the chart) before this returned.
            if (activeSensorHistoryId == sensorId) {
                val tempPoints = history.mapNotNull { point -> point.tempF?.let { point.timestampMillis to it } }
                val humidityPoints = history.mapNotNull { point -> point.humidityPct?.let { point.timestampMillis to it } }
                sensorChartPanel.render(tempPoints, humidityPoints)
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
                val history = withContext(Dispatchers.IO) {
                    sensorHistoryStore.getHistorySince(sensor.id, sinceMillis)
                }
                val tempPoints = history.mapNotNull { point -> point.tempF?.let { point.timestampMillis to it } }
                val humidityPoints = history.mapNotNull { point -> point.humidityPct?.let { point.timestampMillis to it } }
                if (currentInfoPanel != InfoPanelView.SENSOR_GRAPHS) return@launch
                sensorGraphsPanel.render(sensor.id, tempPoints, humidityPoints)
            }
        }
    }

    /** One chart per currently-selected river gauge (Settings > Rivers), mirroring
     *  [refreshSensorGraphs]. Current values come from [AppState] (already in memory); history
     *  needs an async DB read per gauge. */
    private fun refreshRiverGraphs() {
        val gauges = RiverGaugeSettings.getSelectedGauges(this)
        riverGraphsPanel.setGauges(gauges, AppState.uiState.value.riverReadings)
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            gauges.forEach { gauge ->
                val history = withContext(Dispatchers.IO) {
                    riverHistoryStore.getHistorySince(gauge.siteId, sinceMillis)
                }
                val levelPoints = history.mapNotNull { point -> point.gageHeightFt?.let { point.timestampMillis to it } }
                val flowPoints = history.mapNotNull { point -> point.dischargeCfs?.let { point.timestampMillis to it } }
                if (currentInfoPanel != InfoPanelView.RIVERS) return@launch
                riverGraphsPanel.render(gauge.siteId, levelPoints, flowPoints)
            }
        }
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
        private const val STALE_WEATHER_THRESHOLD_MS = 60 * 60 * 1000L
        private const val WAKE_OVERRIDE_DURATION_MS = 5 * 60 * 1000L
        private const val AUTO_CYCLE_INTERVAL_MS = 5 * 60 * 1000L
        private val AUTO_CYCLE_TABS = listOf(
            InfoPanelView.NEWS, InfoPanelView.FORECAST, InfoPanelView.SENSOR_GRAPHS
        )
    }
}

/** Which of the mutually-exclusive views is showing in the info panel. NEWS/FORECAST/
 *  SENSOR_GRAPHS/RADAR are the four tab bar destinations; SENSOR_CHART/ARTICLE are reached other
 *  ways (tapping a sensor row / a news item) and leave the tab bar showing nothing selected. */
private enum class InfoPanelView { NEWS, SENSOR_CHART, ARTICLE, FORECAST, SENSOR_GRAPHS, RADAR, RIVERS }
