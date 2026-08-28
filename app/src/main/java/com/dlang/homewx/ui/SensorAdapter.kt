package com.dlang.homewx.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.databinding.ItemSensorReadingBinding
import com.dlang.homewx.model.SensorReading
import kotlin.math.roundToInt

class SensorAdapter(private val onSensorClick: (SensorReading) -> Unit = {}) :
    RecyclerView.Adapter<SensorAdapter.ViewHolder>() {

    private var readings: List<SensorReading> = emptyList()

    /** Id of the sensor whose history chart is currently open, or null - highlighted so it's
     *  obvious which row to tap again to close the chart. */
    var selectedSensorId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(newReadings: List<SensorReading>) {
        readings = newReadings
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemSensorReadingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val reading = readings[position]
        holder.bind(reading, isSelected = reading.id == selectedSensorId)
        holder.itemView.setOnClickListener { onSensorClick(reading) }
    }

    override fun getItemCount(): Int = readings.size

    class ViewHolder(private val binding: ItemSensorReadingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(reading: SensorReading, isSelected: Boolean) {
            binding.root.setBackgroundColor(
                if (isSelected) ContextCompat.getColor(binding.root.context, R.color.sensor_row_selected) else Color.TRANSPARENT
            )
            binding.roomNameText.text = reading.roomName
            binding.tempText.text = reading.tempF?.let { "${it.roundToInt()}°F" } ?: "--"
            binding.humidityText.text = reading.humidityPct?.let { "${formatHumidity(it)}%" } ?: "--"
            binding.tempTrendText.text = reading.tempTrend1hF?.let { "%+d°F".format(it.roundToInt()) }.orEmpty()
            binding.humidityTrendText.text = reading.humidityTrend1hPct?.let { "%+.1f%%".format(it) }.orEmpty()

            if (reading.error != null) {
                val minutes = reading.lastSuccessAtMillis
                    ?.let { ((System.currentTimeMillis() - it) / 60_000L).toInt() }
                    ?.toString() ?: "?"
                val countSuffix = if (reading.failureCountToday > 1) " (${reading.failureCountToday} failures)" else ""
                binding.sensorWarningText.text = "[ No data last $minutes min$countSuffix ]"
                binding.sensorWarningText.visibility = View.VISIBLE
            } else {
                binding.sensorWarningText.visibility = View.GONE
            }
        }

        private fun formatHumidity(value: Double): String {
            val rounded = (value * 10).roundToInt() / 10.0
            return if (rounded == rounded.roundToInt().toDouble()) {
                rounded.roundToInt().toString()
            } else {
                rounded.toString()
            }
        }
    }
}
