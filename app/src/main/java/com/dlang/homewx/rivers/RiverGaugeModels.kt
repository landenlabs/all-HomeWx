package com.dlang.homewx.rivers

data class GaugeSite(
    val siteId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val hasGageHeight: Boolean,
    val hasDischarge: Boolean,
    val distanceMiles: Double
)

data class GaugeReading(
    val siteId: String,
    val timestampMillis: Long,
    val gageHeightFt: Double?,
    val dischargeCfs: Double?
)

class RiverGaugeProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
