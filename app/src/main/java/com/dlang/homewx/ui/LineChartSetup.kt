package com.dlang.homewx.ui

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.dlang.homewx.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

/** Shared axis/touch setup and data-binding for every [LineChart] in the info panel (sensor history + weather graphs). */
object LineChartSetup {

    fun configure(chart: LineChart, context: Context, description: String?, xAxisValueFormatter: ValueFormatter) {
        val axisTextColor = ContextCompat.getColor(context, R.color.text_secondary)
        val gridLineColor = ContextCompat.getColor(context, R.color.divider)

        chart.legend.isEnabled = false
        chart.setNoDataText(context.getString(R.string.strip_chart_no_data))
        chart.setNoDataTextColor(axisTextColor)
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        if (description != null) {
            // The description doubles as this chart's "which data source is this" label,
            // so it gets its own accent color rather than the neutral axis text color.
            chart.description.apply {
                isEnabled = true
                text = description
                textColor = ContextCompat.getColor(context, R.color.accent_cool)
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
            valueFormatter = xAxisValueFormatter
        }
    }

    /** Draws a thin vertical marker (no label) at each x-value in [xValues] - e.g. the forecast
     *  graphs' hourly charts use this to mark where one calendar day ends and the next begins.
     *  Replaces whatever limit lines the chart had before, so pass an empty list to clear them. */
    fun setLimitLines(chart: LineChart, context: Context, xValues: List<Float>) {
        chart.xAxis.removeAllLimitLines()
        xValues.forEach { x ->
            chart.xAxis.addLimitLine(
                LimitLine(x).apply {
                    lineColor = ContextCompat.getColor(context, R.color.accent_day_marker)
                    lineWidth = 1f
                    enableDashedLine(6f, 4f, 0f)
                }
            )
        }
    }

    /** Draws fixed horizontal threshold lines on the value (y) axis - e.g. wind speed's "high
     *  wind" line, or temperature's freezing line. Replaces whatever horizontal limit lines the
     *  chart had before, so pass an empty list to clear them. */
    fun setThresholdLines(chart: LineChart, context: Context, thresholds: List<Pair<Float, Int>>) {
        chart.axisLeft.removeAllLimitLines()
        thresholds.forEach { (value, colorRes) ->
            chart.axisLeft.addLimitLine(
                LimitLine(value).apply {
                    lineColor = ContextCompat.getColor(context, colorRes)
                    lineWidth = 1.5f
                }
            )
        }
    }

    /** Adds an invisible limit line per (x, text) pair purely to get its text label drawn near
     *  the x-axis at that x-value - used instead of the axis's own auto-placed tick labels when
     *  we want labels at specific x-values (e.g. noon of each day) rather than wherever
     *  MPAndroidChart's "nice interval" grid computation happens to land. Adds to whatever limit
     *  lines are already on the chart (e.g. from [setLimitLines]) rather than clearing them, so
     *  call this after [setLimitLines], not before. */
    fun addAxisLabelMarkers(chart: LineChart, context: Context, labels: List<Pair<Float, String>>) {
        val axisTextColor = ContextCompat.getColor(context, R.color.text_secondary)
        labels.forEach { (x, text) ->
            chart.xAxis.addLimitLine(
                LimitLine(x, text).apply {
                    lineColor = Color.TRANSPARENT
                    textColor = axisTextColor
                    textSize = 12f
                    labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
                }
            )
        }
    }

    /** Every line graph in the app shares the same line color by design. */
    fun render(chart: LineChart, context: Context, points: List<Pair<Long, Double>>) {
        if (points.size < 2) {
            chart.clear()
            return
        }
        val entries = points.map { (timeMillis, value) -> Entry(timeMillis / 1000f, value.toFloat()) }
        val dataSet = LineDataSet(entries, null).apply {
            color = ContextCompat.getColor(context, R.color.accent_warm)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    /** Like [render], but for the one chart in the app that plots two related series
     *  (daily high/low temperature) - each series gets its own color. */
    fun renderSeries(chart: LineChart, context: Context, series: List<Pair<List<Pair<Long, Double>>, Int>>) {
        val dataSets = series.mapNotNull { (points, colorRes) ->
            if (points.size < 2) return@mapNotNull null
            val entries = points.map { (timeMillis, value) -> Entry(timeMillis / 1000f, value.toFloat()) }
            LineDataSet(entries, null).apply {
                color = ContextCompat.getColor(context, colorRes)
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
            }
        }
        if (dataSets.isEmpty()) {
            chart.clear()
            return
        }
        chart.data = LineData(dataSets)
        chart.invalidate()
    }
}
