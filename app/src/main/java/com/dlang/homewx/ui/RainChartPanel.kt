package com.dlang.homewx.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelForecastBinding
import com.dlang.homewx.rain.WeeklyRain
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** The forecast panel's Rain range: one bar per week of precipitation (inches), oldest to newest,
 *  the in-progress week drawn dimmer since its total is only "so far". Data is pushed in by
 *  [ForecastPanel] - this class only draws. */
class RainChartPanel(private val context: Context, binding: PanelForecastBinding) {

    val container: View = binding.forecastRainContainer
    private val chart: BarChart = binding.forecastRainChart
    private val statusText: TextView = binding.forecastRainStatusText

    private val weekFormat = SimpleDateFormat("M/d", Locale.getDefault())

    init {
        val axisTextColor = ContextCompat.getColor(context, R.color.text_secondary)
        val gridLineColor = ContextCompat.getColor(context, R.color.divider)
        chart.apply {
            legend.isEnabled = false
            description.isEnabled = false
            setNoDataText("")
            setScaleEnabled(false)
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            axisRight.isEnabled = false
            setFitBars(true)
        }
        chart.axisLeft.apply {
            textColor = axisTextColor
            gridColor = gridLineColor
            axisMinimum = 0f // bars always start at zero - an auto-zoomed baseline would mislead
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = axisTextColor
            this.gridColor = gridLineColor
            setDrawGridLines(false)
            granularity = 1f
        }
    }

    fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    fun render(weeks: List<WeeklyRain>) {
        statusText.visibility = View.GONE
        if (weeks.isEmpty()) {
            chart.clear()
            return
        }

        val barColor = ContextCompat.getColor(context, R.color.accent_cool)
        val dataSet = BarDataSet(weeks.mapIndexed { i, w -> BarEntry(i.toFloat(), w.inches.toFloat()) }, "").apply {
            colors = weeks.map { if (it.isCurrent) ColorUtils.setAlphaComponent(barColor, CURRENT_WEEK_ALPHA) else barColor }
            valueTextColor = ContextCompat.getColor(context, R.color.text_primary)
            valueTextSize = 11f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = if (value < 0.005f) "" else "%.2f".format(value)
            }
        }
        chart.data = BarData(dataSet).apply { barWidth = 0.7f }

        chart.xAxis.apply {
            labelCount = weeks.size
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    weeks.getOrNull(value.toInt())?.let { weekFormat.format(Date(it.weekStartMillis)) } ?: ""
            }
        }
        // At least a 1 inch tall axis so a nearly-dry stretch doesn't blow a trace of rain up to
        // full height; headroom above the tallest bar leaves room for its value label.
        chart.axisLeft.axisMaximum = max(weeks.maxOf { it.inches }.toFloat() * 1.2f, 1f)
        chart.invalidate()
    }

    private companion object {
        const val CURRENT_WEEK_ALPHA = 128
    }
}
