package com.dlang.homewx.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dlang.homewx.weather.CurrentConditions
import java.util.concurrent.TimeUnit

data class WeatherMetricsPoint(
    val timestampMillis: Long,
    val windSpeedMph: Double?,
    val precipitationIn: Double?,
    val pressureInHg: Double?
)

/**
 * Plain SQLite (no Room) time-series store for wind/precipitation/pressure, recorded on
 * every successful weather poll (simpler than throttling to once/hour, and the poll
 * interval is already 30 min by default). Retains a few days as a buffer past the
 * 48h window the strip charts actually query; pruned on every write.
 */
class WeatherMetricsHistoryStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_WIND_SPEED_MPH REAL,
                $COL_PRECIPITATION_IN REAL,
                $COL_PRESSURE_INHG REAL,
                $COL_TIMESTAMP_MILLIS INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_weather_metrics_time ON $TABLE ($COL_TIMESTAMP_MILLIS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun record(current: CurrentConditions) {
        if (current.windSpeedMph == null && current.precipitationIn == null && current.pressureInHg == null) return
        writableDatabase.insert(
            TABLE,
            null,
            ContentValues().apply {
                put(COL_WIND_SPEED_MPH, current.windSpeedMph)
                put(COL_PRECIPITATION_IN, current.precipitationIn)
                put(COL_PRESSURE_INHG, current.pressureInHg)
                put(COL_TIMESTAMP_MILLIS, current.observedAtMillis)
            }
        )
        pruneOlderThanRetention()
    }

    fun getHistorySince(sinceMillis: Long): List<WeatherMetricsPoint> {
        val points = mutableListOf<WeatherMetricsPoint>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TIMESTAMP_MILLIS, COL_WIND_SPEED_MPH, COL_PRECIPITATION_IN, COL_PRESSURE_INHG),
            "$COL_TIMESTAMP_MILLIS >= ?",
            arrayOf(sinceMillis.toString()),
            null, null,
            "$COL_TIMESTAMP_MILLIS ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                points.add(
                    WeatherMetricsPoint(
                        timestampMillis = cursor.getLong(0),
                        windSpeedMph = if (cursor.isNull(1)) null else cursor.getDouble(1),
                        precipitationIn = if (cursor.isNull(2)) null else cursor.getDouble(2),
                        pressureInHg = if (cursor.isNull(3)) null else cursor.getDouble(3)
                    )
                )
            }
        }
        return points
    }

    private fun pruneOlderThanRetention() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        writableDatabase.delete(TABLE, "$COL_TIMESTAMP_MILLIS < ?", arrayOf(cutoff.toString()))
    }

    companion object {
        private const val DB_NAME = "homewx_weather_metrics_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "weather_metrics"
        private const val COL_ID = "id"
        private const val COL_WIND_SPEED_MPH = "wind_speed_mph"
        private const val COL_PRECIPITATION_IN = "precipitation_in"
        private const val COL_PRESSURE_INHG = "pressure_inhg"
        private const val COL_TIMESTAMP_MILLIS = "timestamp_millis"
        private const val RETENTION_DAYS = 3L
    }
}
