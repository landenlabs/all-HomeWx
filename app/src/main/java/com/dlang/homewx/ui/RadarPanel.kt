package com.dlang.homewx.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleInvalidationHandler
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.TilesOverlay
import java.net.HttpURLConnection
import java.net.URL
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
private const val RADAR_STEP_MINUTES = 5
private const val ANIMATION_FRAME_DELAY_MS = 400L

// NASA GIBS GOES-East satellite imagery (free, no API key), served as WMTS tiles. Frames are
// every 10 minutes and appear ~15-20 minutes after their valid time, so history frames end
// SATELLITE_LATENCY_MINUTES back from now rather than at the current mark (those tiles don't
// exist yet). GeoColor is true-color visible by day and IR-based at night; Band 13 is clean IR.
// GIBS orders the URL path {z}/{y}/{x}, unlike osmdroid's default {z}/{x}/{y}.
private const val GIBS_BASE_URL = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
private const val SATELLITE_STEP_MINUTES = 10
// Measured 2026-10-14: GIBS GeoColor frames lag ~50-60 min, IR ~30 min; newer timestamps 404.
private const val SATELLITE_LATENCY_MINUTES = 60

// Satellite imagery is fully opaque, so it's drawn at partial alpha to leave the base map's
// roads/borders/labels visible through it. Radar is already transparent where there's no echo.
private const val INFRARED_ALPHA = 0.6f
// TEMP: clouds left fully opaque while retesting; restore a partial value (was 0.6f) later.
private const val CLOUDS_ALPHA = 1f

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

private val RETRY_REDRAW_DELAYS_MS = longArrayOf(1_000L, 3_000L)

/** Which tile layer the radar tab overlays on the base map. [maxZoom] is the deepest zoom level
 *  the server actually has tiles for - deeper map zooms are scaled up from it by osmdroid. */
private enum class RadarLayer(val maxZoom: Int, val stepMinutes: Int, val gibsLayer: String?, val gibsMatrixSet: String?) {
    RADAR(18, RADAR_STEP_MINUTES, null, null),
    CLOUDS(7, SATELLITE_STEP_MINUTES, "GOES-East_ABI_GeoColor", "GoogleMapsCompatible_Level7"),
    INFRARED(6, SATELLITE_STEP_MINUTES, "GOES-East_ABI_Band13_Clean_Infrared", "GoogleMapsCompatible_Level6")
}

/** GIBS WMTS tile source for one [RadarLayer] at one [timeStamp] (ISO-8601 UTC, or "default"
 *  for the newest frame). [name] must be unique per frame - osmdroid's disk cache is keyed by
 *  source name, not URL. */
private class GibsTileSource(name: String, layer: RadarLayer, private val timeStamp: String) :
    OnlineTileSourceBase(name, 0, layer.maxZoom, 256, ".png", arrayOf(GIBS_BASE_URL)) {
    private val gibsLayer = layer.gibsLayer!!
    private val matrixSet = layer.gibsMatrixSet!!

    override fun getTileURLString(pMapTileIndex: Long): String {
        val url = "$baseUrl$gibsLayer/default/$timeStamp/$matrixSet/" +
            "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}.png"
        Log.d(TAG, "GibsTileSource.getTileURLString: $url")
        return url
    }
}

/** NEXRAD composite reflectivity tiles (free, no API key) from the Iowa Environmental Mesonet,
 *  or GOES satellite cloud imagery from NASA GIBS (see [RadarLayer]), drawn as an overlay on
 *  top of the standard OpenStreetMap base layer. Inflates itself into
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
    private val gibsTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val radarTileProvider: MapTileProviderBasic
    private val tileDownloader = TileDownloader()
    private var animationJob: Job? = null
    private var overzoomJob: Job? = null
    private var layer = RadarLayer.RADAR
    private val radarOverlay: TilesOverlay

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
            isDebugTileProviders = true
        }

        radarTileProvider = MapTileProviderBasic(context, liveTileSource())
        // Unlike the MapView's own built-in tile provider (which MapView wires to a
        // SimpleInvalidationHandler itself), a standalone MapTileProviderBasic like this one has
        // no handler by default - every asynchronous tile load completes with nowhere to report
        // to, so the view never redraws to show it. Without this, tiles that finish loading after
        // the initial (empty-cache) draw pass sit in memory unseen until some unrelated redraw
        // (e.g. panning) happens to reveal them.
        radarTileProvider.tileRequestCompleteHandlers.add(SimpleInvalidationHandler(binding.radarMapView))
        radarOverlay = TilesOverlay(radarTileProvider, context).apply {
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

        binding.radarLayerGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.radarLayerClouds -> RadarLayer.CLOUDS
                R.id.radarLayerInfrared -> RadarLayer.INFRARED
                else -> RadarLayer.RADAR
            }
            if (selected == layer) return@setOnCheckedChangeListener
            // Also stops a running animation: its frames were built for the old layer.
            stopAnimation()
            layer = selected
            applyLayerAlpha()
            showLiveFrame()
            Log.i(TAG, "layer switched to $layer: zoom=${binding.radarMapView.zoomLevelDouble} " +
                "overlayEnabled=${radarOverlay.isEnabled} overlays=${binding.radarMapView.overlays.size} " +
                "providerSource=${radarTileProvider.tileSource.name()} alpha=${layerAlpha()}")
            if (layer != RadarLayer.RADAR) probeLiveTile()
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

    /** Diagnostic: fetches one live tile for the current layer straight over HTTP, bypassing
     *  osmdroid, and logs status / content type / size / whether it decodes as a bitmap. Tells us
     *  whether "invisible" means the server/URL is bad or osmdroid isn't drawing what it got. */
    private fun probeLiveTile() {
        val source = liveTileSource() as? GibsTileSource ?: return
        val zoom = Math.round(binding.radarMapView.zoomLevelDouble).toInt().coerceAtMost(layer.maxZoom)
        val tile = visibleTileIndices().firstOrNull() ?: return
        val url = source.getTileURLString(tile)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", context.packageName)
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                val bytes = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                Log.i(TAG, "probeLiveTile: $url -> HTTP $code type=${conn.contentType} bytes=${bytes?.size} " +
                    "decoded=${bmp?.width}x${bmp?.height} zoom=$zoom")
            } catch (e: Exception) {
                Log.e(TAG, "probeLiveTile: $url failed", e)
            }
        }
    }

    private fun layerAlpha(): Float = when (layer) {
        RadarLayer.RADAR -> 1f
        RadarLayer.CLOUDS -> CLOUDS_ALPHA
        RadarLayer.INFRARED -> INFRARED_ALPHA
    }

    private fun applyLayerAlpha() {
        radarOverlay.setColorFilter(
            if (layerAlpha() >= 1f) null
            else ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 1f, 1f, layerAlpha()) })
        )
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
        val zoom = Math.round(mapView.zoomLevelDouble).toInt().coerceAtMost(layer.maxZoom)
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

    /** Every [RadarLayer.stepMinutes] UTC mark over the last 2 hours, oldest first - ending
     *  [SATELLITE_LATENCY_MINUTES] back for the satellite layers, whose newest frames lag. */
    private fun buildFrameTimestamps(): List<Date> {
        val step = layer.stepMinutes
        val latency = if (layer == RadarLayer.RADAR) 0 else SATELLITE_LATENCY_MINUTES
        val flooredNow = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis -= latency * 60_000L
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.MINUTE, get(Calendar.MINUTE) / step * step)
        }.timeInMillis
        return (ANIMATION_HISTORY_MINUTES downTo 0 step step)
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
        Log.i(TAG, "showLiveFrame: layer=$layer source=${source.name()} maxZoom=${source.maximumZoomLevel}")
        radarTileProvider.tileSource = source
        radarTileProvider.clearTileCache()
        binding.radarMapView.invalidate()
        binding.radarUpdatedText.text = "Updated ${updatedAtFormat.format(Date())}"
        // Requests still queued from the previous source/layer (often at a zoom the new source
        // doesn't have) fail after the swap, and osmdroid neither retries them nor redraws on
        // failure - SimpleInvalidationHandler only reacts to success - so those tiles stay blank
        // until a pan/zoom forces a redraw. Redraw a couple of times to re-request them.
        RETRY_REDRAW_DELAYS_MS.forEach { delayMs ->
            binding.radarMapView.postDelayed({ binding.radarMapView.invalidate() }, delayMs)
        }
        prefetchOverzoomParents(source)
    }

    /** The overlay asks for tiles at the map's zoom, but GIBS satellite layers stop at
     *  [RadarLayer.maxZoom] (IR: 6, home zoom: 7). Those over-zoomed requests just fail; osmdroid
     *  instead fills them by scaling up lower-zoom tiles it already has cached - and on a fresh
     *  layer switch nothing has fetched those, so the layer stays blank until a pan/zoom happens
     *  to pull them in. Fetching the max-zoom tiles for the viewport up front fixes that. */
    private fun prefetchOverzoomParents(source: OnlineTileSourceBase) {
        overzoomJob?.cancel()
        val mapZoom = Math.round(binding.radarMapView.zoomLevelDouble).toInt()
        if (layer == RadarLayer.RADAR || mapZoom <= layer.maxZoom) return
        val tiles = visibleTileIndices()
        val writer = radarTileProvider.tileWriter
        overzoomJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                tiles.map { tile ->
                    async {
                        try {
                            tileDownloader.downloadTile(tile, writer, source)
                        } catch (e: CantContinueException) {
                            Log.w(TAG, "prefetchOverzoomParents: gave up on tile $tile", e)
                        }
                    }
                }.awaitAll()
            }
            Log.i(TAG, "prefetchOverzoomParents: cached ${tiles.size} z${layer.maxZoom} tiles for map zoom $mapZoom")
            binding.radarMapView.invalidate()
        }
    }

    private fun liveTileSource(): OnlineTileSourceBase = when (layer) {
        RadarLayer.RADAR -> XYTileSource("IEM-NEXRAD", 0, 18, 256, ".png", arrayOf(LIVE_TILE_URL))
        // The newest frame that should exist by now, named by its own timestamp so each new
        // 10-minute frame gets a fresh disk-cache key rather than depending on cache expiry.
        else -> historyTileSource(buildFrameTimestamps().last())
    }

    private fun historyTileSource(time: Date): OnlineTileSourceBase {
        val stamp = frameTimestampFormat.format(time)
        if (layer != RadarLayer.RADAR) {
            return GibsTileSource("GIBS-${layer.name}-$stamp", layer, gibsTimeFormat.format(time))
        }
        return XYTileSource(
            "IEM-NEXRAD-HISTORY-$stamp", 0, 18, 256, ".png",
            arrayOf("$HISTORY_TILE_URL_PREFIX$stamp/")
        )
    }
}
