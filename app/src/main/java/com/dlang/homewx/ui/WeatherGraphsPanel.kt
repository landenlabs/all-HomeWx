package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dlang.homewx.R
import com.dlang.homewx.data.WeatherMetricsPoint
import com.dlang.homewx.databinding.PanelWeatherGraphsBinding
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wind speed / precipitation / pressure history charts. Inflates itself into [container]. */
class WeatherGraphsPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelWeatherGraphsBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root

    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val xAxisValueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = hourOnlyFormat.format(Date(value.toLong() * 1000L))
    }

    init {
        container.addView(root)
        LineChartSetup.configure(binding.windSpeedChartView, context, context.getString(R.string.weather_graph_wind_speed), xAxisValueFormatter)
        LineChartSetup.configure(binding.precipitationChartView, context, context.getString(R.string.weather_graph_precipitation), xAxisValueFormatter)
        LineChartSetup.configure(binding.pressureChartView, context, context.getString(R.string.weather_graph_pressure), xAxisValueFormatter)
    }

    fun render(points: List<WeatherMetricsPoint>) {
        LineChartSetup.render(
            binding.windSpeedChartView,
            context,
            points.mapNotNull { p -> p.windSpeedMph?.let { p.timestampMillis to it } }
        )
        LineChartSetup.render(
            binding.precipitationChartView,
            context,
            points.mapNotNull { p -> p.precipitationIn?.let { p.timestampMillis to it } }
        )
        LineChartSetup.render(
            binding.pressureChartView,
            context,
            points.mapNotNull { p -> p.pressureInHg?.let { p.timestampMillis to it } }
        )
    }
}
