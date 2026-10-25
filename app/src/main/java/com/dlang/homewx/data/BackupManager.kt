package com.dlang.homewx.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.dlang.homewx.BuildConfig
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export/import of all history tables (one CSV each) plus the settings prefs (settings.json)
 * as a single zip, so history survives an applicationId change - a new id gets a fresh private
 * data dir, and this is the only way across. Import merges: rows already present (same natural
 * key) are skipped, so it's safe to run twice or after the new app has recorded its own data.
 */
object BackupManager {

    data class ImportResult(val rowsAdded: Int, val rowsSkipped: Int, val settingsRestored: Int)

    /** A table to round-trip: [keyColumns] is the natural key used to skip rows that already exist;
     *  [skipColumns] are surrogate autoincrement ids that must not be exported. */
    private class TableSpec(
        val name: String,
        val keyColumns: List<String>,
        val skipColumns: Set<String> = setOf("id"),
        val open: (Context) -> SQLiteDatabase
    ) {
        val csvFile get() = "$name.csv"
    }

    private val tables = listOf(
        TableSpec(SensorHistoryStore.TABLE, listOf("sensor_id", "timestamp_millis")) { SensorHistoryStore(it).writableDatabase },
        TableSpec(RiverHistoryStore.TABLE, listOf("site_id", "timestamp_millis")) { RiverHistoryStore(it).writableDatabase },
        TableSpec(WeatherMetricsHistoryStore.TABLE, listOf("timestamp_millis")) { WeatherMetricsHistoryStore(it).writableDatabase },
        TableSpec(WeatherMetricsHistoryStore.DAILY_TABLE, listOf("day_start_millis")) { WeatherMetricsHistoryStore(it).writableDatabase },
        TableSpec(DailySnapshotStore.TABLE, listOf("day_start_millis")) { DailySnapshotStore(it).writableDatabase }
    )

    private const val SETTINGS_FILE = "settings.json"
    private const val MANIFEST_FILE = "manifest.json"
    private const val PREFS_NAME = "homewx_settings"
    private const val FORMAT_VERSION = 1

    fun export(context: Context, out: OutputStream): Int {
        var rows = 0
        ZipOutputStream(out.buffered()).use { zip ->
            val counts = JSONObject()
            for (spec in tables) {
                val count = spec.open(context).use { writeTable(it, spec, zip) }
                counts.put(spec.name, count)
                rows += count
            }
            zip.putNextEntry(ZipEntry(SETTINGS_FILE))
            zip.write(settingsToJson(context).toString(2).toByteArray())
            zip.closeEntry()

            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("sourcePackage", context.packageName)
                .put("sourceVersion", BuildConfig.VERSION_NAME)
                .put("exportedAtMillis", System.currentTimeMillis())
                .put("rowCounts", counts)
            zip.putNextEntry(ZipEntry(MANIFEST_FILE))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
        }
        return rows
    }

    fun import(context: Context, input: InputStream): ImportResult {
        // Read every entry up front so a corrupt/non-HomeWx zip fails before anything is written.
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val manifest = entries[MANIFEST_FILE]?.let { JSONObject(String(it)) }
            ?: throw IllegalArgumentException("Not a HomeWx export (no $MANIFEST_FILE)")
        require(manifest.optInt("formatVersion") <= FORMAT_VERSION) {
            "Export is from a newer version of the app"
        }

        var added = 0
        var skipped = 0
        for (spec in tables) {
            val csv = entries[spec.csvFile] ?: continue
            val (a, s) = spec.open(context).use { importTable(it, spec, String(csv)) }
            added += a
            skipped += s
        }
        val restored = entries[SETTINGS_FILE]?.let { restoreSettings(context, JSONObject(String(it))) } ?: 0
        return ImportResult(added, skipped, restored)
    }

    private fun writeTable(db: SQLiteDatabase, spec: TableSpec, zip: ZipOutputStream): Int {
        zip.putNextEntry(ZipEntry(spec.csvFile))
        val writer = zip.bufferedWriter()
        var count = 0
        db.rawQuery("SELECT * FROM ${spec.name}", null).use { cursor ->
            val columns = cursor.columnNames.filter { it !in spec.skipColumns }
            writer.write(columns.joinToString(",") + "\n")
            while (cursor.moveToNext()) {
                writer.write(columns.joinToString(",") { csvField(cursor.getString(cursor.getColumnIndexOrThrow(it))) } + "\n")
                count++
            }
        }
        writer.flush() // not close() - that would close the zip itself
        zip.closeEntry()
        return count
    }

    private fun importTable(db: SQLiteDatabase, spec: TableSpec, csv: String): Pair<Int, Int> {
        val rows = parseCsv(csv)
        if (rows.isEmpty()) return 0 to 0
        val header = rows.first()
        val existing = db.compileStatement(
            "SELECT COUNT(*) FROM ${spec.name} WHERE " + spec.keyColumns.joinToString(" AND ") { "$it = ?" }
        )
        var added = 0
        var skipped = 0
        db.beginTransaction()
        try {
            for (row in rows.drop(1)) {
                if (row.size != header.size) { skipped++; continue }
                val byColumn = header.zip(row).toMap()
                spec.keyColumns.forEachIndexed { i, col -> existing.bindString(i + 1, byColumn[col] ?: "") }
                if (existing.simpleQueryForLong() > 0) { skipped++; continue }
                val values = ContentValues()
                for ((col, value) in byColumn) {
                    // Empty field = SQL NULL; bound as text, SQLite's column affinity converts it back to INTEGER/REAL.
                    if (value.isEmpty()) values.putNull(col) else values.put(col, value)
                }
                if (db.insert(spec.name, null, values) >= 0) added++ else skipped++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added to skipped
    }

    private fun settingsToJson(context: Context): JSONObject {
        val json = JSONObject()
        for ((key, value) in context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all) {
            val entry = when (value) {
                is Boolean -> JSONObject().put("t", "bool").put("v", value)
                is Int -> JSONObject().put("t", "int").put("v", value)
                is Long -> JSONObject().put("t", "long").put("v", value)
                is Float -> JSONObject().put("t", "float").put("v", value.toDouble())
                is String -> JSONObject().put("t", "string").put("v", value)
                is Set<*> -> JSONObject().put("t", "stringSet").put("v", org.json.JSONArray(value.map { it.toString() }))
                else -> continue
            }
            json.put(key, entry)
        }
        return json
    }

    private fun restoreSettings(context: Context, json: JSONObject): Int {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        var count = 0
        for (key in json.keys()) {
            val entry = json.getJSONObject(key)
            when (entry.getString("t")) {
                "bool" -> editor.putBoolean(key, entry.getBoolean("v"))
                "int" -> editor.putInt(key, entry.getInt("v"))
                "long" -> editor.putLong(key, entry.getLong("v"))
                "float" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                "string" -> editor.putString(key, entry.getString("v"))
                "stringSet" -> {
                    val array = entry.getJSONArray("v")
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
                else -> continue
            }
            count++
        }
        editor.commit()
        return count
    }

    private fun csvField(value: String?): String {
        if (value == null) return ""
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
    }

    /** RFC 4180 parser: quoted fields, doubled-quote escapes, newlines inside quotes. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
