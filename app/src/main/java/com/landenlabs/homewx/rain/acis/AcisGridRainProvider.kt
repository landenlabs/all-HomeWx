package com.landenlabs.homewx.rain.acis

import com.landenlabs.homewx.rain.DailyRain
import com.landenlabs.homewx.rain.RainProvider
import com.landenlabs.homewx.rain.RainProviderException
import com.landenlabs.homewx.weather.GeoLocation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Free, keyless daily precipitation from the NOAA Regional Climate Centers' ACIS GridData
 * endpoint (https://www.rcc-acis.org/docs_gridded.html), PRISM 4 km grid (id 21) - gauge-informed,
 * takes a plain lat/lon, CONUS only. Values are inches; -999 marks a day with no data yet.
 * Blocking network calls - callers must invoke from a background dispatcher.
 */
class AcisGridRainProvider : RainProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun getDailyRain(location: GeoLocation, startMillis: Long, endMillis: Long): List<DailyRain> {
        val body = JSONObject()
            .put("loc", "${location.longitude},${location.latitude}") // lon first
            .put("grid", GRID_PRISM)
            .put("sdate", dayFormat.format(startMillis))
            .put("edate", dayFormat.format(endMillis))
            .put("elems", JSONArray().put(JSONObject().put("name", "pcpn")))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(GRID_DATA_URL).post(body).build()
        val text = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RainProviderException("ACIS HTTP ${response.code}")
                response.body.string()
            }
        } catch (e: RainProviderException) {
            throw e
        } catch (e: Exception) {
            throw RainProviderException("ACIS request failed: ${e.message}", e)
        }

        val json = try { JSONObject(text) } catch (e: Exception) { throw RainProviderException("ACIS returned invalid JSON", e) }
        // Out-of-coverage locations (non-CONUS) and bad requests come back as {"error": "..."}.
        json.optString("error").takeIf { it.isNotEmpty() }?.let { throw RainProviderException("ACIS: $it") }
        val data = json.optJSONArray("data") ?: throw RainProviderException("ACIS response missing 'data'")

        return (0 until data.length()).mapNotNull { i ->
            val row = data.optJSONArray(i) ?: return@mapNotNull null
            val day = dayFormat.parse(row.optString(0)) ?: return@mapNotNull null
            val inches = row.optDouble(1, MISSING).takeIf { it >= 0 }
            DailyRain(day.time, inches)
        }
    }

    private companion object {
        const val GRID_DATA_URL = "https://grid2.rcc-acis.org/GridData"
        const val GRID_PRISM = "21"
        const val MISSING = -999.0
    }
}
