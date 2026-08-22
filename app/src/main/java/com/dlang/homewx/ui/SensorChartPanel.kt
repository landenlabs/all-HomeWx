package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelSensorChartBinding
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single sensor's temperature history strip chart. Inflates itself into [container]. */
class SensorChartPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelSensorChartBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root

    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())

    init {
        container.addView(root)
        LineChartSetup.configure(
            binding.stripChartView,
            context,
            description = null,
            xAxisValueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = hourOnlyFormat.format(Date(value.toLong() * 1000L))
            }
        )
        binding.stripChartView.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = "${value.toInt()}°"
        }
        LineChartSetup.setThresholdLines(binding.stripChartView, context, listOf(100f to R.color.white, 32f to R.color.blue2))
    }

    fun setTitle(title: String) {
        binding.stripChartTitleText.text = title
    }

    fun render(points: List<Pair<Long, Double>>) {
        LineChartSetup.render(binding.stripChartView, context, points)
    }
}
