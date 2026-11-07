package com.landenlabs.homewx.ui

import android.util.Log
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "TileCache"

/**
 * osmdroid's SQLite tile cache with write-ahead logging (WAL) turned on. osmdroid keeps a single
 * static database for every tile provider (base map, radar, reference, prefetch) and opens it in
 * the default rollback-journal mode, where Android gives it one connection: every disk read waits
 * behind every write, and each tile save is its own fsync'd commit. With WAL, reads run alongside
 * the single writer and commits are cheaper. The mode is a property of the shared static handle,
 * so enabling it through any one instance covers every provider, including the MapView's own.
 */
class WalSqlTileWriter : SqlTileWriter() {
    init {
        enableWal()
    }

    private fun enableWal() {
        if (walEnabled) return
        synchronized(walLock) {
            if (walEnabled) return
            val database = db ?: return
            walEnabled = try {
                database.enableWriteAheadLogging().also { Log.i(TAG, "WAL enabled=$it on ${database.path}") }
            } catch (e: IllegalStateException) {
                // Thrown if a transaction (e.g. osmdroid's startup cache trim) is in progress;
                // left false so the next WalSqlTileWriter instance retries.
                Log.w(TAG, "WAL not enabled yet, will retry", e)
                false
            }
        }
    }

    companion object {
        private val walLock = Any()
        @Volatile private var walEnabled = false
    }
}

/** Running totals for [TimingTileWriter] - count, total and worst time - reported and reset by
 *  [report]. Save time includes waiting for the shared SQLite connection, so it shows contention. */
class TileTimingStats {
    private val saves = AtomicLong()
    private val saveNanos = AtomicLong()
    private val saveMaxNanos = AtomicLong()
    private val downloads = AtomicLong()
    private val downloadNanos = AtomicLong()
    private val downloadMaxNanos = AtomicLong()

    fun recordSave(nanos: Long) = record(saves, saveNanos, saveMaxNanos, nanos)

    /** [nanos] is the whole TileDownloader.downloadTile call (network + save). */
    fun recordDownload(nanos: Long) = record(downloads, downloadNanos, downloadMaxNanos, nanos)

    private fun record(count: AtomicLong, total: AtomicLong, max: AtomicLong, nanos: Long) {
        count.incrementAndGet()
        total.addAndGet(nanos)
        max.accumulateAndGet(nanos) { a, b -> maxOf(a, b) }
    }

    /** Logs everything recorded since the last report, then resets. */
    fun report(label: String, wallMillis: Long? = null) {
        val saveCount = saves.getAndSet(0)
        val saveTotal = saveNanos.getAndSet(0)
        val saveMax = saveMaxNanos.getAndSet(0)
        val dlCount = downloads.getAndSet(0)
        val dlTotal = downloadNanos.getAndSet(0)
        val dlMax = downloadMaxNanos.getAndSet(0)
        if (saveCount == 0L && dlCount == 0L) return
        fun ms(n: Long) = n / 1_000_000
        val avgSave = if (saveCount > 0) ms(saveTotal / saveCount) else 0
        val avgDl = if (dlCount > 0) ms(dlTotal / dlCount) else 0
        Log.i(
            TAG, "$label: " + (wallMillis?.let { "wall=${it}ms " } ?: "") +
                "downloadTile n=$dlCount avg=${avgDl}ms max=${ms(dlMax)}ms | " +
                "save n=$saveCount avg=${avgSave}ms max=${ms(saveMax)}ms total=${ms(saveTotal)}ms | " +
                "network~avg=${(avgDl - avgSave).coerceAtLeast(0)}ms"
        )
    }
}

/**
 * Passes everything through to [delegate], timing [saveFile]. osmdroid's downloader calls
 * saveFile once per downloaded tile, so this splits "download" time into network vs. cache-write
 * (including the wait for the SQLite lock). Disk *reads* for drawing don't come through here:
 * osmdroid's SQL cache provider reads the shared database directly.
 */
class TimingTileWriter(private val delegate: IFilesystemCache, private val stats: TileTimingStats) : IFilesystemCache by delegate {
    override fun saveFile(pTileSourceInfo: ITileSource, pMapTileIndex: Long, pStream: InputStream, pExpirationTime: Long?): Boolean {
        val start = System.nanoTime()
        try {
            return delegate.saveFile(pTileSourceInfo, pMapTileIndex, pStream, pExpirationTime)
        } finally {
            stats.recordSave(System.nanoTime() - start)
        }
    }
}

/**
 * Diagnostic: checks whether calling HttpURLConnection.disconnect() after each tile (as osmdroid's
 * TileDownloader does) stops connections being reused, so every tile pays a new TCP + TLS
 * handshake. Fetches [urls] once to warm the server's cache, then twice more in sequence -
 * disconnecting after each request, then just closing the stream (which lets the connection go
 * back to the pool) - and logs each request's time. If disconnect kills reuse, requests 2..n of
 * the "disconnect" pass stay as slow as request 1, while those of the "keep-alive" pass drop.
 * Blocking; call off the main thread.
 */
fun probeConnectionReuse(urls: List<String>, userAgent: String) {
    if (urls.size < 2) return
    fun fetch(url: String, disconnect: Boolean): String {
        val start = System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            val size = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().size } ?: 0
            "${(System.nanoTime() - start) / 1_000_000}ms(HTTP $code,${size}B)"
        } catch (e: Exception) {
            "${(System.nanoTime() - start) / 1_000_000}ms(${e.javaClass.simpleName})"
        } finally {
            if (disconnect) conn.disconnect()
        }
    }
    urls.forEach { fetch(it, disconnect = true) } // warm server-side cache; also empties the pool
    val withDisconnect = urls.map { fetch(it, disconnect = true) }
    val keepAlive = urls.map { fetch(it, disconnect = false) }
    Log.i(TAG, "probeConnectionReuse host=${URL(urls.first()).host}")
    Log.i(TAG, "  disconnect each (osmdroid): ${withDisconnect.joinToString(" ")}")
    Log.i(TAG, "  keep-alive (pooled)       : ${keepAlive.joinToString(" ")}")
}
