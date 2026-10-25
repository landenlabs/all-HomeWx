package com.landenlabs.homewx.data

import com.landenlabs.homewx.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** ThermostatHvac.status: what the furnace/AC is doing right now. */
enum class NestHvacStatus { OFF, HEATING, COOLING, UNKNOWN }

/** ThermostatMode.mode: what the user has the thermostat set to. */
enum class NestThermostatMode { HEAT, COOL, HEATCOOL, OFF, UNKNOWN }

data class NestThermostat(
    val name: String,
    val displayName: String,
    val online: Boolean,
    val temperatureF: Double?,
    val setpointHeatF: Double?,
    val setpointCoolF: Double?,
    val mode: NestThermostatMode,
    val hvacStatus: NestHvacStatus
)

class NestApiException(message: String) : Exception(message)

/**
 * Thin client for the Google Smart Device Management (Nest) API
 * (https://developers.google.com/nest/device-access).
 * Blocking network calls - callers must invoke from a background dispatcher.
 *
 * Auth is OAuth2 with a long-lived refresh token obtained once via the Device Access
 * authorization flow; access tokens (1 hour) are refreshed here on demand.
 */
class NestApiClient(
    private val projectId: String = BuildConfig.NEST_PROJECT_ID,
    private val clientId: String = BuildConfig.NEST_CLIENT_ID,
    private val clientSecret: String = BuildConfig.NEST_CLIENT_SECRET,
    private val refreshToken: String = BuildConfig.NEST_REFRESH_TOKEN
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var accessTokenExpiresAtMillis = 0L

    val isConfigured: Boolean
        get() = projectId.isNotBlank() && clientId.isNotBlank() &&
            clientSecret.isNotBlank() && refreshToken.isNotBlank()

    fun listThermostats(): List<NestThermostat> {
        if (!isConfigured) throw NestApiException("Nest credentials not configured in local.properties")
        val request = Request.Builder()
            .url("$SDM_URL/enterprises/$projectId/devices")
            .header("Authorization", "Bearer ${getAccessToken()}")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 401) accessToken = null
                throw NestApiException("listThermostats failed: HTTP ${response.code} $body")
            }
            val devices = JSONObject(body).optJSONArray("devices") ?: return emptyList()
            return buildList {
                for (i in 0 until devices.length()) {
                    val item = devices.getJSONObject(i)
                    if (item.optString("type") != THERMOSTAT_TYPE) continue
                    add(parseThermostat(item))
                }
            }
        }
    }

    private fun parseThermostat(item: JSONObject): NestThermostat {
        val traits = item.optJSONObject("traits") ?: JSONObject()
        val ambientC = traits.optJSONObject("sdm.devices.traits.Temperature")
            ?.optDouble("ambientTemperatureCelsius", Double.NaN)
        val setpoint = traits.optJSONObject("sdm.devices.traits.ThermostatTemperatureSetpoint")
        val mode = traits.optJSONObject("sdm.devices.traits.ThermostatMode")?.optString("mode")
        val hvac = traits.optJSONObject("sdm.devices.traits.ThermostatHvac")?.optString("status")
        val name = item.getString("name")

        // Room name lives in parentRelations; customName is only set if the user named the device.
        val customName = traits.optJSONObject("sdm.devices.traits.Info")?.optString("customName").orEmpty()
        val roomName = item.optJSONArray("parentRelations")?.optJSONObject(0)?.optString("displayName").orEmpty()

        return NestThermostat(
            name = name,
            displayName = customName.ifBlank { roomName }.ifBlank { "Thermostat" },
            online = traits.optJSONObject("sdm.devices.traits.Connectivity")?.optString("status") == "ONLINE",
            temperatureF = ambientC?.celsiusToF(),
            setpointHeatF = setpoint?.optDouble("heatCelsius", Double.NaN)?.celsiusToF(),
            setpointCoolF = setpoint?.optDouble("coolCelsius", Double.NaN)?.celsiusToF(),
            mode = enumOrUnknown(mode, NestThermostatMode.UNKNOWN),
            hvacStatus = enumOrUnknown(hvac, NestHvacStatus.UNKNOWN)
        )
    }

    private fun getAccessToken(): String {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < accessTokenExpiresAtMillis) return cached

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(form).build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw NestApiException("Nest token refresh failed: HTTP ${response.code} $body")
            }
            val json = JSONObject(body)
            val token = json.getString("access_token")
            // Refresh a minute early so a call never races the expiry.
            val lifetimeSec = json.optLong("expires_in", 3600L)
            accessToken = token
            accessTokenExpiresAtMillis = System.currentTimeMillis() + (lifetimeSec - 60L) * 1000L
            return token
        }
    }

    companion object {
        private const val SDM_URL = "https://smartdevicemanagement.googleapis.com/v1"
        private const val TOKEN_URL = "https://www.googleapis.com/oauth2/v4/token"
        private const val THERMOSTAT_TYPE = "sdm.devices.types.THERMOSTAT"
    }
}

private fun Double.celsiusToF(): Double? = if (isNaN()) null else this * 9.0 / 5.0 + 32.0

private inline fun <reified E : Enum<E>> enumOrUnknown(value: String?, unknown: E): E =
    enumValues<E>().firstOrNull { it.name == value } ?: unknown
