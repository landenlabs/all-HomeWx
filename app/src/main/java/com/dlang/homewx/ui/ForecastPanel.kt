package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.R
import com.dlang.homewx.data.WeatherMetricsPoint
import com.dlang.homewx.databinding.PanelForecastBinding
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.WeatherForecast
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
    }

    fun render(forecast: WeatherForecast, pastPoints: List<WeatherMetricsPoint>) {
        latestForecast = forecast
        latestPastPoints = pastPoints
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

        if (showCards) {
            // Reassigning recyclerView.adapter - even to the same instance - makes RecyclerView
            // drop its scroll position, so only touch it when the range actually changed.
            when (range) {
                ForecastRange.PAST -> {
                    if (recyclerView.adapter !== pastAdapter) recyclerView.adapter = pastAdapter
                    pastAdapter.submit(latestPastPoints)
                }
                ForecastRange.HOURLY -> {
                    if (recyclerView.adapter !== hourlyAdapter) recyclerView.adapter = hourlyAdapter
                    hourlyAdapter.submit(latestForecast.hourly)
                }
                ForecastRange.DAILY -> {
                    if (recyclerView.adapter !== dailyAdapter) recyclerView.adapter = dailyAdapter
                    dailyAdapter.submit(latestForecast.daily)
                }
            }
        } else {
            graphsPanel.render(range, latestForecast, latestPastPoints)
        }
    }

    companion object {
        private const val CARD_MIN_WIDTH_DP = 200
    }
}
