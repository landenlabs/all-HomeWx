package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.data.DailySnapshot
import com.dlang.homewx.databinding.ItemForecastTableRowBinding
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.HourlyForecastEntry
import com.dlang.homewx.weather.isSameDay
import com.dlang.homewx.weather.startOfDay
import com.dlang.homewx.weather.startOfHour
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val MISSING = "-"

/** One display-ready row of the forecast table view. [iconKey] is null for an hour with no data at
 *  all. [isNow] marks the current hour/day, which gets a green time label and a tinted background. */
data class ForecastTableRow(
    val timeText: String,
    val iconKey: String?,
    val tempText: String,
    val windText: String,
    val precipText: String,
    @ColorRes val backgroundRes: Int,
    @ColorRes val tempColorRes: Int = R.color.text_primary,
    @ColorRes val windColorRes: Int = R.color.text_primary,
    @ColorRes val precipColorRes: Int = R.color.text_primary,
    val isNow: Boolean = false
)

/**
 * Hourly table rows from midnight of today (or of the first entry's day, if earlier) through the
 * last of [hours]. An hour with no entry - e.g. before the app started recording - is a row of
 * "-" so the gap shows rather than silently shifting the list. Rows alternate between 2 subtle
 * backgrounds per calendar day, and within each day each numeric column's maximum is colored
 * yellow and minimum blue (only when the day has at least 2 distinct values in that column).
 */
fun buildHourlyTableRows(hours: List<HourlyForecastEntry>, nowMillis: Long): List<ForecastTableRow> {
    if (hours.isEmpty()) return emptyList()
    val byHour = hours.associateBy { startOfHour(it.timeMillis) }
    val startMillis = minOf(startOfDay(nowMillis), startOfDay(hours.first().timeMillis))
    val lastHourMillis = startOfHour(hours.last().timeMillis)

    val calendar = Calendar.getInstance().apply { timeInMillis = startMillis }
    val slots = mutableListOf<Pair<Long, HourlyForecastEntry?>>()
    while (calendar.timeInMillis <= lastHourMillis) {
        slots.add(calendar.timeInMillis to byHour[calendar.timeInMillis])
        calendar.add(Calendar.HOUR_OF_DAY, 1)
    }

    val timeFormat = SimpleDateFormat("EEE h a", Locale.getDefault())
    val nowHour = startOfHour(nowMillis)
    val rows = ArrayList<ForecastTableRow>(slots.size)
    var dayIndex = -1
    slots.groupBy { startOfDay(it.first) }.toSortedMap().forEach { (_, daySlots) ->
        dayIndex++
        val entries = daySlots.map { it.second }
        val temps = entries.map { it?.temperatureF?.roundToInt() }
        val winds = entries.map { it?.windSpeedMph?.roundToInt() }
        val precips = entries.map { it?.precipitationChancePct }
        daySlots.forEachIndexed { i, (hourMillis, entry) ->
            rows.add(
                ForecastTableRow(
                    timeText = timeFormat.format(Date(hourMillis)),
                    iconKey = entry?.iconKey?.ifEmpty { null },
                    tempText = temps[i]?.let { "$it°" } ?: MISSING,
                    windText = winds[i]?.let { "$it mph" } ?: MISSING,
                    precipText = precips[i]?.let { "$it%" } ?: MISSING,
                    backgroundRes = if (dayIndex % 2 == 0) R.color.bg_panel_top else R.color.bg_panel_bottom,
                    tempColorRes = extremeColorRes(temps, i),
                    windColorRes = extremeColorRes(winds, i),
                    precipColorRes = extremeColorRes(precips, i),
                    isNow = hourMillis == nowHour
                )
            )
        }
    }
    return rows
}

/** Yellow for the day's highest value in [values], blue for its lowest, otherwise the normal text
 *  color - and normal for everything when the day has fewer than 2 distinct values (a flat run
 *  would otherwise light up every row). */
@ColorRes
private fun extremeColorRes(values: List<Int?>, index: Int): Int {
    val value = values[index] ?: return R.color.text_primary
    val present = values.filterNotNull()
    if (present.distinct().size < 2) return R.color.text_primary
    return when (value) {
        present.max() -> R.color.accent_warm
        present.min() -> R.color.accent_cool
        else -> R.color.text_primary
    }
}

/**
 * Daily table rows: the recorded [pastSnapshots] (high/low, peak wind, rain amount in inches -
 * past days have no saved chance of precipitation), then [currentAndFuture] (today first, then
 * forecast days; chance %). Backgrounds match the weather panel's title colors for past /
 * current / forecast.
 */
fun buildDailyTableRows(
    pastSnapshots: List<DailySnapshot>,
    currentAndFuture: List<DailyForecastEntry>,
    nowMillis: Long
): List<ForecastTableRow> {
    val dayFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())
    fun highLow(high: Double?, low: Double?): String =
        if (high == null && low == null) MISSING
        else "${high?.roundToInt()?.let { "$it°" } ?: MISSING} / ${low?.roundToInt()?.let { "$it°" } ?: MISSING}"

    val past = pastSnapshots.sortedBy { it.dayStartMillis }.map { s ->
        ForecastTableRow(
            timeText = dayFormat.format(Date(s.dayStartMillis)),
            iconKey = s.iconKey?.ifEmpty { null },
            tempText = highLow(s.tempHighF, s.tempLowF),
            windText = (s.windHighMph ?: s.windSpeedMph)?.roundToInt()?.let { "$it mph" } ?: MISSING,
            precipText = s.precipitationIn?.let { "%.2f in".format(it) } ?: MISSING,
            backgroundRes = R.color.weather_title_bg_past
        )
    }
    val rest = currentAndFuture.map { d ->
        val isToday = isSameDay(d.dateMillis, nowMillis)
        ForecastTableRow(
            timeText = dayFormat.format(Date(d.dateMillis)),
            iconKey = d.iconKey.ifEmpty { null },
            tempText = highLow(d.highF, d.lowF),
            windText = d.windMaxMph?.roundToInt()?.let { "$it mph" } ?: MISSING,
            precipText = d.precipitationChancePct?.let { "$it%" } ?: MISSING,
            backgroundRes = if (isToday) R.color.weather_title_bg_current else R.color.weather_title_bg_forecast,
            isNow = isToday
        )
    }
    return past + rest
}

class ForecastTableAdapter : RecyclerView.Adapter<ForecastTableAdapter.ViewHolder>() {

    private var rows: List<ForecastTableRow> = emptyList()

    /** Index of the current hour/day row, or -1 - what the panel scrolls to. */
    val nowIndex: Int get() = rows.indexOfFirst { it.isNow }

    fun submit(newRows: List<ForecastTableRow>) {
        if (newRows == rows) return
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemForecastTableRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    class ViewHolder(private val binding: ItemForecastTableRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ForecastTableRow) {
            val context = binding.root.context
            fun color(res: Int) = ContextCompat.getColor(context, res)
            fun TextView.set(text: String, colorRes: Int) {
                this.text = text
                setTextColor(color(colorRes))
            }

            val background = color(row.backgroundRes)
            binding.root.setBackgroundColor(
                if (row.isNow) ColorUtils.blendARGB(background, color(R.color.accent_day_marker), NOW_TINT) else background
            )
            binding.tableTimeText.set(row.timeText, if (row.isNow) R.color.accent_day_marker else R.color.accent_cool)
            if (row.iconKey == null) {
                binding.tableIcon.visibility = View.INVISIBLE
            } else {
                binding.tableIcon.visibility = View.VISIBLE
                binding.tableIcon.setImageResource(context.weatherIconRes(row.iconKey))
            }
            binding.tableTempText.set(row.tempText, row.tempColorRes)
            binding.tableWindText.set(row.windText, row.windColorRes)
            binding.tablePrecipText.set(row.precipText, row.precipColorRes)
        }
    }

    private companion object {
        /** How much of the "now" green is blended into the current row's background. */
        const val NOW_TINT = 0.22f
    }
}
