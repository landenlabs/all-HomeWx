package com.dlang.homewx.weather.openmeteo

import com.dlang.homewx.weather.CurrentConditions
import com.dlang.homewx.weather.DailyForecastEntry
import com.dlang.homewx.weather.GeoLocation
import com.dlang.homewx.weather.HistoricalTempAverage
import com.dlang.homewx.weather.HourlyForecastEntry
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.WeatherProvider
import com.dlang.homewx.weather.WeatherProviderException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Free, keyless weather source (https://open-meteo.com) - no signup, no API key.
 * Blocking network calls - callers must invoke from a background dispatcher.
 */
class OpenMeteoWeatherProvider : WeatherProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val hourTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    private val dayTimeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun getCurrentConditions(location: GeoLocation): CurrentConditions {
        val json = fetch(
            location,
            current = "temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m," +
                "wind_direction_10m,precipitation,surface_pressure,weather_code,is_day"
        )
        val current = json.optJSONObject("current")
            ?: throw WeatherProviderException("Open-Meteo response missing 'current' block")
        val code = current.optInt("weather_code", -1)
        val isDay = current.optInt("is_day", 1) == 1

        return CurrentConditions(
            temperatureF = current.optDoubleOrNull("temperature_2m"),
            feelsLikeF = current.optDoubleOrNull("apparent_temperature"),
            humidityPct = current.optDoubleOrNull("relative_humidity_2m"),
            windSpeedMph = current.optDoubleOrNull("wind_speed_10m"),
            windDirectionDeg = current.optDoubleOrNull("wind_direction_10m"),
            precipitationIn = current.optDoubleOrNull("precipitation"),
            pressureInHg = current.optDoubleOrNull("surface_pressure")?.let(::hPaToInHg),
            conditionText = weatherCodeToText(code),
            iconKey = weatherCodeToIconKey(code, isDay),
            observedAtMillis = parseTimeMillis(current.optString("time"), hourTimeFormat)
        )
    }

    override fun getForecast(location: GeoLocation): WeatherForecast {
        val json = fetch(
            location,
            hourly = "temperature_2m,wind_speed_10m,precipitation_probability,surface_pressure,weather_code",
            daily = "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
            forecastDays = FORECAST_DAYS
        )

        val hourly = json.optJSONObject("hourly")?.let { parseHourly(it) }.orEmpty()
        val daily = json.optJSONObject("daily")?.let { parseDaily(it) }.orEmpty()
        return WeatherForecast(hourly = hourly, daily = daily)
    }

    override fun getHistoricalDailyAverage(location: GeoLocation, startMillis: Long, endMillis: Long): HistoricalTempAverage? {
        val json = fetchArchive(
            location,
            startDate = dayTimeFormat.format(Date(startMillis)),
            endDate = dayTimeFormat.format(Date(endMillis))
        )
        val daily = json.optJSONObject("daily") ?: return null
        val highs = daily.optJSONArray("temperature_2m_max") ?: return null
        val lows = daily.optJSONArray("temperature_2m_min") ?: return null

        val highValues = (0 until highs.length()).mapNotNull { highs.optDoubleOrNull(it) }
        val lowValues = (0 until lows.length()).mapNotNull { lows.optDoubleOrNull(it) }
        if (highValues.isEmpty() || lowValues.isEmpty()) return null

        return HistoricalTempAverage(
            avgHighF = highValues.average(),
            avgLowF = lowValues.average(),
            sampleDays = minOf(highValues.size, lowValues.size)
        )
    }

    private fun fetchArchive(location: GeoLocation, startDate: String, endDate: String): JSONObject {
        val url = ARCHIVE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("start_date", startDate)
            .addQueryParameter("end_date", endDate)
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min")
            .addQueryParameter("temperature_unit", "fahrenheit")
            .addQueryParameter("timezone", "auto")
            .build()

        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw WeatherProviderException("Open-Meteo archive request failed: HTTP ${response.code} $body")
            }
            return JSONObject(body)
        }
    }

    private fun parseHourly(hourly: JSONObject): List<HourlyForecastEntry> {
        val times = hourly.optJSONArray("time") ?: return emptyList()
        val temps = hourly.optJSONArray("temperature_2m")
        val windSpeeds = hourly.optJSONArray("wind_speed_10m")
        val precipChance = hourly.optJSONArray("precipitation_probability")
        val pressures = hourly.optJSONArray("surface_pressure")
        val codes = hourly.optJSONArray("weather_code")

        return (0 until times.length()).map { i ->
            HourlyForecastEntry(
                timeMillis = parseTimeMillis(times.getString(i), hourTimeFormat),
                temperatureF = temps?.optDoubleOrNull(i),
                windSpeedMph = windSpeeds?.optDoubleOrNull(i),
                precipitationChancePct = precipChance?.optIntOrNull(i),
                pressureInHg = pressures?.optDoubleOrNull(i)?.let(::hPaToInHg),
                conditionText = weatherCodeToText(codes?.optInt(i, -1) ?: -1)
            )
        }
    }

    private fun parseDaily(daily: JSONObject): List<DailyForecastEntry> {
        val times = daily.optJSONArray("time") ?: return emptyList()
        val highs = daily.optJSONArray("temperature_2m_max")
        val lows = daily.optJSONArray("temperature_2m_min")
        val precipChance = daily.optJSONArray("precipitation_probability_max")
        val codes = daily.optJSONArray("weather_code")

        return (0 until times.length()).map { i ->
            DailyForecastEntry(
                dateMillis = parseTimeMillis(times.getString(i), dayTimeFormat),
                highF = highs?.optDoubleOrNull(i),
                lowF = lows?.optDoubleOrNull(i),
                precipitationChancePct = precipChance?.optIntOrNull(i),
                conditionText = weatherCodeToText(codes?.optInt(i, -1) ?: -1)
            )
        }
    }

    private fun fetch(
        location: GeoLocation,
        current: String? = null,
        hourly: String? = null,
        daily: String? = null,
        forecastDays: Int? = null
    ): JSONObject {
        val urlBuilder = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("temperature_unit", "fahrenheit")
            .addQueryParameter("wind_speed_unit", "mph")
            .addQueryParameter("precipitation_unit", "inch")
            .addQueryParameter("timezone", "auto")
        current?.let { urlBuilder.addQueryParameter("current", it) }
        hourly?.let { urlBuilder.addQueryParameter("hourly", it) }
        daily?.let { urlBuilder.addQueryParameter("daily", it) }
        forecastDays?.let { urlBuilder.addQueryParameter("forecast_days", it.toString()) }

        val request = Request.Builder().url(urlBuilder.build()).get().build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw WeatherProviderException("Open-Meteo request failed: HTTP ${response.code} $body")
            }
            return JSONObject(body)
        }
    }

    private fun parseTimeMillis(value: String, format: SimpleDateFormat): Long =
        runCatching { format.parse(value)?.time }.getOrNull() ?: System.currentTimeMillis()

    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
        private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
        private const val FORECAST_DAYS = 3
        private const val HPA_TO_INHG = 0.0295299830714

        /** Open-Meteo always returns pressure in hPa - no unit param for it - so convert here. */
        private fun hPaToInHg(hpa: Double): Double = hpa * HPA_TO_INHG

        /** WMO weather interpretation codes, per https://open-meteo.com/en/docs */
        private fun weatherCodeToText(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unknown"
        }

        /**
         * Maps a WMO weather code + day/night to a wx-icons.csv "IconCode". Only the
         * codes Open-Meteo can actually produce are covered - the rest of the 00-47 icon
         * set (Tornado, Hurricane, Sleet, Blizzard, Haze, Windy, etc.) has no WMO
         * equivalent and is intentionally unused. Icon 44 (Not Available) is the fallback.
         */
        private fun weatherCodeToIconKey(code: Int, isDay: Boolean): String {
            val iconCode = when (code) {
                0 -> if (isDay) 32 else 31 // Sunny / Clear
                1 -> if (isDay) 34 else 33 // Fair/Mostly Sunny / Fair-Mostly Clear
                2 -> if (isDay) 30 else 29 // Partly Cloudy
                3 -> 26 // Cloudy
                45, 48 -> 20 // Foggy
                51, 53, 55 -> 9 // Drizzle
                56, 57 -> 8 // Freezing Drizzle
                61 -> 11 // Showers
                63 -> 12 // Rain
                65 -> 40 // Heavy Rain
                66, 67 -> 10 // Freezing Rain
                71 -> 14 // Snow Showers
                73 -> 16 // Snow
                75 -> 42 // Heavy Snow
                77 -> 13 // Flurries
                80, 81 -> 11 // Showers
                82 -> 40 // Heavy Rain
                85 -> 14 // Snow Showers
                86 -> 42 // Heavy Snow
                95, 96, 99 -> 4 // Thunderstorms
                else -> 44 // Not Available
            }
            val suffix = if (isDay) "d" else "n"
            return "wx_sun_%02d%s".format(iconCode, suffix)
        }
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

private fun org.json.JSONArray.optDoubleOrNull(index: Int): Double? =
    if (index < length() && !isNull(index)) optDouble(index).takeIf { !it.isNaN() } else null

private fun org.json.JSONArray.optIntOrNull(index: Int): Int? =
    if (index < length() && !isNull(index)) optInt(index) else null
