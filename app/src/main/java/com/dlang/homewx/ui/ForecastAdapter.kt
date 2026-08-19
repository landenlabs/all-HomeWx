package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.databinding.ItemForecastCardBinding
import com.dlang.homewx.weather.DailyForecastEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ForecastAdapter : RecyclerView.Adapter<ForecastAdapter.ViewHolder>() {

    private var days: List<DailyForecastEntry> = emptyList()
    private val dayFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())

    fun submit(newDays: List<DailyForecastEntry>) {
        if (newDays == days) return
        days = newDays
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemForecastCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, dayFormat)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(days[position])

    override fun getItemCount(): Int = days.size

    class ViewHolder(
        private val binding: ItemForecastCardBinding,
        private val dayFormat: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DailyForecastEntry) {
            binding.forecastCardDateText.text = dayFormat.format(Date(entry.dateMillis))
            binding.forecastCardIcon.setImageResource(binding.forecastCardIcon.context.weatherIconRes(entry.iconKey))
            binding.forecastCardHighText.text = entry.highF?.roundToInt()?.let { "$it°F" } ?: "--"
            binding.forecastCardWindText.text = entry.windMaxMph?.roundToInt()?.let { "$it mph" } ?: "--"
            binding.forecastCardPrecipText.text = entry.precipitationChancePct?.let { "$it%" } ?: "--"
            binding.forecastCardLowText.text = entry.lowF?.roundToInt()?.let { "$it°F" } ?: "--"
        }
    }
}
