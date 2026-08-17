package com.dlang.homewx

import android.app.AlertDialog
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dlang.homewx.data.DailySnapshot
import com.dlang.homewx.data.DailySnapshotStore
import com.dlang.homewx.data.SensorHistoryStore
import com.dlang.homewx.databinding.ActivityMainBinding
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.model.SensorReading
import com.dlang.homewx.model.UiState
import com.dlang.homewx.power.ScreenPowerController
import com.dlang.homewx.service.HomeWxMonitorService
import com.dlang.homewx.state.AppState
import com.dlang.homewx.ui.SensorAdapter
import com.dlang.homewx.ui.weatherIconRes
import com.dlang.homewx.weather.DailyExtreme
import com.dlang.homewx.weather.HomeLocation
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.startOfDay
import kotlinx.coroutines.Dispatchers
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
    private val sensorHistoryStore by lazy { SensorHistoryStore(applicationContext) }
    private val dailySnapshotStore by lazy { DailySnapshotStore(applicationContext) }

    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private val forecastDayFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())
    private val weatherDateTimeFormat = SimpleDateFormat("dd MMM, EEE hh:mm a", Locale.getDefault())
    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val historicalDayFormat = SimpleDateFormat("dd MMM, EEE", Locale.getDefault())

    private var latestForecast: WeatherForecast? = null
    /** 0 = today (live), negative = that many days in the past (a frozen [DailySnapshot]). */
    private var viewingDayOffset = 0

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

        screenPowerController = ScreenPowerController(this)
        binding.sensorRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sensorRecyclerView.adapter = sensorAdapter
        binding.weatherRow.setOnTouchListener { _, event -> weatherGestureDetector.onTouchEvent(event) }

        HomeWxMonitorService.start(this)

        observeState()
        startClock()
    }

    // Not gated to STARTED: the light-triggered "wake the screen" action must
    // apply its window flags even while the activity is merely STOPPED
    // (screen off but not destroyed), otherwise the tablet never wakes back up.
    private fun observeState() {
        lifecycleScope.launch {
            AppState.uiState.collect { state ->
                screenPowerController.apply(state.lightMode)
                sensorAdapter.submit(state.sensors)
                binding.modeChip.text = if (state.lightMode == LightMode.ACTIVE) "☀ Active" else "🌙 Quiet"
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
        binding.currentTempText.text = snapshot.tempF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.tempTrendText.text = ""
        binding.conditionValueText.text = snapshot.conditionText ?: "--"
        binding.humidityValueText.text = snapshot.humidityPct?.roundToInt()?.let { "$it%" } ?: "--"
        binding.windSpeedValueText.text = snapshot.windSpeedMph?.roundToInt()?.let { "$it mph" } ?: "--"
        binding.windDirectionValueText.text = formatWindDirection(snapshot.windDirectionDeg)
        binding.precipitationValueText.text = snapshot.precipitationIn?.let { "%.2f in".format(it) } ?: "--"
        binding.pressureValueText.text = snapshot.pressureInHg?.let { "%.2f inHg".format(it) } ?: "--"
        binding.tempHighValueText.text = formatExtreme(toExtreme(snapshot.tempHighF, snapshot.tempHighAtMillis), "°F")
        binding.tempLowValueText.text = formatExtreme(toExtreme(snapshot.tempLowF, snapshot.tempLowAtMillis), "°F")
        binding.windHighValueText.text = formatExtreme(toExtreme(snapshot.windHighMph, snapshot.windHighAtMillis), " mph")
        binding.windLowValueText.text = formatExtreme(toExtreme(snapshot.windLowMph, snapshot.windLowAtMillis), " mph")
        binding.lyMaxValueText.text = snapshot.lyAvgHighF?.roundToInt()?.let { "$it°F" } ?: "--"
        binding.lyMinValueText.text = snapshot.lyAvgLowF?.roundToInt()?.let { "$it°F" } ?: "--"
    }

    private fun toExtreme(value: Double?, atMillis: Long?): DailyExtreme? =
        if (value != null && atMillis != null) DailyExtreme(value, atMillis) else null

    private fun goToOlderDay() {
        viewingDayOffset -= 1
        loadViewedDay()
    }

    private fun goToNewerDay() {
        if (viewingDayOffset == 0) return
        viewingDayOffset += 1
        loadViewedDay()
    }

    private fun loadViewedDay() {
        if (viewingDayOffset == 0) {
            bindLiveWeather(AppState.uiState.value)
            binding.weatherDateTimeText.text = weatherDateTimeFormat.format(Date())
            return
        }
        val dayMillis = startOfDay(System.currentTimeMillis()) + viewingDayOffset * DAY_MILLIS
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { dailySnapshotStore.getSnapshot(dayMillis) }
            if (snapshot == null) {
                // Nothing saved that far back yet - bounce back to the nearest day that has data.
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
        val pressure = pressureInHg?.let { "%.2f inHg".format(it) } ?: "--"
        val trend = trend6hInHg?.let { " (%+.2f/6h)".format(it) }.orEmpty()
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

    private fun showSensorHistory(reading: SensorReading) {
        binding.futureInfoPlaceholderText.visibility = View.GONE
        binding.stripChartPanel.visibility = View.VISIBLE
        binding.stripChartTitleText.text = "${reading.roomName} — temperature"
        lifecycleScope.launch {
            val sinceMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
            val points = withContext(Dispatchers.IO) {
                sensorHistoryStore.getHistorySince(reading.id, sinceMillis)
                    .mapNotNull { point -> point.tempF?.let { point.timestampMillis to it } }
            }
            binding.stripChartView.setData(points)
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
                val now = Date()
                binding.clockText.text = clockFormat.format(now)
                binding.dateText.text = dateFormat.format(now)
                if (viewingDayOffset == 0) {
                    binding.weatherDateTimeText.text = weatherDateTimeFormat.format(now)
                }
                delay(30_000L)
            }
        }
    }

    companion object {
        private const val SWIPE_THRESHOLD_PX = 80
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
