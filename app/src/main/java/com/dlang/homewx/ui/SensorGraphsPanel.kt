package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dlang.homewx.databinding.PanelSensorGraphsBinding
import com.dlang.homewx.model.SensorReading
import java.util.concurrent.TimeUnit

/** One [SensorHistoryChartView] per currently-visible sensor, sharing equal height like
 *  [ForecastGraphsPanel]'s Past range. Inflates itself into [container]. */
class SensorGraphsPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelSensorGraphsBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root
    private val chartContainer = binding.sensorGraphsChartContainer
    private val density = context.resources.displayMetrics.density

    private val chartsBySensorId = mutableMapOf<String, SensorHistoryChartView>()

    /** Full (unfiltered) history last passed to [render], per sensor - re-filtered by [period]
     *  whenever the radio selection changes, so switching periods doesn't need a fresh DB read. */
    private val lastPointsBySensorId = mutableMapOf<String, Pair<List<Pair<Long, Double>>, List<Pair<Long, Double>>>>()
    private var period = SensorGraphPeriod.HOURS_48

    init {
        container.addView(root)
        binding.sensorGraphsPeriodGroup.setOnCheckedChangeListener { _, checkedId ->
            period = when (checkedId) {
                binding.sensorGraphsPeriod24.id -> SensorGraphPeriod.HOURS_24
                binding.sensorGraphsPeriod72.id -> SensorGraphPeriod.HOURS_72
                binding.sensorGraphsPeriodAll.id -> SensorGraphPeriod.ALL
                else -> SensorGraphPeriod.HOURS_48
            }
            lastPointsBySensorId.forEach { (sensorId, points) -> renderFiltered(sensorId, points.first, points.second) }
        }
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
        lastPointsBySensorId.clear()
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

    /** [tempPoints]/[humidityPoints] are the full retained history (not pre-trimmed to any
     *  period) - this filters down to [period] before drawing, and re-checks whether any
     *  sensor's history now spans more than 48h to decide if the period picker should show at
     *  all. */
    fun render(sensorId: String, tempPoints: List<Pair<Long, Double>>, humidityPoints: List<Pair<Long, Double>>) {
        lastPointsBySensorId[sensorId] = tempPoints to humidityPoints
        renderFiltered(sensorId, tempPoints, humidityPoints)

        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48)
        val spansMoreThan48h = lastPointsBySensorId.values.any { (temp, humidity) ->
            (temp + humidity).any { it.first < cutoff }
        }
        binding.sensorGraphsPeriodGroup.visibility = if (spansMoreThan48h) View.VISIBLE else View.GONE
    }

    private fun renderFiltered(sensorId: String, tempPoints: List<Pair<Long, Double>>, humidityPoints: List<Pair<Long, Double>>) {
        val sinceMillis = period.hours?.let { System.currentTimeMillis() - TimeUnit.HOURS.toMillis(it.toLong()) }
        val filteredTemp = if (sinceMillis == null) tempPoints else tempPoints.filter { it.first >= sinceMillis }
        val filteredHumidity = if (sinceMillis == null) humidityPoints else humidityPoints.filter { it.first >= sinceMillis }
        chartsBySensorId[sensorId]?.renderHistory(filteredTemp, filteredHumidity)
    }
}
