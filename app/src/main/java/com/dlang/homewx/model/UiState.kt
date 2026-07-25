package com.dlang.homewx.model

data class SensorReading(
    val id: String,
    val roomName: String,
    val tempF: Double?,
    val humidityPct: Double?,
    val online: Boolean,
    val updatedAtMillis: Long,
    val error: String? = null
)

enum class LightMode { ACTIVE, QUIET }

data class UiState(
    val sensors: List<SensorReading> = emptyList(),
    val lightMode: LightMode = LightMode.ACTIVE,
    val weatherSummary: String = "",
    val lastError: String? = null
)
