package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.data.WeatherMetricsPoint
import com.dlang.homewx.databinding.ItemForecastPastCardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Recorded weather-metrics history cards: temperature, condition icon, wind, precipitation
 *  (with the same drop icon the forecast cards use, shown whenever any rain was recorded) and
 *  pressure. Older rows recorded before the history store tracked temperature/icon will have
 *  nulls for those - render as best-effort ("--" / the fallback icon) rather than hiding the
 *  whole card. */
class PastForecastAdapter(private val onItemClick: (WeatherMetricsPoint) -> Unit) : RecyclerView.Adapter<PastForecastAdapter.ViewHolder>() {

    private var points: List<WeatherMetricsPoint> = emptyList()
    private val timeFormat = SimpleDateFormat("EEE h:mm a", Locale.getDefault())

    fun submit(newPoints: List<WeatherMetricsPoint>) {
        if (newPoints == points) return
        points = newPoints
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemForecastPastCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, timeFormat)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(points[position], onItemClick)

    override fun getItemCount(): Int = points.size

    class ViewHolder(
        private val binding: ItemForecastPastCardBinding,
        private val timeFormat: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(point: WeatherMetricsPoint, onItemClick: (WeatherMetricsPoint) -> Unit) {
            binding.root.setOnClickListener { onItemClick(point) }
            binding.pastCardTimeText.text = timeFormat.format(Date(point.timestampMillis))
            binding.pastCardIcon.setImageResource(
                point.iconKey?.let { binding.pastCardIcon.context.weatherIconRes(it) } ?: R.drawable.wx_sun_44d
            )
            binding.pastCardTempText.text = point.temperatureF?.roundToInt()?.let { "$it°F" } ?: "--"
            binding.pastCardWindText.text = point.windSpeedMph?.roundToInt()?.let { "$it mph" } ?: "--"

            val precipIn = point.precipitationIn
            binding.pastCardPrecipText.text = precipIn?.let { "%.2f in".format(it) } ?: "--"
            binding.pastCardPrecipIcon.visibility = if (precipIn != null && precipIn > 0) View.VISIBLE else View.GONE

            binding.pastCardPressureText.text = point.pressureInHg?.let { "%.2f inHg".format(it) } ?: "--"
        }
    }
}
