package com.dlang.homewx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dlang.homewx.databinding.PanelForecastBinding

/** Weekly forecast panel. Placeholder until the real presentation is designed. Inflates itself into [container]. */
class ForecastPanel(container: ViewGroup) {

    private val binding = PanelForecastBinding.inflate(LayoutInflater.from(container.context), container, false)
    val root: View get() = binding.root

    init {
        container.addView(root)
    }
}
