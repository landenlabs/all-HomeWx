package com.dlang.homewx.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelSensorGraphsBinding
import com.dlang.homewx.model.SensorReading
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One temperature+humidity history chart per currently-visible sensor, sharing equal height
 *  like [ForecastGraphsPanel]'s Past range. Inflates itself into [container]. */
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
            val marginPx = (8 * context.resources.displayMetrics.density).toInt()

            // Sensor name is shown as a large, faint watermark centered behind the chart
            // instead of the small top-corner description label, so it reads at a glance
            // without competing with the plotted lines.
            val watermark = TextView(context).apply {
                text = sensor.roomName
                gravity = Gravity.CENTER
                maxLines = 2
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_secondary), 75))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }

            val chart = LineChart(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            LineChartSetup.configure(chart, context, description = null, xAxisValueFormatter)
            chart.axisLeft.apply {
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = "${value.toInt()}°"
                }
                textColor = ContextCompat.getColor(context, R.color.accent_warm)
            }
            LineChartSetup.enableRightAxis(
                chart,
                textColor = ContextCompat.getColor(context, R.color.accent_cool),
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = "${value.toInt()}%"
                }
            )
            LineChartSetup.setThresholdLines(chart, context, listOf(100f to R.color.white, 32f to R.color.blue2))
            // Left/right axis colors already distinguish the two series, but the legend spells
            // out which is which since both axes' units ("°" vs "%") look similar at a glance.
            chart.legend.apply {
                isEnabled = true
                textColor = ContextCompat.getColor(context, R.color.text_secondary)
            }

            val wrapper = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
            }
            // Watermark added first so it sits behind the chart, which has a transparent
            // background and shows the watermark through its own empty space.
            wrapper.addView(watermark)
            wrapper.addView(chart)
            chartContainer.addView(wrapper)
            chartsBySensorId[sensor.id] = chart
        }
    }

    fun render(sensorId: String, tempPoints: List<Pair<Long, Double>>, humidityPoints: List<Pair<Long, Double>>) {
        val chart = chartsBySensorId[sensorId] ?: return
        LineChartSetup.renderDualAxis(
            chart,
            context,
            leftSeries = tempPoints,
            leftColorRes = R.color.accent_warm,
            leftLabel = "Temp",
            rightSeries = humidityPoints,
            rightColorRes = R.color.accent_cool,
            rightLabel = "Humidity"
        )
    }
}
