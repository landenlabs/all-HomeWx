package com.dlang.homewx.rivers.usgs

import com.dlang.homewx.rivers.GaugeReading
import com.dlang.homewx.rivers.GaugeSite
import com.dlang.homewx.rivers.RiverGaugeProvider
import com.dlang.homewx.rivers.RiverGaugeProviderException
import com.dlang.homewx.weather.GeoLocation
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Free, keyless river-gauge source: USGS's REST/GeoJSON Water Data API
 * (https://api.waterdata.usgs.gov/ogcapi/v0) for site search + readings, plus
 * api.zippopotam.us for zip -> lat/long (USGS has no zip lookup of its own).
 * Blocking network calls - callers must invoke from a background dispatcher.
 */
class UsgsRiverGaugeProvider : RiverGaugeProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Parses response timestamps - USGS returns either a "Z" or a numeric "+00:00" offset. */
    private val isoResponseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    /** Formats our own request timestamps - always UTC, so a literal "Z" is fine here. */
    private val isoRequestFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun geocodeZip(zip: String): GeoLocation {
        val request = Request.Builder().url("$ZIPPOPOTAM_URL/$zip").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw RiverGaugeProviderException("Zip lookup failed for $zip: HTTP ${response.code} $body")
            }
            val place = JSONObject(body).optJSONArray("places")?.optJSONObject(0)
                ?: throw RiverGaugeProviderException("Zip lookup returned no places for $zip")
            return GeoLocation(
                latitude = place.getString("latitude").toDouble(),
                longitude = place.getString("longitude").toDouble(),
                label = place.optString("place name", zip)
            )
        }
    }

    override fun findNearestGauges(location: GeoLocation, maxResults: Int): List<GaugeSite> {
        // Most USGS "stream" sites in a given area are historical/periodic, not live-telemetry
        // gauges - verified against the live API, a 10-mile box around a real address turned up
        // 100+ candidate stream sites but only ~7 with actual live 00060/00065 data. So this
        // expands by live-gauge count, not raw candidate count - a box with plenty of candidates
        // can still come up short on ones that actually report anything right now.
        var deltaDegrees = INITIAL_BBOX_DELTA_DEGREES
        var found = searchAtRadius(location, deltaDegrees, maxResults)
        while (found.size < maxResults && deltaDegrees < MAX_BBOX_DELTA_DEGREES) {
            deltaDegrees *= 2
            found = searchAtRadius(location, deltaDegrees, maxResults)
        }
        return found
    }

    private fun searchAtRadius(location: GeoLocation, deltaDegrees: Double, maxResults: Int): List<GaugeSite> {
        val candidates = fetchStreamSites(location, deltaDegrees)
        if (candidates.isEmpty()) return emptyList()

        // Checked nearest-first, capped so the batched availability call below stays a
        // reasonably sized URL even when a box contains hundreds of candidate stream sites.
        val nearest = candidates
            .map { it to haversineMiles(location.latitude, location.longitude, it.latitude, it.longitude) }
            .sortedBy { it.second }
            .take(AVAILABILITY_CHECK_LIMIT)

        val availability = fetchLatestByParameter(nearest.map { it.first.siteId })

        return nearest.mapNotNull { (site, distanceMiles) ->
            val params = availability[site.siteId].orEmpty()
            val hasGageHeight = params.containsKey(GAGE_HEIGHT_PARAM)
            val hasDischarge = params.containsKey(DISCHARGE_PARAM)
            if (!hasGageHeight && !hasDischarge) return@mapNotNull null
            GaugeSite(
                siteId = site.siteId,
                name = site.name,
                latitude = site.latitude,
                longitude = site.longitude,
                hasGageHeight = hasGageHeight,
                hasDischarge = hasDischarge,
                distanceMiles = distanceMiles
            )
        }.take(maxResults)
    }

    override fun getLatestReading(site: GaugeSite): GaugeReading {
        val byParam = fetchLatestByParameter(listOf(site.siteId))[site.siteId].orEmpty()
        val gageHeight = byParam[GAGE_HEIGHT_PARAM]
        val discharge = byParam[DISCHARGE_PARAM]
        val timeMillis = listOfNotNull(gageHeight?.timeMillis, discharge?.timeMillis).maxOrNull()
            ?: System.currentTimeMillis()
        return GaugeReading(
            siteId = site.siteId,
            timestampMillis = timeMillis,
            gageHeightFt = gageHeight?.value,
            dischargeCfs = discharge?.value
        )
    }

    override fun getHistory(site: GaugeSite, sinceMillis: Long): List<GaugeReading> {
        val paramCodes = listOfNotNull(
            GAGE_HEIGHT_PARAM_CODE.takeIf { site.hasGageHeight },
            DISCHARGE_PARAM_CODE.takeIf { site.hasDischarge }
        )
        if (paramCodes.isEmpty()) return emptyList()

        val url = "$BASE_URL/collections/continuous/items".toHttpUrl().newBuilder()
            .addQueryParameter("monitoring_location_id", site.siteId)
            .addQueryParameter("parameter_code", paramCodes.joinToString(","))
            .addQueryParameter("datetime", "${isoRequestFormat.format(Date(sinceMillis))}/${isoRequestFormat.format(Date())}")
            .addQueryParameter("f", "json")
            .addQueryParameter("limit", "10000")
            .build()

        val byTimestamp = mutableMapOf<Long, MutableMap<Int, Double>>()
        forEachFeature(url) { properties, _ ->
            val paramCode = properties.optString("parameter_code").toIntOrNull() ?: return@forEachFeature
            val value = properties.optString("value").toDoubleOrNull() ?: return@forEachFeature
            val timeMillis = parseIsoTimeMillis(properties.optString("time")) ?: return@forEachFeature
            byTimestamp.getOrPut(timeMillis) { mutableMapOf() }[paramCode] = value
        }

        return byTimestamp.entries.sortedBy { it.key }.map { (timeMillis, params) ->
            GaugeReading(
                siteId = site.siteId,
                timestampMillis = timeMillis,
                gageHeightFt = params[GAGE_HEIGHT_PARAM],
                dischargeCfs = params[DISCHARGE_PARAM]
            )
        }
    }

    private data class RawSite(val siteId: String, val name: String, val latitude: Double, val longitude: Double)
    private data class ParamValue(val value: Double, val timeMillis: Long)

    /** [deltaDegrees] is applied to both lat and lon - an approximation (a degree of longitude
     *  shrinks in real distance at higher latitudes), fine for "cast a wide enough net, then
     *  sort by real haversine distance after" rather than needing an exact search radius. */
    private fun fetchStreamSites(location: GeoLocation, deltaDegrees: Double): List<RawSite> {
        val bbox = listOf(
            location.longitude - deltaDegrees,
            location.latitude - deltaDegrees,
            location.longitude + deltaDegrees,
            location.latitude + deltaDegrees
        ).joinToString(",")

        // site_type_code/agency_code are filterable server-side (verified against the live API) -
        // filtering there instead of client-side matters a lot here: an unfiltered bbox query is
        // dominated by wells/diversions/other-agency sites, so a client-side-only filter combined
        // with the server's own result-count cap could return zero USGS stream sites even when
        // some exist in the box, just because they didn't happen to land in the first page.
        val url = "$BASE_URL/collections/monitoring-locations/items".toHttpUrl().newBuilder()
            .addQueryParameter("bbox", bbox)
            .addQueryParameter("site_type_code", STREAM_SITE_TYPE)
            .addQueryParameter("agency_code", USGS_AGENCY_CODE)
            .addQueryParameter("f", "json")
            .addQueryParameter("limit", "1000")
            .build()

        val sites = mutableListOf<RawSite>()
        forEachFeature(url) { properties, geometry ->
            val coordinates = geometry?.optJSONArray("coordinates") ?: return@forEachFeature
            // Despite the name, this collection's site identifier property is "id", not
            // "monitoring_location_id" (that property name is only used by the
            // continuous/latest-continuous collections) - verified against the live API.
            val siteId = properties.optString("id").takeIf { it.isNotBlank() } ?: return@forEachFeature
            sites.add(
                RawSite(
                    siteId = siteId,
                    name = properties.optString("monitoring_location_name", "Unnamed gauge"),
                    latitude = coordinates.optDouble(1),
                    longitude = coordinates.optDouble(0)
                )
            )
        }
        return sites
    }

    /** siteId -> parameterCode -> latest value+time, from one batched call. */
    private fun fetchLatestByParameter(siteIds: List<String>): Map<String, Map<Int, ParamValue>> {
        if (siteIds.isEmpty()) return emptyMap()
        val url = "$BASE_URL/collections/latest-continuous/items".toHttpUrl().newBuilder()
            .addQueryParameter("monitoring_location_id", siteIds.joinToString(","))
            .addQueryParameter("parameter_code", "$DISCHARGE_PARAM_CODE,$GAGE_HEIGHT_PARAM_CODE")
            .addQueryParameter("f", "json")
            .addQueryParameter("limit", "1000")
            .build()

        val result = mutableMapOf<String, MutableMap<Int, ParamValue>>()
        forEachFeature(url) { properties, _ ->
            val siteId = properties.optString("monitoring_location_id").takeIf { it.isNotBlank() } ?: return@forEachFeature
            val paramCode = properties.optString("parameter_code").toIntOrNull() ?: return@forEachFeature
            val value = properties.optString("value").toDoubleOrNull() ?: return@forEachFeature
            val timeMillis = parseIsoTimeMillis(properties.optString("time")) ?: System.currentTimeMillis()
            result.getOrPut(siteId) { mutableMapOf() }[paramCode] = ParamValue(value, timeMillis)
        }
        return result
    }

    private fun forEachFeature(url: HttpUrl, onFeature: (properties: JSONObject, geometry: JSONObject?) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw RiverGaugeProviderException("USGS request failed: HTTP ${response.code} $body")
            }
            val features = JSONObject(body).optJSONArray("features") ?: return
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val properties = feature.optJSONObject("properties") ?: continue
                onFeature(properties, feature.optJSONObject("geometry"))
            }
        }
    }

    private fun parseIsoTimeMillis(value: String): Long? =
        if (value.isBlank()) null else runCatching { isoResponseFormat.parse(value)?.time }.getOrNull()

    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_MILES * 2 * asin(sqrt(a))
    }

    companion object {
        private const val BASE_URL = "https://api.waterdata.usgs.gov/ogcapi/v0"
        private const val ZIPPOPOTAM_URL = "https://api.zippopotam.us/us"
        private const val STREAM_SITE_TYPE = "ST"
        private const val USGS_AGENCY_CODE = "USGS"
        private const val DISCHARGE_PARAM_CODE = "00060"
        private const val GAGE_HEIGHT_PARAM_CODE = "00065"
        private val DISCHARGE_PARAM = DISCHARGE_PARAM_CODE.toInt()
        private val GAGE_HEIGHT_PARAM = GAGE_HEIGHT_PARAM_CODE.toInt()
        private const val INITIAL_BBOX_DELTA_DEGREES = 0.15
        private const val MAX_BBOX_DELTA_DEGREES = 2.0
        private const val AVAILABILITY_CHECK_LIMIT = 200
        private const val EARTH_RADIUS_MILES = 3958.8
    }
}
