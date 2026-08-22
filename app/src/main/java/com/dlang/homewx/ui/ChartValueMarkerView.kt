package com.dlang.homewx.ui

import android.content.Context
import android.widget.TextView
import com.dlang.homewx.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/** Small label that follows a tap/drag highlight on [chart], showing the time and value under the
 *  crosshair - formatted with whatever x/y-axis [com.github.mikephil.charting.formatter.ValueFormatter]s
 *  [chart] already has, so it automatically matches each chart's own units (e.g. "3 PM  72°").
 *  See [LineChartSetup.configure], which wires this into every chart in the app. */
class ChartValueMarkerView(context: Context, private val chart: LineChart) : MarkerView(context, R.layout.marker_chart_value) {

    private val textView: TextView = findViewById(R.id.markerValueText)

    override fun refreshContent(e: Entry, highlight: Highlight) {
        val time = chart.xAxis.valueFormatter?.getFormattedValue(e.x)
        val value = chart.axisLeft.valueFormatter?.getFormattedValue(e.y) ?: e.y.toString()
        textView.text = if (time.isNullOrEmpty()) value else "$time  $value"
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat())
}
