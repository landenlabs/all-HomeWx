package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.dlang.homewx.databinding.PanelSensorChartBinding

/** A single [SensorHistoryChartView], shown when a sensor row is tapped on the weather panel.
 *  Inflates itself into [container]. */
class SensorChartPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelSensorChartBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root

    private val chartView = SensorHistoryChartView(context)

    init {
        container.addView(root)
        chartView.view.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        binding.root.addView(chartView.view)
    }

    fun setSensor(roomName: String, tempF: Double?, humidityPct: Double?) {
        chartView.setRoomNameAndCurrentValues(roomName, tempF, humidityPct)
    }

    fun render(tempPoints: List<Pair<Long, Double>>, humidityPoints: List<Pair<Long, Double>>) {
        chartView.renderHistory(tempPoints, humidityPoints)
    }
}
