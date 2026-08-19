package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dlang.homewx.databinding.PanelForecastBinding
import com.dlang.homewx.weather.DailyForecastEntry

/** Weekly forecast: one card per day, flowing into as many columns as fit the available
 *  width. Inflates itself into [container]. */
class ForecastPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelForecastBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root
    private val recyclerView: RecyclerView = binding.root

    private val adapter = ForecastAdapter()
    private val layoutManager = GridLayoutManager(context, 1)
    private val cardMinWidthPx = (CARD_MIN_WIDTH_DP * context.resources.displayMetrics.density).toInt()

    init {
        container.addView(root)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val columns = (view.width / cardMinWidthPx).coerceAtLeast(1)
            if (layoutManager.spanCount != columns) layoutManager.spanCount = columns
        }
    }

    fun render(days: List<DailyForecastEntry>) {
        adapter.submit(days)
    }

    companion object {
        private const val CARD_MIN_WIDTH_DP = 200
    }
}
