package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dlang.homewx.databinding.PanelRiverGraphsBinding
import com.dlang.homewx.rivers.GaugeReading
import com.dlang.homewx.rivers.GaugeSite

/** One [RiverGaugeChartView] per currently-selected gauge, same shape as [SensorGraphsPanel]. */
class RiverGraphsPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelRiverGraphsBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root
    private val chartContainer = binding.root
    private val density = context.resources.displayMetrics.density

    private val chartsBySiteId = mutableMapOf<String, RiverGaugeChartView>()

    init {
        container.addView(root)
    }

    /** Rebuilds one chart per gauge, replacing whatever was there before, when the set of
     *  selected gauges (by site id, in order) has changed since the last call. Either way,
     *  refreshes each chart's current-value/gauge-name text, since [latestReadings] changes
     *  every refresh even when the selected gauge set doesn't. */
    fun setGauges(gauges: List<GaugeSite>, latestReadings: Map<String, GaugeReading>) {
        if (gauges.map { it.siteId } != chartsBySiteId.keys.toList()) {
            rebuild(gauges)
        }
        gauges.forEach { gauge ->
            chartsBySiteId[gauge.siteId]?.setGaugeNameAndCurrentValues(gauge.name, latestReadings[gauge.siteId])
        }
    }

    private fun rebuild(gauges: List<GaugeSite>) {
        chartContainer.removeAllViews()
        chartsBySiteId.clear()
        val marginPx = (8 * density).toInt()

        gauges.forEach { gauge ->
            val chartView = RiverGaugeChartView(context)
            chartView.view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            chartContainer.addView(chartView.view)
            chartsBySiteId[gauge.siteId] = chartView
        }
    }

    fun render(siteId: String, levelPoints: List<Pair<Long, Double>>, flowPoints: List<Pair<Long, Double>>) {
        chartsBySiteId[siteId]?.renderHistory(levelPoints, flowPoints)
    }
}
