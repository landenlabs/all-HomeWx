package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.databinding.ItemSensorReadingBinding
import com.dlang.homewx.model.SensorReading
import kotlin.math.roundToInt

class SensorAdapter : RecyclerView.Adapter<SensorAdapter.ViewHolder>() {

    private var readings: List<SensorReading> = emptyList()

    fun submit(newReadings: List<SensorReading>) {
        readings = newReadings
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemSensorReadingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(readings[position])
    }

    override fun getItemCount(): Int = readings.size

    class ViewHolder(private val binding: ItemSensorReadingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(reading: SensorReading) {
            binding.roomNameText.text = reading.roomName
            binding.tempText.text = reading.tempF?.let { "${it.roundToInt()}°F" } ?: "--"
            binding.humidityText.text = reading.humidityPct?.let { "${formatHumidity(it)}%" } ?: "--"
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
