package com.dlang.homewx.rivers

import com.dlang.homewx.weather.GeoLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RiverGaugeRepository(
    private val activeSource: () -> RiverGaugeSourceId = { RiverGaugeSourceId.USGS },
    private val providerFactory: (RiverGaugeSourceId) -> RiverGaugeProvider = RiverGaugeProviderFactory::create
) {
    // See WeatherRepository.currentProvider() - same reasoning: reuse one provider (and its
    // OkHttpClient) per source instead of leaking a fresh connection pool on every call.
    private val providers = mutableMapOf<RiverGaugeSourceId, RiverGaugeProvider>()
    private fun currentProvider(): RiverGaugeProvider =
        providers.getOrPut(activeSource()) { providerFactory(activeSource()) }

    suspend fun geocodeZip(zip: String): GeoLocation = withContext(Dispatchers.IO) {
        currentProvider().geocodeZip(zip)
    }

    suspend fun findNearestGauges(location: GeoLocation, maxResults: Int = 5): List<GaugeSite> =
        withContext(Dispatchers.IO) {
            currentProvider().findNearestGauges(location, maxResults)
        }

    suspend fun getLatestReading(site: GaugeSite): GaugeReading = withContext(Dispatchers.IO) {
        currentProvider().getLatestReading(site)
    }

    suspend fun getHistory(site: GaugeSite, sinceMillis: Long): List<GaugeReading> = withContext(Dispatchers.IO) {
        currentProvider().getHistory(site, sinceMillis)
    }
}
