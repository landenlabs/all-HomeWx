package com.landenlabs.homewx.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.landenlabs.homewx.R
import com.landenlabs.homewx.databinding.PanelForecastBinding
import com.landenlabs.homewx.rain.MonthlyRain
import com.landenlabs.homewx.rain.RainTotals
import com.landenlabs.homewx.rain.WeeklyRain
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

    private val chartGroup: View = binding.forecastRainChartGroup
    private val tableScroll: View = binding.forecastRainTableScroll
    private val tableContent: LinearLayout = binding.forecastRainTableContent
    private var tableShown = false

    private val weekFormat = SimpleDateFormat("M/d", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

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

    /** Swaps the bar chart for the weekly + monthly tables (same data, as numbers). */
    fun showTable(show: Boolean) {
        tableShown = show
        chartGroup.visibility = if (show) View.GONE else View.VISIBLE
        tableScroll.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun render(totals: RainTotals) {
        renderTables(totals)
        renderChart(totals.weeks)
    }

    private fun renderTables(totals: RainTotals) {
        tableContent.removeAllViews()
        if (totals.weeks.isEmpty()) return
        addTitle(R.string.forecast_rain_table_weekly_title, topMargin = false)
        addRow(context.getString(R.string.forecast_rain_table_week_header), context.getString(R.string.forecast_rain_table_this_year), context.getString(R.string.forecast_rain_table_last_year), header = true)
        totals.weeks.forEach { w ->
            val label = weekFormat.format(Date(w.weekStartMillis)) + if (w.isCurrent) " *" else ""
            addRow(label, inches(w.inches), inches(w.lastYearInches))
        }
        if (totals.weeks.any { it.isCurrent }) {
            tableContent.addView(TextView(context).apply {
                text = context.getString(R.string.forecast_rain_table_current_note)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 4, 0, 0)
            })
        }
        addTitle(R.string.forecast_rain_table_monthly_title, topMargin = true)
        addRow(context.getString(R.string.forecast_rain_table_month_header), context.getString(R.string.forecast_rain_table_this_year), context.getString(R.string.forecast_rain_table_last_year), header = true)
        totals.months.forEach { m -> addRow(monthFormat.format(Date(m.monthStartMillis)), inches(m.inches), inches(m.lastYearInches)) }
    }

    private fun inches(value: Double?) = value?.let { "%.2f".format(it) } ?: "--"

    private fun addTitle(titleRes: Int, topMargin: Boolean) {
        tableContent.addView(TextView(context).apply {
            setText(titleRes)
            setTextColor(ContextCompat.getColor(context, R.color.accent_cool))
            textSize = 16f
            setPadding(0, if (topMargin) 28 else 0, 0, 8)
        })
    }

    private fun addRow(label: String, thisYear: String, lastYear: String, header: Boolean = false) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6) }
        fun cell(text: String, weight: Float, gravity: Int, colorRes: Int) = TextView(context).apply {
            this.text = text
            this.gravity = gravity
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, colorRes))
            if (header) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
        row.addView(cell(label, 1.2f, Gravity.START, if (header) R.color.text_secondary else R.color.text_primary))
        row.addView(cell(thisYear, 1f, Gravity.END, if (header) R.color.accent_cool else R.color.text_primary))
        row.addView(cell(lastYear, 1f, Gravity.END, if (header) R.color.accent_warm else R.color.text_primary))
        tableContent.addView(row)
    }

    private fun renderChart(weeks: List<WeeklyRain>) {
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
