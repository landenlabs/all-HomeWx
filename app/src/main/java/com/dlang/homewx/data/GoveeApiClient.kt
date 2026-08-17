package com.dlang.homewx.data

import com.dlang.homewx.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GoveeDevice(
    val sku: String,
    val device: String,
    val deviceName: String
)

data class GoveeReading(
    val online: Boolean,
    val temperatureF: Double?,
    val humidityPct: Double?
)

class GoveeApiException(message: String) : Exception(message)

/**
 * Thin client for the Govee OpenAPI (https://developer.govee.com).
 * Blocking network calls - callers must invoke from a background dispatcher.
 */
class GoveeApiClient(private val apiKey: String = BuildConfig.GOVEE_API_KEY) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    fun listDevices(): List<GoveeDevice> {
        val request = Request.Builder()
            .url("$BASE_URL/user/devices")
            .header("Content-Type", "application/json")
            .header("Govee-API-Key", apiKey)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw GoveeApiException("listDevices failed: HTTP ${response.code} $body")
            }
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            return buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    if (item.optString("type") != THERMOMETER_TYPE) continue
                    add(
                        GoveeDevice(
                            sku = item.getString("sku"),
                            device = item.getString("device"),
                            deviceName = item.optString("deviceName", item.getString("device"))
                        )
                    )
                }
            }
        }
    }

    fun getDeviceState(device: GoveeDevice): GoveeReading {
        val payload = JSONObject().apply {
            put("requestId", UUID.randomUUID().toString())
            put(
                "payload",
                JSONObject().apply {
                    put("sku", device.sku)
                    put("device", device.device)
                }
            )
        }

        val request = Request.Builder()
            .url("$BASE_URL/device/state")
            .header("Content-Type", "application/json")
            .header("Govee-API-Key", apiKey)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw GoveeApiException("getDeviceState failed for ${device.deviceName}: HTTP ${response.code} $body")
            }
            val capabilities = JSONObject(body)
                .optJSONObject("payload")
                ?.optJSONArray("capabilities")
                ?: return GoveeReading(online = false, temperatureF = null, humidityPct = null)

            var online = false
            var temperature: Double? = null
            var humidity: Double? = null

            for (i in 0 until capabilities.length()) {
                val capability = capabilities.getJSONObject(i)
                val state = capability.optJSONObject("state") ?: continue
                when (capability.optString("instance")) {
                    "online" -> online = state.optBoolean("value", false)
                    "sensorTemperature" -> temperature = state.optDouble("value").takeIfFinite()
                    "sensorHumidity" -> humidity = state.optDouble("value").takeIfFinite()
                }
            }
            return GoveeReading(online = online, temperatureF = temperature, humidityPct = humidity)
        }
    }

    companion object {
        private const val BASE_URL = "https://openapi.api.govee.com/router/api/v1"
        private const val THERMOMETER_TYPE = "devices.types.thermometer"
    }
}

private fun Double.takeIfFinite(): Double? = if (isNaN()) null else this
