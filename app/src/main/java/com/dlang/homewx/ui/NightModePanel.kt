package com.dlang.homewx.ui

import android.content.Context
import android.view.LayoutInflater
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dlang.homewx.R
import com.dlang.homewx.data.WeatherMetricsPoint
import com.dlang.homewx.databinding.PanelNightModeBinding
import com.dlang.homewx.model.UiState
import com.dlang.homewx.settings.AppSettings
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Which metric the night layout's bottom third shows - see [NightModePanel.chooseSecondary]. */
enum class NightSecondary { WIND, PRECIPITATION, TEMPERATURE_READOUT }

/**
 * Full-screen layout shown in place of the normal UI while the light sensor has the app in
 * QUIET (night) mode. Top third: the time ("7:15p") in the largest font that fits. Middle
 * third: temperature, [WINDOW_HOURS] hours back and forward with the current time dead center
 * (solid green line). Bottom third: wind if it is above [WIND_MPH_THRESHOLD] now or anywhere in
 * the forecast window, else precipitation chance if above [PRECIP_PCT_THRESHOLD], else a big
 * current-temperature readout with its 4-hour trend.
 *
 * Past values come from the recorded weather-metrics history; future ones from the hourly
 * forecast. The history store doesn't record precipitation *chance*, so that chart's past half
 * is only whatever past hours the forecast provider itself returned.
 */
class NightModePanel(context: Context) {

    private val binding = PanelNightModeBinding.inflate(LayoutInflater.from(context))
    val root: View get() = binding.root

    private val context = binding.root.context
    private val timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
    private val hourFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val xAxisFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = hourFormat.format(Date(value.toLong() * 1000L))
    }

    init {
        // The background text's size depends on its frame's laid-out height, so (re)size it
        // whenever that changes rather than guessing a size up front.
        binding.nightTempFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeBackgroundText(binding.nightTempBgText, binding.nightTempFrame) }
        binding.nightSecondaryFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeBackgroundText(binding.nightSecondaryBgText, binding.nightSecondaryFrame) }
    }

    fun show() {
        applyBrightness()
        binding.root.visibility = View.VISIBLE
    }

    /** Scales the content's brightness by the user's night-brightness setting (the content sits
     *  on black, so view alpha is a straight brightness multiplier). Alpha goes on the content,
     *  never the root: the root's black background must stay opaque or the normal UI shows
     *  through it. */
    private fun applyBrightness() {
        val alpha = AppSettings.getNightBrightnessPercent(context) / 100f
        binding.nightContent.alpha = alpha
        binding.nightSettingsButton.alpha = alpha
    }
    fun setOnSettingsClick(listener: () -> Unit) {
        binding.nightSettingsButton.setOnClickListener { listener() }
    }

    fun hide() { binding.root.visibility = View.GONE }

    /** Redraws everything for [nowMillis]; cheap enough to call once a minute or on a weather update. */
    fun render(state: UiState, pastPoints: List<WeatherMetricsPoint>, nowMillis: Long = System.currentTimeMillis()) {
        applyBrightness()
        renderTime(nowMillis)

        val windowStart = nowMillis - WINDOW_MILLIS
        val windowEnd = nowMillis + WINDOW_MILLIS
        val future = state.weatherForecast?.hourly.orEmpty().filter { it.timeMillis in nowMillis..windowEnd }
        val past = pastPoints.filter { it.timestampMillis in windowStart until nowMillis }

        val temp = past.mapNotNull { p -> p.temperatureF?.let { p.timestampMillis to it } } to
            future.mapNotNull { h -> h.temperatureF?.let { h.timeMillis to it } }
        renderChart(binding.nightTempChart, temp.first, temp.second, nowMillis, R.color.accent_warm)

        val wind = past.mapNotNull { p -> p.windSpeedMph?.let { p.timestampMillis to it } } to
            future.mapNotNull { h -> h.windSpeedMph?.let { h.timeMillis to it } }
        val precipChance = state.weatherForecast?.hourly.orEmpty()
            .filter { it.timeMillis in windowStart..windowEnd }
            .mapNotNull { h -> h.precipitationChancePct?.let { h.timeMillis to it.toDouble() } }

        val maxWind = maxOf(state.currentWeather?.windSpeedMph ?: 0.0, wind.second.maxOfOrNull { it.second } ?: 0.0)
        val maxPrecipPct = precipChance.filter { it.first >= nowMillis }.maxOfOrNull { it.second } ?: 0.0
        val raining = (state.currentWeather?.precipitationIn ?: 0.0) > 0.0

        binding.nightTempBgText.text = state.currentWeather?.temperatureF?.roundToInt()?.let { "$it°" } ?: ""
        sizeBackgroundText(binding.nightTempBgText, binding.nightTempFrame)

        when (chooseSecondary(maxWind, maxPrecipPct, raining)) {
            NightSecondary.WIND -> {
                showChart()
                binding.nightSecondaryBgText.text = state.currentWeather?.windSpeedMph?.roundToInt()?.let { "$it mph" } ?: ""
                renderChart(
                    binding.nightSecondaryChart, wind.first, wind.second, nowMillis, R.color.accent_warm,
                    thresholds = listOf(LineChartSetup.ThresholdLine(WIND_MPH_THRESHOLD.toFloat(), R.color.white))
                )
            }
            NightSecondary.PRECIPITATION -> {
                showChart()
                val chanceNow = precipChance.lastOrNull { it.first <= nowMillis }?.second
                    ?: precipChance.firstOrNull()?.second
                binding.nightSecondaryBgText.text = chanceNow?.roundToInt()?.let { "$it%" } ?: ""
                renderChart(
                    binding.nightSecondaryChart,
                    precipChance.filter { it.first < nowMillis }, precipChance.filter { it.first >= nowMillis },
                    nowMillis, R.color.accent_cool, filled = true, fixedAxisRange = LineChartSetup.PERCENT_AXIS_RANGE,
                    thresholds = listOf(LineChartSetup.ThresholdLine(PRECIP_PCT_THRESHOLD.toFloat(), R.color.white, LineChartSetup.ThresholdDash.DOTTED))
                )
            }
            NightSecondary.TEMPERATURE_READOUT -> {
                binding.nightSecondaryChart.visibility = View.GONE
                binding.nightSecondaryBgText.text = ""
                binding.nightTempReadoutText.visibility = View.VISIBLE
                binding.nightTempReadoutText.text = temperatureReadout(state)
            }
        }
        sizeBackgroundText(binding.nightSecondaryBgText, binding.nightSecondaryFrame)
    }

    /** Sizes [text] so its digits are about half [frame]'s height (digit height is roughly 0.72
     *  of the font size), shrunk further if that would make the text wider than the frame. */
    private fun sizeBackgroundText(text: TextView, frame: View) {
        val frameHeight = frame.height
        val frameWidth = frame.width
        if (frameHeight == 0 || text.text.isNullOrEmpty()) return
        var sizePx = frameHeight * TARGET_HEIGHT_FRACTION / DIGIT_HEIGHT_PER_EM
        val measured = text.paint.apply { textSize = sizePx }.measureText(text.text.toString())
        if (measured > frameWidth * MAX_WIDTH_FRACTION) sizePx *= frameWidth * MAX_WIDTH_FRACTION / measured
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    }

    private fun showChart() {
        binding.nightSecondaryChart.visibility = View.VISIBLE
        binding.nightTempReadoutText.visibility = View.GONE
    }

    private fun renderTime(nowMillis: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val suffix = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "a" else "p"
        binding.nightTimeText.text = timeFormat.format(Date(nowMillis)) + suffix
    }

    private fun temperatureReadout(state: UiState): String {
        val temp = state.currentWeather?.temperatureF?.roundToInt()?.let { "$it°" } ?: "--°"
        val trend = state.tempTrendNext4HourF?.roundToInt()?.let { "  %+d°/4h".format(it) }.orEmpty()
        return temp + trend
    }

    /** Draws [past] then [future] as two series (future dashed) on [chart], x-axis pinned to
     *  [nowMillis] +/- [WINDOW_MILLIS] so "now" is always the horizontal center. */
    private fun renderChart(
        chart: LineChart,
        past: List<Pair<Long, Double>>,
        future: List<Pair<Long, Double>>,
        nowMillis: Long,
        colorRes: Int,
        filled: Boolean = false,
        fixedAxisRange: Pair<Float, Float>? = null,
        thresholds: List<LineChartSetup.ThresholdLine> = emptyList()
    ) {
        LineChartSetup.configure(chart, context, description = null, xAxisValueFormatter = xAxisFormatter,
            minMarkerAxis = YAxis.AxisDependency.LEFT.takeIf { chart === binding.nightTempChart })
        LineChartSetup.setThresholdLines(chart, context, thresholds)
        LineChartSetup.setLimitLines(chart, context, emptyList())
        LineChartSetup.addCurrentTimeMarker(chart, context, nowMillis / 1000f)

        val color = ContextCompat.getColor(context, colorRes)
        fun dataSet(points: List<Pair<Long, Double>>, dashed: Boolean): LineDataSet? {
            if (points.isEmpty()) return null
            return LineDataSet(points.map { (t, v) -> Entry(t / 1000f, v.toFloat()) }, null).apply {
                this.color = color
                lineWidth = 4f
                setDrawCircles(false)
                setDrawValues(false)
                if (dashed) enableDashedLine(10f, 6f, 0f)
                if (filled) {
                    setDrawFilled(true)
                    fillColor = color
                    fillAlpha = 100
                }
            }
        }
        // The two halves meet at "now": give the future series the last past sample as its
        // starting point so there's no visible gap between them.
        val joinedFuture = past.lastOrNull()?.let { listOf(it) + future } ?: future
        val dataSets = listOfNotNull(dataSet(past, dashed = false), dataSet(joinedFuture, dashed = true))
        if (dataSets.isEmpty()) {
            chart.clear()
        } else {
            chart.data = LineData(dataSets)
        }

        chart.xAxis.axisMinimum = (nowMillis - WINDOW_MILLIS) / 1000f
        chart.xAxis.axisMaximum = (nowMillis + WINDOW_MILLIS) / 1000f
        if (fixedAxisRange != null) {
            chart.axisLeft.axisMinimum = fixedAxisRange.first
            chart.axisLeft.axisMaximum = fixedAxisRange.second
        } else {
            chart.axisLeft.resetAxisMinimum()
            chart.axisLeft.resetAxisMaximum()
        }
        chart.invalidate()
    }

    companion object {
        private const val TARGET_HEIGHT_FRACTION = 0.5f
        private const val DIGIT_HEIGHT_PER_EM = 0.72f
        private const val MAX_WIDTH_FRACTION = 0.9f
        const val WINDOW_HOURS = 12
        private const val WINDOW_MILLIS = WINDOW_HOURS * 60 * 60 * 1000L
        const val WIND_MPH_THRESHOLD = 10.0
        const val PRECIP_PCT_THRESHOLD = 50.0

        /** Wind wins over precipitation when both qualify; precipitation also qualifies on any
         *  measured rain right now (there's no "current chance" to compare against the threshold). */
        fun chooseSecondary(maxWindMph: Double, maxPrecipChancePct: Double, raining: Boolean): NightSecondary = when {
            maxWindMph > WIND_MPH_THRESHOLD -> NightSecondary.WIND
            maxPrecipChancePct > PRECIP_PCT_THRESHOLD || raining -> NightSecondary.PRECIPITATION
            else -> NightSecondary.TEMPERATURE_READOUT
        }
    }
}
