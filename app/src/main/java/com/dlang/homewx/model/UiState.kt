package com.dlang.homewx.model

import com.dlang.homewx.news.NewsItem
import com.dlang.homewx.news.NewsSourceId
import com.dlang.homewx.weather.CurrentConditions
import com.dlang.homewx.weather.DailyExtremes
import com.dlang.homewx.weather.HistoricalTempAverage
import com.dlang.homewx.weather.WeatherForecast

data class SensorReading(
    val id: String,
    val roomName: String,
    val tempF: Double?,
    val humidityPct: Double?,
    val online: Boolean,
    val updatedAtMillis: Long,
    val error: String? = null,
    val tempTrend1hF: Double? = null,
    val humidityTrend1hPct: Double? = null,
    val lastSuccessAtMillis: Long? = null,
    val failureCountToday: Int = 0
)

enum class LightMode { ACTIVE, QUIET }

data class UiState(
    val sensors: List<SensorReading> = emptyList(),
    val sensorsUpdatedAtMillis: Long? = null,
    val lightMode: LightMode = LightMode.ACTIVE,
    val currentLux: Float? = null,
    val currentWeather: CurrentConditions? = null,
    val weatherForecast: WeatherForecast? = null,
    val tempTrendNextHourF: Double? = null,
    val pressureTrend6hInHg: Double? = null,
    val dailyExtremes: DailyExtremes = DailyExtremes(),
    val historicalTempAverage: HistoricalTempAverage? = null,
    val weatherError: String? = null,
    val lastError: String? = null,
    val newsItemsBySource: Map<NewsSourceId, List<NewsItem>> = emptyMap(),
    val networkReachable: Boolean = true
)
