package com.dlang.homewx.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dlang.homewx.rivers.GaugeReading
import java.util.concurrent.TimeUnit

data class RiverHistoryPoint(
    val timestampMillis: Long,
    val gageHeightFt: Double?,
    val dischargeCfs: Double?
)

/**
 * Plain SQLite (no Room) time-series store for USGS gauge readings - same shape as
 * [SensorHistoryStore] and for the same reason (avoids kapt/ksp for one small table).
 * Retains at most [RETENTION_DAYS] of history per gauge; pruned on every write.
 */
class RiverHistoryStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SITE_ID TEXT NOT NULL,
                $COL_GAGE_HEIGHT_FT REAL,
                $COL_DISCHARGE_CFS REAL,
                $COL_TIMESTAMP_MILLIS INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_site_time ON $TABLE ($COL_SITE_ID, $COL_TIMESTAMP_MILLIS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun record(reading: GaugeReading) {
        if (reading.gageHeightFt == null && reading.dischargeCfs == null) return
        writableDatabase.insert(
            TABLE,
            null,
            ContentValues().apply {
                put(COL_SITE_ID, reading.siteId)
                put(COL_GAGE_HEIGHT_FT, reading.gageHeightFt)
                put(COL_DISCHARGE_CFS, reading.dischargeCfs)
                put(COL_TIMESTAMP_MILLIS, reading.timestampMillis)
            }
        )
        pruneOlderThanRetention()
    }

    fun recordAll(readings: List<GaugeReading>) {
        readings.forEach { record(it) }
    }

    fun getHistorySince(siteId: String, sinceMillis: Long): List<RiverHistoryPoint> {
        val points = mutableListOf<RiverHistoryPoint>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TIMESTAMP_MILLIS, COL_GAGE_HEIGHT_FT, COL_DISCHARGE_CFS),
            "$COL_SITE_ID = ? AND $COL_TIMESTAMP_MILLIS >= ?",
            arrayOf(siteId, sinceMillis.toString()),
            null, null,
            "$COL_TIMESTAMP_MILLIS ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                points.add(
                    RiverHistoryPoint(
                        timestampMillis = cursor.getLong(0),
                        gageHeightFt = if (cursor.isNull(1)) null else cursor.getDouble(1),
                        dischargeCfs = if (cursor.isNull(2)) null else cursor.getDouble(2)
                    )
                )
            }
        }
        return points
    }

    /** Timestamp of the most recent recorded reading for [siteId], or null if this gauge has never been polled. */
    fun getLatestTimestamp(siteId: String): Long? {
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TIMESTAMP_MILLIS),
            "$COL_SITE_ID = ?",
            arrayOf(siteId),
            null, null,
            "$COL_TIMESTAMP_MILLIS DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun pruneOlderThanRetention() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        writableDatabase.delete(TABLE, "$COL_TIMESTAMP_MILLIS < ?", arrayOf(cutoff.toString()))
    }

    companion object {
        private const val DB_NAME = "homewx_river_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "river_reading"
        private const val COL_ID = "id"
        private const val COL_SITE_ID = "site_id"
        private const val COL_GAGE_HEIGHT_FT = "gage_height_ft"
        private const val COL_DISCHARGE_CFS = "discharge_cfs"
        private const val COL_TIMESTAMP_MILLIS = "timestamp_millis"
        private const val RETENTION_DAYS = 7L
    }
}
