package com.dlang.homewx.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dlang.homewx.model.SensorReading
import java.util.concurrent.TimeUnit

data class SensorHistoryPoint(
    val timestampMillis: Long,
    val tempF: Double?,
    val humidityPct: Double?
)

data class SensorExtreme(val value: Double, val atMillis: Long)

/**
 * Plain SQLite (no Room) time-series store for Govee readings - the app has no other
 * annotation-processed dependency, so this avoids pulling in kapt/ksp for one small table.
 * Retains at most [RETENTION_DAYS] of history per sensor; pruned on every write.
 */
class SensorHistoryStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SENSOR_ID TEXT NOT NULL,
                $COL_TEMP_F REAL,
                $COL_HUMIDITY_PCT REAL,
                $COL_TIMESTAMP_MILLIS INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sensor_time ON $TABLE ($COL_SENSOR_ID, $COL_TIMESTAMP_MILLIS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun record(reading: SensorReading) {
        if (reading.tempF == null && reading.humidityPct == null) return
        writableDatabase.insert(
            TABLE,
            null,
            ContentValues().apply {
                put(COL_SENSOR_ID, reading.id)
                put(COL_TEMP_F, reading.tempF)
                put(COL_HUMIDITY_PCT, reading.humidityPct)
                put(COL_TIMESTAMP_MILLIS, reading.updatedAtMillis)
            }
        )
        pruneOlderThanRetention()
    }

    fun getHistorySince(sensorId: String, sinceMillis: Long): List<SensorHistoryPoint> {
        val points = mutableListOf<SensorHistoryPoint>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TIMESTAMP_MILLIS, COL_TEMP_F, COL_HUMIDITY_PCT),
            "$COL_SENSOR_ID = ? AND $COL_TIMESTAMP_MILLIS >= ?",
            arrayOf(sensorId, sinceMillis.toString()),
            null, null,
            "$COL_TIMESTAMP_MILLIS ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                points.add(
                    SensorHistoryPoint(
                        timestampMillis = cursor.getLong(0),
                        tempF = if (cursor.isNull(1)) null else cursor.getDouble(1),
                        humidityPct = if (cursor.isNull(2)) null else cursor.getDouble(2)
                    )
                )
            }
        }
        return points
    }

    /** Timestamp of the most recent recorded reading for [sensorId] (recordings only ever hold successes). */
    fun getLatestTimestamp(sensorId: String): Long? {
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TIMESTAMP_MILLIS),
            "$COL_SENSOR_ID = ?",
            arrayOf(sensorId),
            null, null,
            "$COL_TIMESTAMP_MILLIS DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    /** Closest recorded point to [targetMillis] within [toleranceMillis], or null if none is close enough. */
    fun getValueNear(sensorId: String, targetMillis: Long, toleranceMillis: Long): SensorHistoryPoint? {
        var best: SensorHistoryPoint? = null
        var bestDiff = Long.MAX_VALUE
        for (point in getHistorySince(sensorId, targetMillis - toleranceMillis)) {
            val diff = kotlin.math.abs(point.timestampMillis - targetMillis)
            if (diff <= toleranceMillis && diff < bestDiff) {
                best = point
                bestDiff = diff
            }
        }
        return best
    }

    /** Today's high/low temp for [sensorId], with the timestamp of its last occurrence on ties. */
    fun getTodayTempExtremes(sensorId: String, dayStartMillis: Long): Pair<SensorExtreme?, SensorExtreme?> {
        var high: SensorExtreme? = null
        var low: SensorExtreme? = null
        for (point in getHistorySince(sensorId, dayStartMillis)) {
            val temp = point.tempF ?: continue
            if (high == null || temp >= high.value) high = SensorExtreme(temp, point.timestampMillis)
            if (low == null || temp <= low.value) low = SensorExtreme(temp, point.timestampMillis)
        }
        return high to low
    }

    private fun pruneOlderThanRetention() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        writableDatabase.delete(TABLE, "$COL_TIMESTAMP_MILLIS < ?", arrayOf(cutoff.toString()))
    }

    companion object {
        private const val DB_NAME = "homewx_sensor_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "sensor_reading"
        private const val COL_ID = "id"
        private const val COL_SENSOR_ID = "sensor_id"
        private const val COL_TEMP_F = "temp_f"
        private const val COL_HUMIDITY_PCT = "humidity_pct"
        private const val COL_TIMESTAMP_MILLIS = "timestamp_millis"
        private const val RETENTION_DAYS = 7L
    }
}
