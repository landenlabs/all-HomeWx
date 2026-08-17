package com.dlang.homewx.weather

/**
 * Running high/low for the outdoor temp and wind speed actually observed so far today,
 * fed one "current conditions" sample at a time. Resets when the calendar day rolls
 * over. Ties keep the *latest* timestamp, matching "last time it was the min or max".
 */
class WeatherDailyTracker {

    private var dayStartMillis: Long? = null
    private var tempHigh: DailyExtreme? = null
    private var tempLow: DailyExtreme? = null
    private var windHigh: DailyExtreme? = null
    private var windLow: DailyExtreme? = null

    fun recordSample(atMillis: Long, tempF: Double?, windMph: Double?) {
        rolloverIfNewDay(atMillis)
        if (tempF != null) {
            if (tempHigh == null || tempF >= tempHigh!!.value) tempHigh = DailyExtreme(tempF, atMillis)
            if (tempLow == null || tempF <= tempLow!!.value) tempLow = DailyExtreme(tempF, atMillis)
        }
        if (windMph != null) {
            if (windHigh == null || windMph >= windHigh!!.value) windHigh = DailyExtreme(windMph, atMillis)
            if (windLow == null || windMph <= windLow!!.value) windLow = DailyExtreme(windMph, atMillis)
        }
    }

    fun tempExtremes(): Pair<DailyExtreme?, DailyExtreme?> = tempHigh to tempLow

    fun windExtremes(): Pair<DailyExtreme?, DailyExtreme?> = windHigh to windLow

    private fun rolloverIfNewDay(atMillis: Long) {
        val todayStart = startOfDay(atMillis)
        if (dayStartMillis != todayStart) {
            dayStartMillis = todayStart
            tempHigh = null
            tempLow = null
            windHigh = null
            windLow = null
        }
    }
}
