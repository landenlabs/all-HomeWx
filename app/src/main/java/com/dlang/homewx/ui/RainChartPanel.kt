package com.dlang.homewx.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelForecastBinding
import com.dlang.homewx.rain.WeeklyRain
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** The forecast panel's Rain range: one bar per week of precipitation (inches), oldest to newest,
 *  the in-progress week drawn dimmer since its total is only "so far", with last year's same-week
 *  totals as a yellow line over the bars. Data is pushed in by
 *  [ForecastPanel] - this class only draws. */
class RainChartPanel(private val context: Context, binding: PanelForecastBinding) {

    val container: View = binding.forecastRainContainer
    private val chart: CombinedChart = binding.forecastRainChart
    private val statusText: TextView = binding.forecastRainStatusText

    private val weekFormat = SimpleDateFormat("M/d", Locale.getDefault())

    init {
        val axisTextColor = ContextCompat.getColor(context, R.color.text_secondary)
        val gridLineColor = ContextCompat.getColor(context, R.color.divider)
        chart.apply {
            legend.isEnabled = false
            // Bars and the last-year line share an x-axis; draw the line over the bars.
            drawOrder = arrayOf(CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE)
            description.isEnabled = false
            setNoDataText("")
            setScaleEnabled(false)
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            axisRight.isEnabled = false
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
        val barData = BarData(dataSet).apply { barWidth = 0.7f }

        // Last year's same-week totals, as a line - only weeks that have a value get a point.
        val lastYearEntries = weeks.mapIndexedNotNull { i, w -> w.lastYearInches?.let { Entry(i.toFloat(), it.toFloat()) } }
        val lastYearColor = ContextCompat.getColor(context, R.color.accent_warm)
        val lineData = LineData(LineDataSet(lastYearEntries, "").apply {
            color = lastYearColor
            lineWidth = 2f
            setCircleColor(lastYearColor)
            circleRadius = 3f
            setDrawValues(false)
            isHighlightEnabled = false
        })
        chart.data = CombinedData().apply {
            setData(barData)
            if (lastYearEntries.isNotEmpty()) setData(lineData)
        }

        chart.xAxis.apply {
            labelCount = weeks.size
            // CombinedChart has no setFitBars - pad half a bar either side so edge bars aren't clipped.
            axisMinimum = -0.5f
            axisMaximum = weeks.size - 0.5f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    weeks.getOrNull(value.toInt())?.let { weekFormat.format(Date(it.weekStartMillis)) } ?: ""
            }
        }
        // At least a 1 inch tall axis so a nearly-dry stretch doesn't blow a trace of rain up to
        // full height; headroom above the tallest bar leaves room for its value label.
        chart.axisLeft.axisMaximum = max(weeks.maxOf { max(it.inches, it.lastYearInches ?: 0.0) }.toFloat() * 1.2f, 1f)
        chart.invalidate()
    }

    private companion object {
        const val CURRENT_WEEK_ALPHA = 128
    }
}
