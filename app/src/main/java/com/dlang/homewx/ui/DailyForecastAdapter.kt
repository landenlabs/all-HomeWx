package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.databinding.ItemForecastCardBinding
import com.dlang.homewx.weather.DailyForecastEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Cards flag a >70% chance of rain with a blue border, and mark whichever card(s) hold the
 *  highest high temp of the whole set with yellow high-temp text plus a yellow border - unless
 *  that same card also gets the precip border, which takes priority. */
class DailyForecastAdapter : RecyclerView.Adapter<DailyForecastAdapter.ViewHolder>() {

    private var days: List<DailyForecastEntry> = emptyList()
    private var maxHighRounded: Int? = null
    private val dayFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())

    fun submit(newDays: List<DailyForecastEntry>) {
        if (newDays == days) return
        days = newDays
        maxHighRounded = newDays.mapNotNull { it.highF?.roundToInt() }.maxOrNull()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemForecastCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, dayFormat)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(days[position], maxHighRounded)

    override fun getItemCount(): Int = days.size

    class ViewHolder(
        private val binding: ItemForecastCardBinding,
        private val dayFormat: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DailyForecastEntry, maxHighRounded: Int?) {
            val context = binding.root.context
            binding.forecastCardDateText.text = dayFormat.format(Date(entry.dateMillis))
            binding.forecastCardIcon.setImageResource(context.weatherIconRes(entry.iconKey))

            val highRounded = entry.highF?.roundToInt()
            binding.forecastCardHighText.text = highRounded?.let { "$it°F" } ?: "--"
            val isMaxHigh = highRounded != null && highRounded == maxHighRounded
            binding.forecastCardHighText.setTextColor(
                ContextCompat.getColor(context, if (isMaxHigh) R.color.accent_warm else R.color.text_primary)
            )

            binding.forecastCardWindText.text = entry.windMaxMph?.roundToInt()?.let { "$it mph" } ?: "--"
            binding.forecastCardLowText.text = entry.lowF?.roundToInt()?.let { "$it°F" } ?: "--"

            val precipChance = entry.precipitationChancePct
            binding.forecastCardPrecipText.text = precipChance?.let { "$it%" } ?: "--"
            binding.forecastCardPrecipIcon.visibility = if (precipChance != null && precipChance > 0) View.VISIBLE else View.GONE
            binding.root.setBackgroundResource(
                when {
                    precipChance != null && precipChance > 70 -> R.drawable.bg_forecast_card_precip_alert
                    isMaxHigh -> R.drawable.bg_forecast_card_max_temp_alert
                    else -> R.drawable.bg_forecast_card
                }
            )
        }
    }
}
