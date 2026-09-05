package com.dlang.homewx.ui

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelRadarBinding
import com.dlang.homewx.state.AppState
import com.dlang.homewx.weather.HomeLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.CantContinueException
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleInvalidationHandler
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

private const val HOME_ZOOM = 7.0
private const val LIVE_TILE_URL = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/"

// IEM's time-indexed national composite layer, distinct from the live-only layer above -
// each 5-minute UTC mark back to 2011 is addressable as ridge::USCOMP-N0Q-<yyyyMMddHHmm>.
private const val HISTORY_TILE_URL_PREFIX = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/ridge::USCOMP-N0Q-"
private const val ANIMATION_HISTORY_MINUTES = 120
private const val ANIMATION_STEP_MINUTES = 5
private const val ANIMATION_FRAME_DELAY_MS = 400L

// osmdroid's in-memory tile cache is cleared on every tileSource swap and is keyed only by
// (z,x,y) - not by source - so it can never hold more than one frame's tiles at a time. Without
// pre-warming, each 400ms frame flip races the network/disk to fill the viewport from scratch,
// which is why most frames render blank. Prefetching every frame's visible tiles into osmdroid's
// disk cache first means playback only has to read from disk, which comfortably fits the budget.
private const val PREFETCH_CONCURRENCY = 16
private const val PREFETCH_PROGRESS_STEP = 20
private const val TAG = "RadarPanel"

// TilesOverlay computes its own visible tile rect from the MapView's pixel viewport at draw
// time, which can round to a 1-tile-wider rect than CacheManager.getTilesRect() gets from the
// MapView's lat/lon boundingBox corners. Padding the prefetch area avoids a coverage gap at the
// edges - confirmed from a device log where a tile the overlay needed still had to be fetched
// live during playback because it fell just outside our unpadded prefetch rect.
private const val PREFETCH_TILE_PADDING = 1

/** NEXRAD composite reflectivity tiles (free, no API key) from the Iowa Environmental Mesonet,
 *  drawn as an overlay on top of the standard OpenStreetMap base layer. Inflates itself into
 *  [container]. Tiles are cached to disk by osmdroid with a short expiry, so [refresh] just
 *  needs to invalidate the map to pick up whatever the server currently has. The play button
 *  loops through the last 2 hours of 5-minute composite frames; [stopAnimation] returns to the
 *  live layer and is called from outside whenever the Radar tab is no longer visible. */
class RadarPanel(container: ViewGroup, private val lifecycleScope: LifecycleCoroutineScope) {

    private val context = container.context
    private val binding = PanelRadarBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root

    private val updatedAtFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val frameTimestampFormat = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val radarTileProvider: MapTileProviderBasic
    private val tileDownloader = TileDownloader()
    private var animationJob: Job? = null

    init {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            // Raise these from osmdroid's defaults (2 download threads, 8 filesystem threads) so
            // the animation prefetch below can actually saturate PREFETCH_CONCURRENCY.
            tileDownloadThreads = 8
            tileDownloadMaxQueueSize = 100
            tileFileSystemThreads = 16
            tileFileSystemMaxQueueSize = 100
            // Logs every tile request/response (url, HTTP status, mime type) under tag "OsmDroid",
            // so failures during prefetch/playback show up in logcat instead of silently
            // rendering blank.
            isDebugMode = true
            isDebugMapTileDownloader = true
        }

        radarTileProvider = MapTileProviderBasic(context, liveTileSource())
        // Unlike the MapView's own built-in tile provider (which MapView wires to a
        // SimpleInvalidationHandler itself), a standalone MapTileProviderBasic like this one has
        // no handler by default - every asynchronous tile load completes with nowhere to report
        // to, so the view never redraws to show it. Without this, tiles that finish loading after
        // the initial (empty-cache) draw pass sit in memory unseen until some unrelated redraw
        // (e.g. panning) happens to reveal them.
        radarTileProvider.tileRequestCompleteHandlers.add(SimpleInvalidationHandler(binding.radarMapView))
        val radarOverlay = TilesOverlay(radarTileProvider, context).apply {
            loadingBackgroundColor = Color.TRANSPARENT
            loadingLineColor = Color.TRANSPARENT
        }

        binding.radarMapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            overlays.add(radarOverlay)
            controller.setZoom(HOME_ZOOM)
            controller.setCenter(GeoPoint(HomeLocation.CURRENT.latitude, HomeLocation.CURRENT.longitude))
        }

        binding.radarPlayButton.setOnClickListener {
            if (animationJob == null) startAnimation() else stopAnimation()
        }

        // Base map + radar tiles are both network-dependent, and osmdroid doesn't surface a
        // per-tile failure callback we can hook cleanly - the network's own reachable flag
        // (already tracked in HomeWxMonitorService) is a simpler, reliable proxy for "this map
        // has nothing new to show right now" than trying to infer it from tile load failures.
        lifecycleScope.launch {
            AppState.uiState.map { it.networkReachable }.distinctUntilChanged().collect { reachable ->
                binding.radarNoDataText.visibility = if (reachable) View.GONE else View.VISIBLE
            }
        }

        container.addView(root)
    }

    /** Clears the cached radar tiles and redraws, so reopening this tab shows the latest sweep. */
    fun refresh() {
        showLiveFrame()
    }

    /** Stops any running animation and drops back to the live tile source. Safe to call even
     *  when nothing is playing, so callers can invoke it unconditionally on tab switch. */
    fun stopAnimation() {
        if (animationJob == null) return
        animationJob?.cancel()
        animationJob = null
        binding.radarPlayButton.setImageResource(R.drawable.ic_play)
        binding.radarPlayButton.contentDescription = context.getString(R.string.radar_play_animation)
        showLiveFrame()
    }

    private fun startAnimation() {
        val frameTimes = buildFrameTimestamps()
        Log.i(TAG, "startAnimation: ${frameTimes.size} frames, oldest=${frameTimes.first()} newest=${frameTimes.last()}")
        animationJob = lifecycleScope.launch {
            prefetchFrames(frameTimes)
            binding.radarPlayButton.setImageResource(R.drawable.ic_pause)
            binding.radarPlayButton.contentDescription = context.getString(R.string.radar_pause_animation)
            var index = 0
            while (true) {
                showHistoryFrame(frameTimes[index])
                index = (index + 1) % frameTimes.size
                delay(ANIMATION_FRAME_DELAY_MS)
            }
        }
    }

    /** Downloads every frame's visible tiles into osmdroid's on-disk cache before playback starts.
     *  Without this, each frame flip during [startAnimation]'s loop has to fetch its tiles from
     *  the network from scratch - osmdroid's in-memory tile cache is cleared on every tileSource
     *  swap and can only ever hold one frame's worth of tiles - so most frames rendered blank. */
    private suspend fun prefetchFrames(frameTimes: List<Date>) {
        val tileIndices = visibleTileIndices()
        if (tileIndices.isEmpty()) {
            Log.w(TAG, "prefetchFrames: no visible tile indices for current viewport/zoom - skipping prefetch")
            return
        }
        val writer = radarTileProvider.tileWriter
        val semaphore = Semaphore(PREFETCH_CONCURRENCY)
        val total = frameTimes.size * tileIndices.size
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val sampleSource = historyTileSource(frameTimes.last())
        Log.i(
            TAG, "prefetchFrames: fetching $total tiles (${frameTimes.size} frames x " +
                "${tileIndices.size} tiles), sample url=${sampleSource.getTileURLString(tileIndices.first())}"
        )
        binding.radarUpdatedText.text = context.getString(R.string.radar_loading_animation)
        withContext(Dispatchers.IO) {
            frameTimes.flatMap { time ->
                val source = historyTileSource(time)
                tileIndices.map { tileIndex ->
                    async {
                        semaphore.withPermit {
                            val drawable = try {
                                tileDownloader.downloadTile(tileIndex, writer, source)
                            } catch (e: CantContinueException) {
                                Log.w(TAG, "prefetchFrames: gave up on tile $tileIndex for ${source.name()}", e)
                                null
                            }
                            if (drawable == null) failed.incrementAndGet()
                        }
                        val done = completed.incrementAndGet()
                        if (done == total || done % PREFETCH_PROGRESS_STEP == 0) {
                            withContext(Dispatchers.Main) {
                                binding.radarUpdatedText.text = context.getString(
                                    R.string.radar_loading_animation_progress, done * 100 / total
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(TAG, "prefetchFrames: done, ${total - failed.get()}/$total tiles cached, ${failed.get()} failed")
    }

    /** Tile indices (z/x/y, encoded) covering the map's current viewport at its current zoom,
     *  padded by [PREFETCH_TILE_PADDING] tiles on each side to absorb rounding differences
     *  between this rect and the one TilesOverlay computes for itself at draw time. */
    private fun visibleTileIndices(): List<Long> {
        val mapView = binding.radarMapView
        val zoom = Math.round(mapView.zoomLevelDouble).toInt()
        val rect = CacheManager.getTilesRect(mapView.boundingBox, zoom)
        val tileUpperBound = 1 shl zoom
        val tiles = mutableListOf<Long>()
        for (x in (rect.left - PREFETCH_TILE_PADDING)..(rect.right + PREFETCH_TILE_PADDING)) {
            val wrappedX = ((x % tileUpperBound) + tileUpperBound) % tileUpperBound
            for (y in (rect.top - PREFETCH_TILE_PADDING)..(rect.bottom + PREFETCH_TILE_PADDING)) {
                if (y < 0 || y >= tileUpperBound) continue
                tiles.add(MapTileIndex.getTileIndex(zoom, wrappedX, y))
            }
        }
        Log.i(TAG, "visibleTileIndices: zoom=$zoom rect=$rect padding=$PREFETCH_TILE_PADDING -> ${tiles.size} tiles")
        return tiles
    }

    /** Every 5-minute UTC mark over the last 2 hours, oldest first. */
    private fun buildFrameTimestamps(): List<Date> {
        val flooredNow = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.MINUTE, get(Calendar.MINUTE) / ANIMATION_STEP_MINUTES * ANIMATION_STEP_MINUTES)
        }.timeInMillis
        return (ANIMATION_HISTORY_MINUTES downTo 0 step ANIMATION_STEP_MINUTES)
            .map { minutesAgo -> Date(flooredNow - minutesAgo * 60_000L) }
    }

    private fun showHistoryFrame(time: Date) {
        val source = historyTileSource(time)
        Log.d(TAG, "showHistoryFrame: ${frameTimestampFormat.format(time)} source=${source.name()}")
        radarTileProvider.tileSource = source
        radarTileProvider.clearTileCache()
        binding.radarMapView.invalidate()
        binding.radarUpdatedText.text = updatedAtFormat.format(time)
    }

    private fun showLiveFrame() {
        val source = liveTileSource()
        Log.d(TAG, "showLiveFrame: source=${source.name()} url=${LIVE_TILE_URL}")
        radarTileProvider.tileSource = source
        radarTileProvider.clearTileCache()
        binding.radarMapView.invalidate()
        binding.radarUpdatedText.text = "Updated ${updatedAtFormat.format(Date())}"
    }

    private fun liveTileSource() = XYTileSource("IEM-NEXRAD", 0, 18, 256, ".png", arrayOf(LIVE_TILE_URL))

    private fun historyTileSource(time: Date): XYTileSource {
        val stamp = frameTimestampFormat.format(time)
        return XYTileSource(
            "IEM-NEXRAD-HISTORY-$stamp", 0, 18, 256, ".png",
            arrayOf("$HISTORY_TILE_URL_PREFIX$stamp/")
        )
    }
}
