package com.dlang.homewx.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dlang.homewx.weather.CurrentConditions
import com.dlang.homewx.weather.startOfDay
import com.dlang.homewx.weather.startOfHour
import java.util.concurrent.TimeUnit

data class WeatherMetricsPoint(
    val timestampMillis: Long,
    val temperatureF: Double?,
    val windSpeedMph: Double?,
    val precipitationIn: Double?,
    val pressureInHg: Double?,
    /** Drawable resource name (no extension) in res/drawable-nodpi, e.g. "wx_sun_30d" - stored
     *  as-recorded (already resolved from the weather code + day/night at capture time) rather
     *  than the raw numeric weather code, so re-deriving it later doesn't need to guess
     *  day/night from the timestamp. */
    val iconKey: String?
)

/**
 * Plain SQLite (no Room) time-series store for temperature/wind/precipitation/pressure,
 * recorded on every successful weather poll (simpler than throttling to once/hour, and the
 * poll interval is already 30 min by default). Retains a few days as a buffer past the
 * 48h window the strip charts actually query; pruned on every write.
 */
class WeatherMetricsHistoryStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createShortTermTable(db)
        createDailyTable(db)
    }

    private fun createShortTermTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEMPERATURE_F REAL,
                $COL_WIND_SPEED_MPH REAL,
                $COL_PRECIPITATION_IN REAL,
                $COL_PRESSURE_INHG REAL,
                $COL_ICON_KEY TEXT,
                $COL_TIMESTAMP_MILLIS INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_weather_metrics_time ON $TABLE ($COL_TIMESTAMP_MILLIS)")
    }

    private fun createDailyTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $DAILY_TABLE (
                $COL_DAY_START_MILLIS INTEGER PRIMARY KEY,
                $COL_TEMPERATURE_F REAL,
                $COL_WIND_SPEED_MPH REAL,
                $COL_PRECIPITATION_IN REAL,
                $COL_PRESSURE_INHG REAL,
                $COL_ICON_KEY TEXT,
                $COL_TIMESTAMP_MILLIS INTEGER NOT NULL,
                $COL_PRECIP_HOUR_START_MILLIS INTEGER,
                $COL_PRECIP_HOUR_AMOUNT REAL
            )
            """.trimIndent()
        )
    }

    // The short-term table is a rolling few-day buffer, not precious data, so an upgrade just
    // drops and recreates it rather than migrating rows in place. The daily table is precious
    // (kept forever) so it's only created if missing, never dropped.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        createShortTermTable(db)
        createDailyTable(db)
        // createDailyTable's CREATE TABLE IF NOT EXISTS is a no-op against an already-existing
        // daily table, so an install upgrading from before these 2 precipitation-accumulation
        // columns existed needs them added explicitly.
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE $DAILY_TABLE ADD COLUMN $COL_PRECIP_HOUR_START_MILLIS INTEGER")
            db.execSQL("ALTER TABLE $DAILY_TABLE ADD COLUMN $COL_PRECIP_HOUR_AMOUNT REAL")
            // Today's existing row (if any) still holds the old last-sample-wins value, not an
            // accumulated total - left alone, the next poll would treat it as an already-running
            // total and add to it, inflating just this one transition day. Clearing it lets
            // nextDailyPrecipTotal start today fresh from 0, same as any future day. Older days
            // are left as-is; they were already last-sample-wins and this migration doesn't try
            // to retroactively reconstruct their true totals.
            db.update(
                DAILY_TABLE,
                ContentValues().apply { putNull(COL_PRECIPITATION_IN) },
                "$COL_DAY_START_MILLIS = ?",
                arrayOf(startOfDay(System.currentTimeMillis()).toString())
            )
        }
    }

    fun record(current: CurrentConditions) {
        if (current.temperatureF == null && current.windSpeedMph == null &&
            current.precipitationIn == null && current.pressureInHg == null
        ) {
            return
        }
        writableDatabase.insert(
            TABLE,
            null,
            ContentValues().apply {
                put(COL_TEMPERATURE_F, current.temperatureF)
                put(COL_WIND_SPEED_MPH, current.windSpeedMph)
                put(COL_PRECIPITATION_IN, current.precipitationIn)
                put(COL_PRESSURE_INHG, current.pressureInHg)
                put(COL_ICON_KEY, current.iconKey)
                put(COL_TIMESTAMP_MILLIS, current.observedAtMillis)
            }
        )
        pruneOlderThanRetention()
        recordDailySnapshot(current)
    }

    /**
     * Upserts the permanent one-row-per-calendar-day archive with this sample, keyed by the day
     * it falls on. Temperature/wind/pressure/icon are simple last-sample-wins snapshots - samples
     * for a given day always arrive in time order, so the last one recorded is necessarily the
     * one closest to its midnight, a reasonable stand-in for "conditions at end of day". Never
     * pruned.
     *
     * Precipitation can't use that same last-sample-wins rule: [CurrentConditions.precipitationIn]
     * is a trailing accumulation (roughly the last hour, not a delta since the previous poll), so
     * whatever fell earlier in the day gets overwritten and lost by the next poll's smaller/zero
     * reading - see [nextDailyPrecipTotal].
     */
    private fun recordDailySnapshot(current: CurrentConditions) {
        val dayStartMillis = startOfDay(current.observedAtMillis)
        val sampleHourMillis = startOfHour(current.observedAtMillis)
        val precipState = nextDailyPrecipTotal(existingDailyPrecipState(dayStartMillis), sampleHourMillis, current.precipitationIn)

        writableDatabase.insertWithOnConflict(
            DAILY_TABLE,
            null,
            ContentValues().apply {
                put(COL_DAY_START_MILLIS, dayStartMillis)
                put(COL_TEMPERATURE_F, current.temperatureF)
                put(COL_WIND_SPEED_MPH, current.windSpeedMph)
                put(COL_PRECIPITATION_IN, precipState.totalIn)
                put(COL_PRESSURE_INHG, current.pressureInHg)
                put(COL_ICON_KEY, current.iconKey)
                put(COL_TIMESTAMP_MILLIS, current.observedAtMillis)
                put(COL_PRECIP_HOUR_START_MILLIS, precipState.hourStartMillis)
                put(COL_PRECIP_HOUR_AMOUNT, precipState.hourAmountIn)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** [COL_PRECIPITATION_IN]/[COL_PRECIP_HOUR_START_MILLIS]/[COL_PRECIP_HOUR_AMOUNT] for a day's
     *  existing row, or all-null if that day has no row yet. */
    private data class DailyPrecipState(val totalIn: Double?, val hourStartMillis: Long?, val hourAmountIn: Double?)

    private fun existingDailyPrecipState(dayStartMillis: Long): DailyPrecipState {
        readableDatabase.query(
            DAILY_TABLE,
            arrayOf(COL_PRECIPITATION_IN, COL_PRECIP_HOUR_START_MILLIS, COL_PRECIP_HOUR_AMOUNT),
            "$COL_DAY_START_MILLIS = ?",
            arrayOf(dayStartMillis.toString()),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return DailyPrecipState(null, null, null)
            return DailyPrecipState(
                totalIn = if (cursor.isNull(0)) null else cursor.getDouble(0),
                hourStartMillis = if (cursor.isNull(1)) null else cursor.getLong(1),
                hourAmountIn = if (cursor.isNull(2)) null else cursor.getDouble(2)
            )
        }
    }

    /**
     * Folds [samplePrecipIn] into a day's running precipitation total, correcting for it being a
     * trailing accumulation rather than a delta since the last poll: polls land every ~30 min, so
     * naively summing every raw sample would double-count whatever overlaps between consecutive
     * ones. At most one sample per calendar hour ever contributes to the total - a sample in the
     * same hour as [existing]'s last one *replaces* that hour's contribution (it's a fuller/more
     * complete reading of the same hour, not a new one) rather than adding to it; a sample in a
     * new hour is added on top. A missing [samplePrecipIn] leaves [existing] untouched. */
    private fun nextDailyPrecipTotal(existing: DailyPrecipState, sampleHourMillis: Long, samplePrecipIn: Double?): DailyPrecipState {
        if (samplePrecipIn == null) return existing
        val baseTotal = existing.totalIn ?: 0.0
        val newTotal = if (existing.hourStartMillis == sampleHourMillis) {
            baseTotal - (existing.hourAmountIn ?: 0.0) + samplePrecipIn
        } else {
            baseTotal + samplePrecipIn
        }
        return DailyPrecipState(newTotal, sampleHourMillis, samplePrecipIn)
    }

    fun getHistorySince(sinceMillis: Long): List<WeatherMetricsPoint> {
        val points = mutableListOf<WeatherMetricsPoint>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TIMESTAMP_MILLIS, COL_TEMPERATURE_F, COL_WIND_SPEED_MPH, COL_PRECIPITATION_IN, COL_PRESSURE_INHG, COL_ICON_KEY),
            "$COL_TIMESTAMP_MILLIS >= ?",
            arrayOf(sinceMillis.toString()),
            null, null,
            "$COL_TIMESTAMP_MILLIS ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                points.add(
                    WeatherMetricsPoint(
                        timestampMillis = cursor.getLong(0),
                        temperatureF = if (cursor.isNull(1)) null else cursor.getDouble(1),
                        windSpeedMph = if (cursor.isNull(2)) null else cursor.getDouble(2),
                        precipitationIn = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        pressureInHg = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        iconKey = if (cursor.isNull(5)) null else cursor.getString(5)
                    )
                )
            }
        }
        return points
    }

    /**
     * The permanent daily archive: one point per calendar day (the sample nearest that day's
     * midnight), kept forever - unlike [getHistorySince] this isn't limited by [RETENTION_DAYS].
     */
    fun getDailyHistoryBefore(beforeMillis: Long): List<WeatherMetricsPoint> {
        val points = mutableListOf<WeatherMetricsPoint>()
        readableDatabase.query(
            DAILY_TABLE,
            arrayOf(COL_TIMESTAMP_MILLIS, COL_TEMPERATURE_F, COL_WIND_SPEED_MPH, COL_PRECIPITATION_IN, COL_PRESSURE_INHG, COL_ICON_KEY),
            "$COL_DAY_START_MILLIS < ?",
            arrayOf(beforeMillis.toString()),
            null, null,
            "$COL_DAY_START_MILLIS ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                points.add(
                    WeatherMetricsPoint(
                        timestampMillis = cursor.getLong(0),
                        temperatureF = if (cursor.isNull(1)) null else cursor.getDouble(1),
                        windSpeedMph = if (cursor.isNull(2)) null else cursor.getDouble(2),
                        precipitationIn = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        pressureInHg = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        iconKey = if (cursor.isNull(5)) null else cursor.getString(5)
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
        private const val DB_VERSION = 5
        private const val TABLE = "weather_metrics"
        private const val DAILY_TABLE = "weather_metrics_daily"
        private const val COL_ID = "id"
        private const val COL_DAY_START_MILLIS = "day_start_millis"
        private const val COL_TEMPERATURE_F = "temperature_f"
        private const val COL_WIND_SPEED_MPH = "wind_speed_mph"
        private const val COL_PRECIPITATION_IN = "precipitation_in"
        private const val COL_PRESSURE_INHG = "pressure_inhg"
        private const val COL_ICON_KEY = "icon_key"
        private const val COL_TIMESTAMP_MILLIS = "timestamp_millis"
        /** Daily table only - tracks which hour [COL_PRECIPITATION_IN]'s running total last
         *  folded in a sample from, so [nextDailyPrecipTotal] knows whether a new sample should
         *  add to the total (new hour) or replace the previous sample's contribution (same hour). */
        private const val COL_PRECIP_HOUR_START_MILLIS = "precip_hour_start_millis"
        /** Daily table only - the amount [COL_PRECIP_HOUR_START_MILLIS]'s hour currently
         *  contributes to [COL_PRECIPITATION_IN], kept so that contribution can be subtracted
         *  back out if a fuller same-hour sample arrives. */
        private const val COL_PRECIP_HOUR_AMOUNT = "precip_hour_amount"
        private const val RETENTION_DAYS = 3L
    }
}
