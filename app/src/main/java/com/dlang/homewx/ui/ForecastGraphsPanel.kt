package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dlang.homewx.R
import com.dlang.homewx.data.WeatherMetricsPoint
import com.dlang.homewx.databinding.PanelForecastGraphsBinding
import com.dlang.homewx.weather.HourlyForecastEntry
import com.dlang.homewx.weather.WeatherForecast
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Temperature / wind / precipitation / pressure line graphs for the forecast panel's Past,
 * Hourly or Daily range - up to 4 strip charts spanning the panel width, stacked with equal
 * vertical space (pressure only appears for Past; Hourly/Daily show the first 3). Each chart's
 * title is drawn in the app's blue accent, matching every other graph in the app. Data lines are
 * yellow, except the daily temperature chart's minimum-temperature line, which is light blue.
 *
 * A chart with fewer than 2 data points, or (for wind/precipitation) whose entire dataset is
 * exactly zero, collapses to just its title plus a "No <metric> data"/"No <metric>
 * expected|recorded" statement, and gives up its share of vertical space to the other charts -
 * e.g. Past's temperature chart collapses on any device that hasn't yet recorded 2 samples
 * since the history store started tracking temperature.
 */
class ForecastGraphsPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelForecastGraphsBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root

    private val collapsedEmptyTextPaddingPx = (12 * context.resources.displayMetrics.density).toInt()

    private val hourFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    private val pastFormat = SimpleDateFormat("EEE h a", Locale.getDefault())

    init {
        container.addView(root)
        // Titles live in their own TextView below each chart rather than MPAndroidChart's
        // built-in description (drawn inside the chart's own bottom-right corner), which
        // overlapped the hourly view's day-of-week axis labels there.
        binding.forecastTempTitleText.text = context.getString(R.string.forecast_graph_temperature)
        binding.forecastWindTitleText.text = context.getString(R.string.weather_graph_wind_speed)
        binding.forecastPrecipTitleText.text = context.getString(R.string.forecast_graph_precipitation_chance)
        binding.forecastPressureTitleText.text = context.getString(R.string.weather_graph_pressure)
    }

    fun render(range: ForecastRange, forecast: WeatherForecast, pastPoints: List<WeatherMetricsPoint>) {
        binding.forecastPressureSection.visibility = if (range == ForecastRange.PAST) View.VISIBLE else View.GONE

        val xAxisValueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val date = Date(value.toLong() * 1000L)
                return when (range) {
                    ForecastRange.PAST -> pastFormat.format(date)
                    ForecastRange.HOURLY -> hourFormat.format(date)
                    ForecastRange.DAILY -> dayFormat.format(date)
                }
            }
        }
        listOfNotNull(
            binding.forecastTempChartView,
            binding.forecastWindChartView,
            binding.forecastPrecipChartView,
            binding.forecastPressureChartView.takeIf { range == ForecastRange.PAST }
        ).forEach { LineChartSetup.configure(it, context, description = null, xAxisValueFormatter) }

        LineChartSetup.setThresholdLines(binding.forecastTempChartView, context, TEMPERATURE_THRESHOLDS)
        LineChartSetup.setThresholdLines(binding.forecastWindChartView, context, WIND_THRESHOLDS)
        // Precipitation chance is a percentage for Hourly/Daily but inches of rain for Past, so
        // the percent thresholds only make sense on the former.
        LineChartSetup.setThresholdLines(
            binding.forecastPrecipChartView, context,
            if (range == ForecastRange.PAST) emptyList() else PRECIPITATION_CHANCE_THRESHOLDS
        )

        when (range) {
            ForecastRange.HOURLY -> {
                val hours = forecast.hourly
                val dayBoundaries = dayBoundaryXValues(hours)
                val noonLabels = noonDayOfWeekLabels(hours)
                listOf(binding.forecastTempChartView, binding.forecastWindChartView, binding.forecastPrecipChartView).forEach { chart ->
                    // The default hour-by-hour tick labels ("3 PM") land wherever MPAndroidChart's
                    // automatic grid computation happens to fall, which reads as near-random -
                    // replaced with one "Mon"/"Tue" label per day, centered on that day's noon.
                    chart.xAxis.setDrawLabels(false)
                    LineChartSetup.setLimitLines(chart, context, dayBoundaries)
                    LineChartSetup.addAxisLabelMarkers(chart, context, noonLabels)
                }

                renderSingleLineTemp(hours.mapNotNull { h -> h.temperatureF?.let { h.timeMillis to it } })
                renderMetric(
                    binding.forecastWindSection, binding.forecastWindChartFrame, binding.forecastWindChartView, binding.forecastWindEmptyText,
                    hours.mapNotNull { h -> h.windSpeedMph?.let { h.timeMillis to it } },
                    R.string.forecast_no_wind_data, R.string.forecast_no_wind
                )
                renderMetric(
                    binding.forecastPrecipSection, binding.forecastPrecipChartFrame, binding.forecastPrecipChartView, binding.forecastPrecipEmptyText,
                    hours.mapNotNull { h -> h.precipitationChancePct?.let { h.timeMillis to it.toDouble() } },
                    R.string.forecast_no_precipitation_data, R.string.forecast_no_precipitation,
                    R.color.accent_cool, filled = true
                )
            }
            ForecastRange.DAILY -> {
                val days = forecast.daily
                // Each entry here is already one full calendar day apart, so the shared
                // day-boundary detector (built for hourly's many-points-per-day case) places a
                // marker at every entry but the first - exactly the dividers between days.
                val dayBoundaries = LineChartSetup.dayBoundaryXValues(days.map { it.dateMillis })
                listOf(binding.forecastTempChartView, binding.forecastWindChartView, binding.forecastPrecipChartView).forEach { chart ->
                    chart.xAxis.setDrawLabels(true)
                    LineChartSetup.setLimitLines(chart, context, dayBoundaries)
                }

                renderDailyTemp(
                    days.mapNotNull { d -> d.highF?.let { d.dateMillis to it } },
                    days.mapNotNull { d -> d.lowF?.let { d.dateMillis to it } }
                )
                renderMetric(
                    binding.forecastWindSection, binding.forecastWindChartFrame, binding.forecastWindChartView, binding.forecastWindEmptyText,
                    days.mapNotNull { d -> d.windMaxMph?.let { d.dateMillis to it } },
                    R.string.forecast_no_wind_data, R.string.forecast_no_wind
                )
                renderMetric(
                    binding.forecastPrecipSection, binding.forecastPrecipChartFrame, binding.forecastPrecipChartView, binding.forecastPrecipEmptyText,
                    days.mapNotNull { d -> d.precipitationChancePct?.let { d.dateMillis to it.toDouble() } },
                    R.string.forecast_no_precipitation_data, R.string.forecast_no_precipitation,
                    R.color.accent_cool, filled = true
                )
            }
            ForecastRange.PAST -> {
                listOf(binding.forecastTempChartView, binding.forecastWindChartView, binding.forecastPrecipChartView, binding.forecastPressureChartView).forEach { chart ->
                    chart.xAxis.setDrawLabels(true)
                    LineChartSetup.setLimitLines(chart, context, emptyList())
                }

                renderSingleLineTemp(pastPoints.mapNotNull { p -> p.temperatureF?.let { p.timestampMillis to it } })
                renderMetric(
                    binding.forecastWindSection, binding.forecastWindChartFrame, binding.forecastWindChartView, binding.forecastWindEmptyText,
                    pastPoints.mapNotNull { p -> p.windSpeedMph?.let { p.timestampMillis to it } },
                    R.string.forecast_no_wind_data, R.string.forecast_no_wind_recorded
                )
                renderMetric(
                    binding.forecastPrecipSection, binding.forecastPrecipChartFrame, binding.forecastPrecipChartView, binding.forecastPrecipEmptyText,
                    pastPoints.mapNotNull { p -> p.precipitationIn?.let { p.timestampMillis to it } },
                    R.string.forecast_no_precipitation_data, R.string.forecast_no_precipitation_recorded
                )
                renderMetric(
                    binding.forecastPressureSection, binding.forecastPressureChartFrame, binding.forecastPressureChartView, binding.forecastPressureEmptyText,
                    pastPoints.mapNotNull { p -> p.pressureInHg?.let { p.timestampMillis to it } },
                    R.string.forecast_no_pressure_data, allZeroMessageRes = null
                )
            }
        }
    }

    /** x-values (matching [LineChartSetup]'s seconds-since-epoch convention) where one calendar
     *  day's hours end and the next day's begin - skips the very first hour, which isn't a
     *  "change". */
    private fun dayBoundaryXValues(hours: List<HourlyForecastEntry>): List<Float> =
        LineChartSetup.dayBoundaryXValues(hours.map { it.timeMillis })

    /** One (x, "Mon") label per calendar day in [hours], positioned at that day's noon - only
     *  when noon actually falls within the covered data range, and skipped near the very start
     *  or end of that range where there's not enough width for the label to sit without
     *  crowding the chart edge. */
    private fun noonDayOfWeekLabels(hours: List<HourlyForecastEntry>): List<Pair<Float, String>> {
        if (hours.isEmpty()) return emptyList()
        val dataMinMillis = hours.first().timeMillis
        val dataMaxMillis = hours.last().timeMillis
        val totalRangeMillis = (dataMaxMillis - dataMinMillis).toDouble()
        if (totalRangeMillis <= 0) return emptyList()

        val calendar = Calendar.getInstance()
        val seenDayKeys = mutableSetOf<Int>()
        val labels = mutableListOf<Pair<Float, String>>()
        for (entry in hours) {
            calendar.timeInMillis = entry.timeMillis
            val dayKey = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
            if (!seenDayKeys.add(dayKey)) continue

            calendar.set(Calendar.HOUR_OF_DAY, 12)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val noonMillis = calendar.timeInMillis
            if (noonMillis < dataMinMillis || noonMillis > dataMaxMillis) continue

            val edgeFraction = (noonMillis - dataMinMillis) / totalRangeMillis
            if (edgeFraction < EDGE_MARGIN_FRACTION || edgeFraction > 1.0 - EDGE_MARGIN_FRACTION) continue

            labels.add((noonMillis / 1000f) to dayFormat.format(Date(noonMillis)))
        }
        return labels
    }

    private fun renderSingleLineTemp(points: List<Pair<Long, Double>>) {
        val collapse = points.size < 2
        setSectionCollapsed(binding.forecastTempSection, binding.forecastTempChartFrame, binding.forecastTempEmptyText, collapse)
        if (collapse) {
            binding.forecastTempChartView.visibility = View.GONE
            binding.forecastTempEmptyText.visibility = View.VISIBLE
            binding.forecastTempEmptyText.setText(R.string.forecast_no_temperature_data)
        } else {
            binding.forecastTempChartView.visibility = View.VISIBLE
            binding.forecastTempEmptyText.visibility = View.GONE
            LineChartSetup.render(binding.forecastTempChartView, context, points)
        }
    }

    private fun renderDailyTemp(highs: List<Pair<Long, Double>>, lows: List<Pair<Long, Double>>) {
        val collapse = highs.size < 2 && lows.size < 2
        setSectionCollapsed(binding.forecastTempSection, binding.forecastTempChartFrame, binding.forecastTempEmptyText, collapse)
        if (collapse) {
            binding.forecastTempChartView.visibility = View.GONE
            binding.forecastTempEmptyText.visibility = View.VISIBLE
            binding.forecastTempEmptyText.setText(R.string.forecast_no_temperature_data)
        } else {
            binding.forecastTempChartView.visibility = View.VISIBLE
            binding.forecastTempEmptyText.visibility = View.GONE
            LineChartSetup.renderSeries(
                binding.forecastTempChartView,
                context,
                listOf(highs to R.color.accent_warm, lows to R.color.accent_cool)
            )
        }
    }

    /** Shared renderer for the single-line metrics (wind, precipitation, pressure): collapses
     *  the section when there's not enough data, or - when [allZeroMessageRes] is given - when
     *  every value is exactly zero. Pressure has no such all-zero case (0 inHg never happens),
     *  so its call site passes null. */
    private fun renderMetric(
        section: LinearLayout,
        chartFrame: FrameLayout,
        chart: LineChart,
        emptyText: TextView,
        points: List<Pair<Long, Double>>,
        noDataMessageRes: Int,
        allZeroMessageRes: Int?,
        colorRes: Int = R.color.accent_warm,
        filled: Boolean = false
    ) {
        val noData = points.size < 2
        val allZero = !noData && allZeroMessageRes != null && points.all { it.second == 0.0 }
        val collapse = noData || allZero
        setSectionCollapsed(section, chartFrame, emptyText, collapse)
        if (collapse) {
            chart.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.setText(if (noData) noDataMessageRes else allZeroMessageRes!!)
        } else {
            chart.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            LineChartSetup.render(chart, context, points, colorRes, filled)
        }
    }

    /** Shrinks [section]/[chartFrame] to just their title's height (giving up their share of the
     *  panel's vertical space to the other sections) when [collapsed], or restores their normal
     *  equal-weight sizing otherwise. */
    private fun setSectionCollapsed(section: LinearLayout, chartFrame: FrameLayout, emptyText: TextView, collapsed: Boolean) {
        val sectionParams = section.layoutParams as LinearLayout.LayoutParams
        sectionParams.height = if (collapsed) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        sectionParams.weight = if (collapsed) 0f else 1f
        section.layoutParams = sectionParams

        val frameParams = chartFrame.layoutParams as LinearLayout.LayoutParams
        frameParams.height = if (collapsed) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        frameParams.weight = if (collapsed) 0f else 1f
        chartFrame.layoutParams = frameParams

        val emptyTextParams = emptyText.layoutParams as FrameLayout.LayoutParams
        emptyTextParams.height = if (collapsed) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        emptyText.layoutParams = emptyTextParams
        emptyText.setPadding(emptyText.paddingLeft, collapsedEmptyTextPaddingPx.takeIf { collapsed } ?: 0, emptyText.paddingRight, collapsedEmptyTextPaddingPx.takeIf { collapsed } ?: 0)
    }

    companion object {
        /** A noon label within this fraction of either edge of the visible x-range is dropped -
         *  not enough room to draw it without crowding the chart's border. */
        private const val EDGE_MARGIN_FRACTION = 0.05

        private val TEMPERATURE_THRESHOLDS = listOf(100f to R.color.white, 32f to R.color.blue2)
        private val WIND_THRESHOLDS = listOf(10f to R.color.white, 20f to R.color.red)
        private val PRECIPITATION_CHANCE_THRESHOLDS = listOf(50f to R.color.white, 75f to R.color.blue2)
    }
}
