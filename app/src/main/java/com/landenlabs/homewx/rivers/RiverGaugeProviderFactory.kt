// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.rivers

import com.landenlabs.homewx.rivers.usgs.UsgsRiverGaugeProvider

object RiverGaugeProviderFactory {
    fun create(source: RiverGaugeSourceId): RiverGaugeProvider = when (source) {
        RiverGaugeSourceId.USGS -> UsgsRiverGaugeProvider()
    }
}
