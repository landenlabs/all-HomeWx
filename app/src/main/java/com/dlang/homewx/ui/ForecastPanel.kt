package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.data.DailySnapshot
import com.dlang.homewx.data.WeatherMetricsPoint
import com.dlang.homewx.databinding.PanelForecastBinding
import com.dlang.homewx.rain.RainRepository
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.HourlyForecastEntry
import com.dlang.homewx.weather.HomeLocation
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.isSameDay
import com.dlang.homewx.weather.startOfDay
import com.dlang.homewx.weather.startOfHour
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Forecast panel: a 2nd tab bar (Past/Hourly/Daily/Rain) plus a cards/graph toggle shared across
 *  the first 3 ranges (Rain is always its weekly bar chart). Cards flow into as many columns as fit the available width. Inflates itself into
 *  [container]. Tapping a Past or Daily card previews that card's data in the main weather
 *  panel via [onPastCardClick]/[onDailyCardClick] - this panel only forwards the tap, the
 *  preview binding and auto-revert timer live in [com.dlang.homewx.MainActivity]. */
class ForecastPanel(
    container: ViewGroup,
    private val scope: CoroutineScope,
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
    private val tableAdapter = ForecastTableAdapter()
    private val tableLayoutManager = LinearLayoutManager(context)
    private val rainPanel = RainChartPanel(context, binding)
    private val rainRepository = RainRepository()
    private var rainJob: Job? = null

    /** What the table was last scrolled-to-now for - it only re-scrolls when this changes (table
     *  newly shown, range or period switched), never on a data refresh, which would yank the list
     *  out from under a user who's scrolled elsewhere. */
    private var lastTableScrollKey: Any? = null

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
        binding.forecastTableRecyclerView.layoutManager = tableLayoutManager
        binding.forecastTableRecyclerView.adapter = tableAdapter
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
        binding.forecastRangeTabLayout.addTab(
            binding.forecastRangeTabLayout.newTab().setText(R.string.forecast_tab_rain).apply { tag = ForecastRange.RAIN }
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
            presentation = if (presentation == ForecastPresentation.GRAPH) ForecastPresentation.CARDS else ForecastPresentation.GRAPH
            refresh()
        }
        binding.forecastTableToggleButton.setOnClickListener {
            presentation = if (presentation == ForecastPresentation.TABLE) ForecastPresentation.CARDS else ForecastPresentation.TABLE
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
        // Rain is always its bar chart, whatever the cards/graph/table toggle last said.
        val showRain = range == ForecastRange.RAIN
        val showCards = presentation == ForecastPresentation.CARDS && !showRain
        val showGraph = presentation == ForecastPresentation.GRAPH && !showRain
        val showTable = presentation == ForecastPresentation.TABLE && !showRain
        val toggleVisibility = if (showRain) View.GONE else View.VISIBLE
        binding.forecastViewToggleButton.visibility = toggleVisibility
        binding.forecastTableToggleButton.visibility = toggleVisibility
        binding.forecastViewToggleButton.setImageResource(if (showGraph) R.drawable.ic_view_cards else R.drawable.ic_graph)
        binding.forecastViewToggleButton.contentDescription = context.getString(
            if (showGraph) R.string.forecast_switch_to_card_view else R.string.forecast_switch_to_graph_view
        )
        binding.forecastTableToggleButton.setImageResource(if (showTable) R.drawable.ic_view_cards else R.drawable.ic_table)
        binding.forecastTableToggleButton.contentDescription = context.getString(
            if (showTable) R.string.forecast_switch_to_card_view else R.string.forecast_switch_to_table_view
        )

        recyclerView.visibility = if (showCards) View.VISIBLE else View.GONE
        binding.forecastGraphsContainer.visibility = if (showGraph) View.VISIBLE else View.GONE
        binding.forecastTableContainer.visibility = if (showTable) View.VISIBLE else View.GONE
        rainPanel.container.visibility = if (showRain) View.VISIBLE else View.GONE

        // Shared across both presentations - whichever period group matches the selected tab
        // filters cards and graph alike.
        binding.forecastHourlyPeriodGroup.visibility = if (range == ForecastRange.HOURLY) View.VISIBLE else View.GONE
        binding.forecastDailyPeriodGroup.visibility = if (range == ForecastRange.DAILY) View.VISIBLE else View.GONE
        binding.forecastPastPeriodGroup.visibility = if (range == ForecastRange.PAST) View.VISIBLE else View.GONE

        if (!showTable) lastTableScrollKey = null
        if (showRain) {
            renderRain()
        } else if (showTable) {
            renderTable()
        } else if (showCards) {
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
                ForecastRange.RAIN -> Unit // handled above
            }
        } else {
            graphsPanel.render(range, latestForecast, latestPastPoints, latestRecentDailySnapshots, hourlyPeriod, dailyPeriod, pastPeriod)
        }
    }

    /** Draws whatever totals are cached right away (so a data refresh never blanks the chart), then
     *  fetches in the background if they're stale. [refresh] runs on every weather update, so a
     *  fetch already in flight is left alone. */
    private fun renderRain() {
        val location = HomeLocation.CURRENT
        val cached = rainRepository.cachedWeeks(location)
        if (cached != null) rainPanel.render(cached) else rainPanel.showStatus(context.getString(R.string.forecast_rain_loading))
        if (rainRepository.isFresh(location) || rainJob?.isActive == true) return

        rainJob = scope.launch {
            try {
                val weeks = rainRepository.refresh(location)
                if (range == ForecastRange.RAIN) rainPanel.render(weeks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing stale totals if there are any; only an empty chart needs the error.
                if (range == ForecastRange.RAIN && rainRepository.cachedWeeks(location) == null) {
                    rainPanel.showStatus(context.getString(R.string.forecast_rain_error, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Hourly range -> an hourly table; Past and Daily -> the daily table (past 7 days, today,
     *  then forecast days), since the past days only exist as one recorded summary per day. */
    private fun renderTable() {
        val nowMillis = System.currentTimeMillis()
        val hourly = range == ForecastRange.HOURLY
        binding.forecastTableTempHeader.setText(
            if (hourly) R.string.forecast_table_header_temp else R.string.forecast_table_header_temp_high_low
        )
        val rows = if (hourly) {
            buildHourlyTableRows(filterHoursForPeriod(latestForecast.hourly, hourlyPeriod), nowMillis)
        } else {
            val entries = buildDailyEntriesForPeriod(latestForecast.daily, dailyPeriod, latestRecentDailySnapshots)
            // buildDailyEntriesForPeriod's NOW entry is today built from live extremes - the
            // best "today" available - so reuse it for today under every period.
            val today = buildDailyEntriesForPeriod(latestForecast.daily, DailyGraphPeriod.NOW, latestRecentDailySnapshots)
                .first()
            val future = entries.filter { it.dateMillis > today.dateMillis && !isSameDay(it.dateMillis, today.dateMillis) }
            buildDailyTableRows(latestRecentDailySnapshots, listOf(today) + future, nowMillis)
        }
        tableAdapter.submit(rows)

        val scrollKey = range to (if (hourly) hourlyPeriod else dailyPeriod)
        if (scrollKey != lastTableScrollKey) {
            lastTableScrollKey = scrollKey
            binding.forecastTableRecyclerView.post {
                val index = tableAdapter.nowIndex
                if (index >= 0) tableLayoutManager.scrollToPositionWithOffset(index, NOW_ROW_SCROLL_OFFSET_PX)
            }
        }
    }

    /** Called when this panel is made visible - see [ForecastGraphsPanel.resetView]. */
    fun resetGraphView() {
        graphsPanel.resetView()
        lastTableScrollKey = null
    }

    companion object {
        private const val CARD_MIN_WIDTH_DP = 200

        /** Leaves a little context above the current row when scrolling it into view. */
        private const val NOW_ROW_SCROLL_OFFSET_PX = 120
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
 * the average of whatever samples landed in it. Precipitation chance isn't an observation, so
 * it's the forecast chance that was in effect when each sample was recorded (the latest one in the
 * hour); null only for hours recorded before that was tracked.
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
                precipitationChancePct = points.mapNotNull { it.precipChancePct }.lastOrNull(),
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
