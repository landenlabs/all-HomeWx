package com.dlang.homewx.rivers

import com.dlang.homewx.weather.GeoLocation

/**
 * One river-gauge data source (USGS today, kept pluggable like
 * [com.dlang.homewx.weather.WeatherProvider]). Implementations are plain blocking clients -
 * callers must invoke from a background dispatcher, matching [com.dlang.homewx.data.GoveeApiClient].
 */
interface RiverGaugeProvider {
    fun geocodeZip(zip: String): GeoLocation
    fun findNearestGauges(location: GeoLocation, maxResults: Int): List<GaugeSite>
    fun getLatestReading(site: GaugeSite): GaugeReading
    fun getHistory(site: GaugeSite, sinceMillis: Long): List<GaugeReading>
}
