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

/** Cards tied for the highest precipitation chance of the whole set (when that chance is >= 50%)
 *  get a blue border. Failing that, cards tied for the highest high temp get yellow high-temp
 *  text plus a yellow border. Failing that, cards tied for the lowest low temp get purple
 *  low-temp text plus a purple border. Only the first matching condition's border is drawn. */
class DailyForecastAdapter(private val onItemClick: (DailyForecastEntry) -> Unit) : RecyclerView.Adapter<DailyForecastAdapter.ViewHolder>() {

    private var days: List<DailyForecastEntry> = emptyList()
    private var maxHighRounded: Int? = null
    private var minLowRounded: Int? = null
    private var maxPrecipPct: Int? = null
    private val dayFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())

    fun submit(newDays: List<DailyForecastEntry>) {
        if (newDays == days) return
        days = newDays
        maxHighRounded = newDays.mapNotNull { it.highF?.roundToInt() }.maxOrNull()
        minLowRounded = newDays.mapNotNull { it.lowF?.roundToInt() }.minOrNull()
        maxPrecipPct = newDays.mapNotNull { it.precipitationChancePct }.maxOrNull()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemForecastCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, dayFormat)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(days[position], maxHighRounded, minLowRounded, maxPrecipPct, onItemClick)

    override fun getItemCount(): Int = days.size

    class ViewHolder(
        private val binding: ItemForecastCardBinding,
        private val dayFormat: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            entry: DailyForecastEntry,
            maxHighRounded: Int?,
            minLowRounded: Int?,
            maxPrecipPct: Int?,
            onItemClick: (DailyForecastEntry) -> Unit
        ) {
            val context = binding.root.context
            binding.root.setOnClickListener { onItemClick(entry) }
            binding.forecastCardDateText.text = dayFormat.format(Date(entry.dateMillis))
            binding.forecastCardIcon.setImageResource(context.weatherIconRes(entry.iconKey))

            val highRounded = entry.highF?.roundToInt()
            binding.forecastCardHighText.text = highRounded?.let { "$it°F" } ?: "--"
            val isMaxHigh = highRounded != null && highRounded == maxHighRounded
            binding.forecastCardHighText.setTextColor(
                ContextCompat.getColor(context, if (isMaxHigh) R.color.accent_warm else R.color.text_primary)
            )

            binding.forecastCardWindText.text = entry.windMaxMph?.roundToInt()?.let { "$it mph" } ?: "--"

            val lowRounded = entry.lowF?.roundToInt()
            binding.forecastCardLowText.text = lowRounded?.let { "$it°F" } ?: "--"
            val isMinLow = lowRounded != null && lowRounded == minLowRounded
            binding.forecastCardLowText.setTextColor(
                ContextCompat.getColor(context, if (isMinLow) R.color.accent_purple else R.color.text_primary)
            )

            val precipChance = entry.precipitationChancePct
            binding.forecastCardPrecipText.text = precipChance?.let { "$it%" } ?: "--"
            binding.forecastCardPrecipIcon.visibility = if (precipChance != null && precipChance > 0) View.VISIBLE else View.GONE
            val isMaxPrecip = precipChance != null && maxPrecipPct != null &&
                precipChance == maxPrecipPct && maxPrecipPct >= 50
            binding.root.setBackgroundResource(
                when {
                    isMaxPrecip -> R.drawable.bg_forecast_card_precip_alert
                    isMaxHigh -> R.drawable.bg_forecast_card_max_temp_alert
                    isMinLow -> R.drawable.bg_forecast_card_min_temp_alert
                    else -> R.drawable.bg_forecast_card
                }
            )
        }
    }
}
