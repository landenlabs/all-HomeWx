package com.dlang.homewx.rivers

import com.dlang.homewx.weather.GeoLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RiverGaugeRepository(
    private val activeSource: () -> RiverGaugeSourceId = { RiverGaugeSourceId.USGS },
    private val providerFactory: (RiverGaugeSourceId) -> RiverGaugeProvider = RiverGaugeProviderFactory::create
) {
    suspend fun geocodeZip(zip: String): GeoLocation = withContext(Dispatchers.IO) {
        providerFactory(activeSource()).geocodeZip(zip)
    }

    suspend fun findNearestGauges(location: GeoLocation, maxResults: Int = 5): List<GaugeSite> =
        withContext(Dispatchers.IO) {
            providerFactory(activeSource()).findNearestGauges(location, maxResults)
        }

    suspend fun getLatestReading(site: GaugeSite): GaugeReading = withContext(Dispatchers.IO) {
        providerFactory(activeSource()).getLatestReading(site)
    }

    suspend fun getHistory(site: GaugeSite, sinceMillis: Long): List<GaugeReading> = withContext(Dispatchers.IO) {
        providerFactory(activeSource()).getHistory(site, sinceMillis)
    }
}
