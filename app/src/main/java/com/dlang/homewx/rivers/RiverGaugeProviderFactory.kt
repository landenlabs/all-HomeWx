package com.dlang.homewx.rivers

import com.dlang.homewx.rivers.usgs.UsgsRiverGaugeProvider

object RiverGaugeProviderFactory {
    fun create(source: RiverGaugeSourceId): RiverGaugeProvider = when (source) {
        RiverGaugeSourceId.USGS -> UsgsRiverGaugeProvider()
    }
}
