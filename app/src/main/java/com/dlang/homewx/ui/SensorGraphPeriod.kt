package com.dlang.homewx.ui

/** How much of the recorded sensor history [SensorGraphsPanel] plots - selected via the radio
 *  buttons above the charts, only shown when there's more than 48h of history to trim. [hours]
 *  is null for ALL, meaning show everything the store has retained
 *  ([com.dlang.homewx.data.SensorHistoryStore]'s retention window). */
enum class SensorGraphPeriod(val hours: Int?) { HOURS_24(24), HOURS_48(48), HOURS_72(72), ALL(null) }
