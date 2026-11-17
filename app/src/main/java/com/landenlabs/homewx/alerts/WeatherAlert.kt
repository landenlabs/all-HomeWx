// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.alerts

import com.landenlabs.homewx.weather.GeoLocation

/** CAP severity, most to least severe - the order the alert list and the icon tint use. */
enum class AlertSeverity {
    EXTREME, SEVERE, MODERATE, MINOR, UNKNOWN;

    companion object {
        /** Parses the CAP severity word both NWS and TWC send ("Extreme", "Severe", ...). */
        fun parse(text: String?): AlertSeverity =
            values().firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** One active watch/warning/advisory for the home location, from whichever [AlertProvider] ran.
 *  Times are epoch millis, null when the source left them out. */
data class WeatherAlert(
    val id: String,
    /** Kind of alert, e.g. "Winter Storm Warning". */
    val event: String,
    val headline: String?,
    val severity: AlertSeverity,
    val urgency: String?,
    val certainty: String?,
    val description: String?,
    val instruction: String?,
    val areaDesc: String?,
    val senderName: String?,
    val issuedMillis: Long?,
    val effectiveMillis: Long?,
    val onsetMillis: Long?,
    val expiresMillis: Long?,
    val endsMillis: Long?,
    /** Short source label shown in the panel ("NWS", "WxData"). */
    val source: String
) {
    /** When the hazard itself is over - [endsMillis] if given, else when this message [expiresMillis]. */
    val untilMillis: Long? get() = endsMillis ?: expiresMillis

    fun isActive(nowMillis: Long): Boolean = untilMillis?.let { it > nowMillis } ?: true
}

/** Returns the alerts in effect at [GeoLocation]. Blocking network call - run on Dispatchers.IO. */
interface AlertProvider {
    val sourceLabel: String
    fun getActiveAlerts(location: GeoLocation): List<WeatherAlert>
}

/** Most severe first, then soonest to end. */
fun List<WeatherAlert>.sortedForDisplay(): List<WeatherAlert> =
    sortedWith(compareBy<WeatherAlert> { it.severity.ordinal }.thenBy { it.untilMillis ?: Long.MAX_VALUE })
