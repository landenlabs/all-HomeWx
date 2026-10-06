package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.data.DailySnapshot
import com.dlang.homewx.data.WeatherMetricsPoint
import com.dlang.homewx.databinding.PanelForecastBinding
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.HourlyForecastEntry
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.startOfDay
import com.dlang.homewx.weather.startOfHour
import com.google.android.material.tabs.TabLayout

/** Forecast panel: a 2nd tab bar (Past/Hourly/Daily) plus a cards/graph toggle shared across all
 *  3 ranges. Cards flow into as many columns as fit the available width. Inflates itself into
 *  [container]. Tapping a Past or Daily card previews that card's data in the main weather
 *  panel via [onPastCardClick]/[onDailyCardClick] - this panel only forwards the tap, the
 *  preview binding and auto-revert timer live in [com.dlang.homewx.MainActivity]. */
class ForecastPanel(
    container: ViewGroup,
    onPastCardClick: (WeatherMetricsPoint) -> Unit,
    onDailyCardClick: (DailyForecastEntry) -> Unit
) {

    private val context = container.context
    private val binding = PanelForecastBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root
    private val recyclerView: RecyclerView = binding.forecastCardsRecyclerView

    private val pastAdapter = PastForecastAdapter(onPastCardClick)
    private val dailyAdapter = DailyForecastAdapter(onDailyCardClick)
    private val hourlyAdapter = HourlyForecastAdapter()
    private val layoutManager = GridLayoutManager(context, 1)
    private val cardMinWidthPx = (CARD_MIN_WIDTH_DP * context.resources.displayMetrics.density).toInt()
    private val graphsPanel = ForecastGraphsPanel(binding.forecastGraphsContainer)

    private var range = ForecastRange.DAILY
    private var presentation = ForecastPresentation.CARDS
    private var latestForecast = WeatherForecast(hourly = emptyList(), daily = emptyList())
    private var latestPastPoints: List<WeatherMetricsPoint> = emptyList()
    private var latestRecentDailySnapshots: List<DailySnapshot> = emptyList()

    // Period selection lives here (not in [ForecastGraphsPanel]) since the same radio buttons,
    // sitting above both presentations, filter cards and graph identically - defaults match
    // whichever radio button is checked by default in the layout.
    private var hourlyPeriod = HourlyGraphPeriod.TODAY
    private var dailyPeriod = DailyGraphPeriod.ALL
    private var pastPeriod = PastGraphPeriod.DAYS_7

    init {
        container.addView(root)
        recyclerView.layoutManager = layoutManager
        // Read view.width from a posted Runnable, not directly in the layout-change callback:
        // on the panel's first layout pass the RecyclerView's own children haven't been laid
        // out yet, so computing span count synchronously here used a stale/zero width and
        // rendered a single wide column until some later, unrelated layout pass corrected it.
        recyclerView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.post {
                if (view.width <= 0) return@post
                val columns = (view.width / cardMinWidthPx).coerceAtLeast(1)
                if (layoutManager.spanCount != columns) layoutManager.spanCount = columns
            }
        }

        binding.forecastRangeTabLayout.addTab(
            binding.forecastRangeTabLayout.newTab().setText(R.string.forecast_tab_past).apply { tag = ForecastRange.PAST }
        )
        binding.forecastRangeTabLayout.addTab(
            binding.forecastRangeTabLayout.newTab().setText(R.string.forecast_tab_hourly).apply { tag = ForecastRange.HOURLY }
        )
        binding.forecastRangeTabLayout.addTab(
            binding.forecastRangeTabLayout.newTab().setText(R.string.forecast_tab_daily).apply { tag = ForecastRange.DAILY },
            /* select = */ true
        )
        binding.forecastRangeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                range = tab.tag as ForecastRange
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.forecastViewToggleButton.setOnClickListener {
            presentation = if (presentation == ForecastPresentation.CARDS) ForecastPresentation.GRAPH else ForecastPresentation.CARDS
            refresh()
        }

        binding.forecastHourlyPeriodGroup.setOnCheckedChangeListener { _, checkedId ->
            hourlyPeriod = when (checkedId) {
                binding.forecastHourlyPeriodAll.id -> HourlyGraphPeriod.ALL
                binding.forecastHourlyPeriodPlus24.id -> HourlyGraphPeriod.TWO_DAYS
                else -> HourlyGraphPeriod.TODAY
            }
            refresh()
        }
        binding.forecastDailyPeriodGroup.setOnCheckedChangeListener { _, checkedId ->
            dailyPeriod = when (checkedId) {
                binding.forecastDailyPeriodNow.id -> DailyGraphPeriod.NOW
                binding.forecastDailyPeriodPlus3Day.id -> DailyGraphPeriod.PLUS_3_DAY
                else -> DailyGraphPeriod.ALL
            }
            refresh()
        }
        binding.forecastPastPeriodGroup.setOnCheckedChangeListener { _, checkedId ->
            pastPeriod = when (checkedId) {
                binding.forecastPastPeriod3Day.id -> PastGraphPeriod.DAYS_3
                binding.forecastPastPeriod30Day.id -> PastGraphPeriod.DAYS_30
                binding.forecastPastPeriodAll.id -> PastGraphPeriod.ALL
                else -> PastGraphPeriod.DAYS_7
            }
            refresh()
        }
    }

    fun render(forecast: WeatherForecast, pastPoints: List<WeatherMetricsPoint>, recentDailySnapshots: List<DailySnapshot>) {
        latestForecast = forecast.withTodayBackfilled(pastPoints)
        latestPastPoints = pastPoints
        latestRecentDailySnapshots = recentDailySnapshots
        refresh()
    }

    private fun refresh() {
        val showCards = presentation == ForecastPresentation.CARDS
        binding.forecastViewToggleButton.setImageResource(if (showCards) R.drawable.ic_graph else R.drawable.ic_view_cards)
        binding.forecastViewToggleButton.contentDescription = context.getString(
            if (showCards) R.string.forecast_switch_to_graph_view else R.string.forecast_switch_to_card_view
        )

        recyclerView.visibility = if (showCards) View.VISIBLE else View.GONE
        binding.forecastGraphsContainer.visibility = if (showCards) View.GONE else View.VISIBLE

        // Shared across both presentations - whichever period group matches the selected tab
        // filters cards and graph alike.
        binding.forecastHourlyPeriodGroup.visibility = if (range == ForecastRange.HOURLY) View.VISIBLE else View.GONE
        binding.forecastDailyPeriodGroup.visibility = if (range == ForecastRange.DAILY) View.VISIBLE else View.GONE
        binding.forecastPastPeriodGroup.visibility = if (range == ForecastRange.PAST) View.VISIBLE else View.GONE

        if (showCards) {
            // Reassigning recyclerView.adapter - even to the same instance - makes RecyclerView
            // drop its scroll position, so only touch it when the range actually changed.
            when (range) {
                ForecastRange.PAST -> {
                    if (recyclerView.adapter !== pastAdapter) recyclerView.adapter = pastAdapter
                    pastAdapter.submit(filterPastPointsForPeriod(latestPastPoints, pastPeriod))
                }
                ForecastRange.HOURLY -> {
                    if (recyclerView.adapter !== hourlyAdapter) recyclerView.adapter = hourlyAdapter
                    hourlyAdapter.submit(filterHoursForPeriod(latestForecast.hourly, hourlyPeriod))
                }
                ForecastRange.DAILY -> {
                    if (recyclerView.adapter !== dailyAdapter) recyclerView.adapter = dailyAdapter
                    dailyAdapter.submit(buildDailyEntriesForPeriod(latestForecast.daily, dailyPeriod, latestRecentDailySnapshots))
                }
            }
        } else {
            graphsPanel.render(range, latestForecast, latestPastPoints, latestRecentDailySnapshots, hourlyPeriod, dailyPeriod, pastPeriod)
        }
    }

    companion object {
        private const val CARD_MIN_WIDTH_DP = 200
    }
}

/**
 * Ensures every hour of the current calendar day that's already passed shows the actual
 * recorded average rather than a forecast - always overriding, not just filling gaps: a
 * source's hourly forecast can either be forward-looking only (wxdata's [com.wsi.wxdata.WxTime.comingHours],
 * which has no entry at all for an already-passed hour) or can still return a forecast-model
 * value for it (Open-Meteo returns the whole calendar day, including hours before now); either
 * way, once an hour has actually happened, its real recorded data wins over whatever the
 * forecast guessed for it. Hours from now onward are left untouched. [recordedPoints] is the
 * same fine-grained (multiple samples/hour) history the Past graph plots; each replaced hour is
 * the average of whatever samples landed in it. Precipitation chance has no recorded equivalent
 * - only actual inches are recorded, not a forecast probability - so replaced hours leave it
 * null, same as an hour with no data at all.
 */
private fun WeatherForecast.withTodayBackfilled(recordedPoints: List<WeatherMetricsPoint>): WeatherForecast {
    val now = System.currentTimeMillis()
    val todayStart = startOfDay(now)

    val actualHours = recordedPoints
        .filter { it.timestampMillis in todayStart until now }
        .groupBy { startOfHour(it.timestampMillis) }
        .mapValues { (hourMillis, points) ->
            HourlyForecastEntry(
                timeMillis = hourMillis,
                temperatureF = points.mapNotNull { it.temperatureF }.averageOrNull(),
                windSpeedMph = points.mapNotNull { it.windSpeedMph }.averageOrNull(),
                precipitationChancePct = null,
                pressureInHg = points.mapNotNull { it.pressureInHg }.averageOrNull(),
                conditionText = "",
                iconKey = points.lastOrNull()?.iconKey ?: ""
            )
        }
    if (actualHours.isEmpty()) return this

    val remainingForecastHours = hourly.filterNot { startOfHour(it.timeMillis) in actualHours.keys }
    return copy(hourly = (remainingForecastHours + actualHours.values).sortedBy { it.timeMillis })
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
