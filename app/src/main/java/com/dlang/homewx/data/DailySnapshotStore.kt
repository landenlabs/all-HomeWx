package com.dlang.homewx.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A frozen end-of-day weather snapshot - "what the conditions were" for one calendar day.
 * Kept forever (no pruning) so a future calendar view can browse any past day.
 */
data class DailySnapshot(
    val dayStartMillis: Long,
    val conditionText: String?,
    val iconKey: String?,
    val tempF: Double?,
    val feelsLikeF: Double?,
    val humidityPct: Double?,
    val windSpeedMph: Double?,
    val windDirectionDeg: Double?,
    val precipitationIn: Double?,
    val pressureInHg: Double?,
    val tempHighF: Double?,
    val tempHighAtMillis: Long?,
    val tempLowF: Double?,
    val tempLowAtMillis: Long?,
    val windHighMph: Double?,
    val windHighAtMillis: Long?,
    val windLowMph: Double?,
    val windLowAtMillis: Long?,
    val lyAvgHighF: Double?,
    val lyAvgLowF: Double?
)

/** Plain SQLite (no Room) store for daily weather snapshots - one row per calendar day, kept forever. */
class DailySnapshotStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_DAY_START_MILLIS INTEGER PRIMARY KEY,
                $COL_CONDITION_TEXT TEXT,
                $COL_ICON_KEY TEXT,
                $COL_TEMP_F REAL,
                $COL_FEELS_LIKE_F REAL,
                $COL_HUMIDITY_PCT REAL,
                $COL_WIND_SPEED_MPH REAL,
                $COL_WIND_DIRECTION_DEG REAL,
                $COL_PRECIPITATION_IN REAL,
                $COL_PRESSURE_INHG REAL,
                $COL_TEMP_HIGH_F REAL,
                $COL_TEMP_HIGH_AT INTEGER,
                $COL_TEMP_LOW_F REAL,
                $COL_TEMP_LOW_AT INTEGER,
                $COL_WIND_HIGH_MPH REAL,
                $COL_WIND_HIGH_AT INTEGER,
                $COL_WIND_LOW_MPH REAL,
                $COL_WIND_LOW_AT INTEGER,
                $COL_LY_AVG_HIGH_F REAL,
                $COL_LY_AVG_LOW_F REAL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun saveSnapshot(snapshot: DailySnapshot) {
        writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            ContentValues().apply {
                put(COL_DAY_START_MILLIS, snapshot.dayStartMillis)
                put(COL_CONDITION_TEXT, snapshot.conditionText)
                put(COL_ICON_KEY, snapshot.iconKey)
                put(COL_TEMP_F, snapshot.tempF)
                put(COL_FEELS_LIKE_F, snapshot.feelsLikeF)
                put(COL_HUMIDITY_PCT, snapshot.humidityPct)
                put(COL_WIND_SPEED_MPH, snapshot.windSpeedMph)
                put(COL_WIND_DIRECTION_DEG, snapshot.windDirectionDeg)
                put(COL_PRECIPITATION_IN, snapshot.precipitationIn)
                put(COL_PRESSURE_INHG, snapshot.pressureInHg)
                put(COL_TEMP_HIGH_F, snapshot.tempHighF)
                put(COL_TEMP_HIGH_AT, snapshot.tempHighAtMillis)
                put(COL_TEMP_LOW_F, snapshot.tempLowF)
                put(COL_TEMP_LOW_AT, snapshot.tempLowAtMillis)
                put(COL_WIND_HIGH_MPH, snapshot.windHighMph)
                put(COL_WIND_HIGH_AT, snapshot.windHighAtMillis)
                put(COL_WIND_LOW_MPH, snapshot.windLowMph)
                put(COL_WIND_LOW_AT, snapshot.windLowAtMillis)
                put(COL_LY_AVG_HIGH_F, snapshot.lyAvgHighF)
                put(COL_LY_AVG_LOW_F, snapshot.lyAvgLowF)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getSnapshot(dayStartMillis: Long): DailySnapshot? {
        readableDatabase.query(
            TABLE,
            null,
            "$COL_DAY_START_MILLIS = ?",
            arrayOf(dayStartMillis.toString()),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DailySnapshot(
                dayStartMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DAY_START_MILLIS)),
                conditionText = cursor.getStringOrNull(COL_CONDITION_TEXT),
                iconKey = cursor.getStringOrNull(COL_ICON_KEY),
                tempF = cursor.getDoubleOrNull(COL_TEMP_F),
                feelsLikeF = cursor.getDoubleOrNull(COL_FEELS_LIKE_F),
                humidityPct = cursor.getDoubleOrNull(COL_HUMIDITY_PCT),
                windSpeedMph = cursor.getDoubleOrNull(COL_WIND_SPEED_MPH),
                windDirectionDeg = cursor.getDoubleOrNull(COL_WIND_DIRECTION_DEG),
                precipitationIn = cursor.getDoubleOrNull(COL_PRECIPITATION_IN),
                pressureInHg = cursor.getDoubleOrNull(COL_PRESSURE_INHG),
                tempHighF = cursor.getDoubleOrNull(COL_TEMP_HIGH_F),
                tempHighAtMillis = cursor.getLongOrNull(COL_TEMP_HIGH_AT),
                tempLowF = cursor.getDoubleOrNull(COL_TEMP_LOW_F),
                tempLowAtMillis = cursor.getLongOrNull(COL_TEMP_LOW_AT),
                windHighMph = cursor.getDoubleOrNull(COL_WIND_HIGH_MPH),
                windHighAtMillis = cursor.getLongOrNull(COL_WIND_HIGH_AT),
                windLowMph = cursor.getDoubleOrNull(COL_WIND_LOW_MPH),
                windLowAtMillis = cursor.getLongOrNull(COL_WIND_LOW_AT),
                lyAvgHighF = cursor.getDoubleOrNull(COL_LY_AVG_HIGH_F),
                lyAvgLowF = cursor.getDoubleOrNull(COL_LY_AVG_LOW_F)
            )
        }
    }

    /** Oldest saved day, or null if nothing has been saved yet - used to cap how far back swiping can go. */
    fun getOldestDayStartMillis(): Long? {
        readableDatabase.rawQuery("SELECT MIN($COL_DAY_START_MILLIS) FROM $TABLE", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getLong(0)
        }
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.getDoubleOrNull(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        private const val DB_NAME = "homewx_daily_snapshots.db"
        private const val DB_VERSION = 1
        private const val TABLE = "daily_snapshot"
        private const val COL_DAY_START_MILLIS = "day_start_millis"
        private const val COL_CONDITION_TEXT = "condition_text"
        private const val COL_ICON_KEY = "icon_key"
        private const val COL_TEMP_F = "temp_f"
        private const val COL_FEELS_LIKE_F = "feels_like_f"
        private const val COL_HUMIDITY_PCT = "humidity_pct"
        private const val COL_WIND_SPEED_MPH = "wind_speed_mph"
        private const val COL_WIND_DIRECTION_DEG = "wind_direction_deg"
        private const val COL_PRECIPITATION_IN = "precipitation_in"
        private const val COL_PRESSURE_INHG = "pressure_inhg"
        private const val COL_TEMP_HIGH_F = "temp_high_f"
        private const val COL_TEMP_HIGH_AT = "temp_high_at_millis"
        private const val COL_TEMP_LOW_F = "temp_low_f"
        private const val COL_TEMP_LOW_AT = "temp_low_at_millis"
        private const val COL_WIND_HIGH_MPH = "wind_high_mph"
        private const val COL_WIND_HIGH_AT = "wind_high_at_millis"
        private const val COL_WIND_LOW_MPH = "wind_low_mph"
        private const val COL_WIND_LOW_AT = "wind_low_at_millis"
        private const val COL_LY_AVG_HIGH_F = "ly_avg_high_f"
        private const val COL_LY_AVG_LOW_F = "ly_avg_low_f"
    }
}
