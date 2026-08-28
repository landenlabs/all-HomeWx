package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dlang.homewx.databinding.PanelSensorGraphsBinding
import com.dlang.homewx.model.SensorReading

/** One [SensorHistoryChartView] per currently-visible sensor, sharing equal height like
 *  [ForecastGraphsPanel]'s Past range. Inflates itself into [container]. */
class SensorGraphsPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelSensorGraphsBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root
    private val chartContainer = binding.root
    private val density = context.resources.displayMetrics.density

    private val chartsBySensorId = mutableMapOf<String, SensorHistoryChartView>()

    init {
        container.addView(root)
    }

    /** Rebuilds one chart per sensor, replacing whatever was there before, when the set of
     *  visible sensors (by id, in order) has changed since the last call. Either way, refreshes
     *  the current-value/room-name text below each chart, since those change every refresh even
     *  when the sensor set itself doesn't. */
    fun setSensors(sensors: List<SensorReading>) {
        if (sensors.map { it.id } != chartsBySensorId.keys.toList()) {
            rebuild(sensors)
        }
        sensors.forEach { sensor ->
            chartsBySensorId[sensor.id]?.setRoomNameAndCurrentValues(sensor.roomName, sensor.tempF, sensor.humidityPct)
        }
    }

    private fun rebuild(sensors: List<SensorReading>) {
        chartContainer.removeAllViews()
        chartsBySensorId.clear()
        val marginPx = (8 * density).toInt()

        sensors.forEach { sensor ->
            val chartView = SensorHistoryChartView(context)
            chartView.view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            chartContainer.addView(chartView.view)
            chartsBySensorId[sensor.id] = chartView
        }
    }

    fun render(sensorId: String, tempPoints: List<Pair<Long, Double>>, humidityPoints: List<Pair<Long, Double>>) {
        chartsBySensorId[sensorId]?.renderHistory(tempPoints, humidityPoints)
    }
}
