package com.dlang.homewx.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.dlang.homewx.R
import com.github.mikephil.charting.charts.LineChart
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
}
