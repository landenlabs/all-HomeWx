package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dlang.homewx.databinding.PanelSensorGraphsBinding
import com.dlang.homewx.model.SensorReading
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One temperature history chart per currently-visible sensor, sharing equal height like
 *  [WeatherGraphsPanel]. Inflates itself into [container]. */
class SensorGraphsPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelSensorGraphsBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root
    private val chartContainer = binding.root

    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val xAxisValueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = hourOnlyFormat.format(Date(value.toLong() * 1000L))
    }
    private val chartsBySensorId = mutableMapOf<String, LineChart>()

    init {
        container.addView(root)
    }

    /** Rebuilds one chart per sensor, replacing whatever was there before. No-ops if the set
     *  of visible sensors (by id, in order) hasn't changed since the last call. */
    fun setSensors(sensors: List<SensorReading>) {
        if (sensors.map { it.id } == chartsBySensorId.keys.toList()) return

        chartContainer.removeAllViews()
        chartsBySensorId.clear()
        sensors.forEach { sensor ->
            val chart = LineChart(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    val marginPx = (8 * context.resources.displayMetrics.density).toInt()
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
            }
            LineChartSetup.configure(chart, context, sensor.roomName, xAxisValueFormatter)
            chart.axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${value.toInt()}°"
            }
            chartContainer.addView(chart)
            chartsBySensorId[sensor.id] = chart
        }
    }

    fun render(sensorId: String, points: List<Pair<Long, Double>>) {
        val chart = chartsBySensorId[sensorId] ?: return
        LineChartSetup.render(chart, context, points)
    }
}
