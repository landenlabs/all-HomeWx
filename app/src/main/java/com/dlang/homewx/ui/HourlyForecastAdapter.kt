package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.databinding.ItemForecastDayHeaderCardBinding
import com.dlang.homewx.databinding.ItemForecastHourlyCardBinding
import com.dlang.homewx.weather.HourlyForecastEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val VIEW_TYPE_DAY_HEADER = 0
private const val VIEW_TYPE_HOUR = 1

/** One row in the hourly grid: either a day-of-week/date divider or an hour's card. [dayParity]
 *  (0/1, alternating per calendar day) drives the alternating card background. */
private sealed class HourlyRow {
    abstract val dayParity: Int
    data class DayHeader(val dateMillis: Long, override val dayParity: Int) : HourlyRow()
    data class Hour(val entry: HourlyForecastEntry, override val dayParity: Int) : HourlyRow()
}

/**
 * Hourly cards are grouped by calendar day, with a day-of-week/date divider card inserted
 * before each day's hours. Every card belonging to a day shares one of two alternating
 * backgrounds so the transition between days reads clearly while scrolling. On top of that tint,
 * cards tied for the highest precipitation chance of the whole set (when that chance is >= 50%)
 * get a blue border. Failing that, cards tied for the highest temp of the whole set get yellow
 * temp text plus a yellow border. Failing that, cards tied for the lowest temp get purple temp
 * text plus a purple border. Only the first matching condition's border is drawn.
 */
class HourlyForecastAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HourlyRow> = emptyList()
    private var maxTempRounded: Int? = null
    private var minTempRounded: Int? = null
    private var maxPrecipPct: Int? = null
    private val hourFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    fun submit(newHours: List<HourlyForecastEntry>) {
        val newRows = buildRows(newHours)
        if (newRows == rows) return
        rows = newRows
        maxTempRounded = newHours.mapNotNull { it.temperatureF?.roundToInt() }.maxOrNull()
        minTempRounded = newHours.mapNotNull { it.temperatureF?.roundToInt() }.minOrNull()
        maxPrecipPct = newHours.mapNotNull { it.precipitationChancePct }.maxOrNull()
        notifyDataSetChanged()
    }

    private fun buildRows(hours: List<HourlyForecastEntry>): List<HourlyRow> {
        val calendar = Calendar.getInstance()
        val result = mutableListOf<HourlyRow>()
        var currentDayKey = -1
        var dayIndex = -1
        for (entry in hours) {
            calendar.timeInMillis = entry.timeMillis
            val dayKey = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
            if (dayKey != currentDayKey) {
                currentDayKey = dayKey
                dayIndex++
                result.add(HourlyRow.DayHeader(entry.timeMillis, dayIndex % 2))
            }
            result.add(HourlyRow.Hour(entry, dayIndex % 2))
        }
        return result
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is HourlyRow.DayHeader -> VIEW_TYPE_DAY_HEADER
        is HourlyRow.Hour -> VIEW_TYPE_HOUR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_DAY_HEADER) {
            val binding = ItemForecastDayHeaderCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            DayHeaderViewHolder(binding, dayOfWeekFormat, dateFormat)
        } else {
            val binding = ItemForecastHourlyCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            HourViewHolder(binding, hourFormat)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HourlyRow.DayHeader -> (holder as DayHeaderViewHolder).bind(row)
            is HourlyRow.Hour -> (holder as HourViewHolder).bind(
                row.entry, row.dayParity, maxTempRounded, minTempRounded, maxPrecipPct
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    private class DayHeaderViewHolder(
        private val binding: ItemForecastDayHeaderCardBinding,
        private val dayOfWeekFormat: SimpleDateFormat,
        private val dateFormat: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: HourlyRow.DayHeader) {
            val date = Date(row.dateMillis)
            binding.dayHeaderDayOfWeekText.text = dayOfWeekFormat.format(date)
            binding.dayHeaderDateText.text = dateFormat.format(date)
            binding.root.setBackgroundResource(dayBackgroundRes(row.dayParity))
        }
    }

    private class HourViewHolder(
        private val binding: ItemForecastHourlyCardBinding,
        private val hourFormat: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            entry: HourlyForecastEntry,
            dayParity: Int,
            maxTempRounded: Int?,
            minTempRounded: Int?,
            maxPrecipPct: Int?
        ) {
            val context = binding.root.context
            binding.hourlyCardTimeText.text = hourFormat.format(Date(entry.timeMillis))
            binding.hourlyCardIcon.setImageResource(context.weatherIconRes(entry.iconKey))

            val tempRounded = entry.temperatureF?.roundToInt()
            binding.hourlyCardTempText.text = tempRounded?.let { "$it°F" } ?: "--"
            val isMaxTemp = tempRounded != null && tempRounded == maxTempRounded
            val isMinTemp = tempRounded != null && tempRounded == minTempRounded
            binding.hourlyCardTempText.setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        isMaxTemp -> R.color.accent_warm
                        isMinTemp -> R.color.accent_purple
                        else -> R.color.text_primary
                    }
                )
            )

            binding.hourlyCardWindText.text = entry.windSpeedMph?.roundToInt()?.let { "$it mph" } ?: "--"

            val precipChance = entry.precipitationChancePct
            binding.hourlyCardPrecipText.text = precipChance?.let { "$it%" } ?: "--"
            binding.hourlyCardPrecipIcon.visibility = if (precipChance != null && precipChance > 0) View.VISIBLE else View.GONE
            val isMaxPrecip = precipChance != null && maxPrecipPct != null &&
                precipChance == maxPrecipPct && maxPrecipPct >= 50

            binding.root.setBackgroundResource(
                when {
                    isMaxPrecip -> precipAlertBackgroundRes(dayParity)
                    isMaxTemp -> maxTempAlertBackgroundRes(dayParity)
                    isMinTemp -> minTempAlertBackgroundRes(dayParity)
                    else -> dayBackgroundRes(dayParity)
                }
            )
        }
    }
}

private fun dayBackgroundRes(dayParity: Int): Int =
    if (dayParity == 0) R.drawable.bg_forecast_card else R.drawable.bg_forecast_card_alt

private fun precipAlertBackgroundRes(dayParity: Int): Int =
    if (dayParity == 0) R.drawable.bg_forecast_card_precip_alert else R.drawable.bg_forecast_card_precip_alert_alt

private fun maxTempAlertBackgroundRes(dayParity: Int): Int =
    if (dayParity == 0) R.drawable.bg_forecast_card_max_temp_alert else R.drawable.bg_forecast_card_max_temp_alert_alt

private fun minTempAlertBackgroundRes(dayParity: Int): Int =
    if (dayParity == 0) R.drawable.bg_forecast_card_min_temp_alert else R.drawable.bg_forecast_card_min_temp_alert_alt
