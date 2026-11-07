package com.landenlabs.homewx.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.withStarted
import com.landenlabs.homewx.R
import com.landenlabs.homewx.databinding.PanelRadarBinding
import com.landenlabs.homewx.settings.AppSettings
import com.landenlabs.homewx.settings.IdleSubPanelKeys
import com.landenlabs.homewx.state.AppState
import com.landenlabs.homewx.weather.DeviceLocation
import com.landenlabs.homewx.weather.HomeLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.osmdroid.tileprovider.modules.CantContinueException
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleInvalidationHandler
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.RectL
import org.osmdroid.util.TileSystem
import org.osmdroid.views.overlay.Marker
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

// While the tab is showing, the live layer is reloaded on this period - the same cadence as IEM's
// 5-minute NEXRAD composites and the tiles' max-age=300.
private const val AUTO_REFRESH_MS = 5 * 60 * 1000L
// The live IEM tiles are written to osmdroid's disk cache with this much shaved off the server's
// max-age. Without it, tiles that finished downloading a few seconds after one refresh would
// still be "fresh" when the next refresh fires exactly 5 minutes later, so they'd be reused from
// disk and the radar would only really update every other tick (10 minutes).
private const val LIVE_TILE_EARLY_EXPIRY_MS = 60 * 1000L
// Bottom of the house path in ic_home_marker (y=21 of 24), so the doorstep sits on the point.
private const val HOME_MARKER_ANCHOR_Y = 21f / 24f
private const val LIVE_TILE_URL = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/"

// IEM's "Q2" radar-based rainfall estimate, summed over the last 72 hours (inches). A rolling
// total, not a series of time steps, so it has no history frames and no animation. Same host and
// {z}/{x}/{y} order as the NEXRAD layer above; the server sends max-age=300, so osmdroid's disk
// cache expires it on its own like the live radar tiles.
private const val RAIN_72H_TILE_URL = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/q2-p72h-900913/"
private const val RAIN_72H_MAX_ZOOM = 10 // data is ~1 km; deeper zooms would only be upscaled

// NOAA WPC quantitative precipitation forecast, cumulative over the next 72 hours (days 1-3).
// An ArcGIS MapServer with no pre-rendered tiles, so [WpcQpfTileSource] asks it to render each
// tile on demand via /export. Layer 9 is "QPF 72 Hour Day 1-3" in that service.
private const val WPC_QPF_EXPORT_URL = "https://mapservices.weather.noaa.gov/vector/rest/services/precip/wpc_qpf/MapServer/export"
private const val WPC_QPF_72H_LAYER = 9
private const val WPC_QPF_MAX_ZOOM = 10
private const val WEB_MERCATOR_WORLD_METERS = 40075016.685578488

// IEM's time-indexed national composite layer, distinct from the live-only layer above -
// each 5-minute UTC mark back to 2011 is addressable as ridge::USCOMP-N0Q-<yyyyMMddHHmm>.
private const val HISTORY_TILE_URL_PREFIX = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/ridge::USCOMP-N0Q-"
private const val ANIMATION_HISTORY_MINUTES = 120
private const val RADAR_STEP_MINUTES = 5
// Minimum time each frame stays on screen. A frame can stay longer if the hidden buffer is
// still loading the next one (see playFrames' per-pass log).
private const val ANIMATION_FRAME_DELAY_MS = 200L

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
// Sequential requests per pass in probeConnectionReuse (see TileCacheDiagnostics).
private const val CONNECTION_PROBE_TILES = 5
private const val TAG = "RadarPanel"

// Playback starts as soon as this many frames (downloaded newest first) are cached; the older
// frames join the loop as their downloads finish, instead of waiting ~12 s for all 25.
private const val MIN_FRAMES_TO_START = 3
// How long a frame may take to load into the hidden buffer before it's shown anyway - e.g. a
// tile the server doesn't have never "arrives", and the loop mustn't stall on it.
private const val FRAME_LOAD_TIMEOUT_MS = 2_000L
private const val FRAME_LOAD_POLL_MS = 25L

// Esri "Dark Gray Canvas" (no API key; attribution: Esri, HERE, Garmin, (c) OpenStreetMap contributors).
// CARTO's dark tiles now return an "API KEY REQUIRED" watermark. The base has no labels or
// boundaries - those come from a separate transparent reference layer drawn above the radar.
private const val ESRI_CANVAS_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/"
private const val ESRI_MAX_ZOOM = 16
private val DARK_BASE_TILE_SOURCE = EsriTileSource("ESRI-DarkGrayBase", "World_Dark_Gray_Base", ".jpg")
private val DARK_REFERENCE_TILE_SOURCE = EsriTileSource("ESRI-DarkGrayReference", "World_Dark_Gray_Reference", ".png")

/** Esri ArcGIS REST tile source; like GIBS it orders the URL path {z}/{y}/{x}. */
private class EsriTileSource(name: String, private val service: String, ext: String) :
    OnlineTileSourceBase(name, 0, ESRI_MAX_ZOOM, 256, ext, arrayOf(ESRI_CANVAS_URL)) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "$baseUrl$service/MapServer/tile/${MapTileIndex.getZoom(pMapTileIndex)}/" +
            "${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
}

/** Expires cached tiles [LIVE_TILE_EARLY_EXPIRY_MS] before the server says to (never before
 *  now), so each auto-refresh finds the previous tick's tiles stale and re-downloads them. */
private class EarlyExpiryTilePolicy : TileSourcePolicy() {
    override fun computeExpirationTime(pHttpExpiresHeader: String?, pHttpCacheControlHeader: String?, pNow: Long): Long =
        maxOf(pNow, super.computeExpirationTime(pHttpExpiresHeader, pHttpCacheControlHeader, pNow) - LIVE_TILE_EARLY_EXPIRY_MS)
}

private val RETRY_REDRAW_DELAYS_MS = longArrayOf(1_000L, 3_000L)

/** Which tile layer the radar tab overlays on the base map. [maxZoom] is the deepest zoom level
 *  the server actually has tiles for - deeper map zooms are scaled up from it by osmdroid. */
private enum class RadarLayer(val maxZoom: Int, val stepMinutes: Int, val gibsLayer: String?, val gibsMatrixSet: String?) {
    RADAR(18, RADAR_STEP_MINUTES, null, null),
    CLOUDS(7, SATELLITE_STEP_MINUTES, "GOES-East_ABI_GeoColor", "GoogleMapsCompatible_Level7"),
    INFRARED(6, SATELLITE_STEP_MINUTES, "GOES-East_ABI_Band13_Clean_Infrared", "GoogleMapsCompatible_Level6"),

    /** 72-hour rainfall total; [stepMinutes] is unused since it is never animated. */
    RAIN_72H(RAIN_72H_MAX_ZOOM, RADAR_STEP_MINUTES, null, null),

    /** Forecast 72-hour rainfall total; same no-animation treatment as [RAIN_72H]. */
    RAIN_FORECAST_72H(WPC_QPF_MAX_ZOOM, RADAR_STEP_MINUTES, null, null);

    val isSatellite: Boolean get() = gibsLayer != null
    val isAnimatable: Boolean get() = this != RAIN_72H && this != RAIN_FORECAST_72H
}

/** WPC QPF rendered per tile: converts the tile's z/x/y to its Web Mercator bounding box and
 *  requests that box from the MapServer's export endpoint as a transparent 256px PNG. [name] must
 *  be unique per fetch window - osmdroid's disk cache is keyed by source name, and this server
 *  sends max-age=0 - so [liveTileSource] stamps it with the current hour. */
private class WpcQpfTileSource(name: String) :
    OnlineTileSourceBase(name, 0, WPC_QPF_MAX_ZOOM, 256, ".png", arrayOf(WPC_QPF_EXPORT_URL)) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val tileMeters = WEB_MERCATOR_WORLD_METERS / (1 shl MapTileIndex.getZoom(pMapTileIndex))
        val minX = -WEB_MERCATOR_WORLD_METERS / 2 + MapTileIndex.getX(pMapTileIndex) * tileMeters
        val maxY = WEB_MERCATOR_WORLD_METERS / 2 - MapTileIndex.getY(pMapTileIndex) * tileMeters
        return "$baseUrl?bbox=$minX,${maxY - tileMeters},${minX + tileMeters},$maxY" +
            "&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true" +
            "&layers=show:$WPC_QPF_72H_LAYER&f=image"
    }
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
        // Log.d(TAG, "GibsTileSource.getTileURLString: $url")
        return url
    }
}

/** NEXRAD composite reflectivity tiles (free, no API key) from the Iowa Environmental Mesonet,
 *  or GOES satellite cloud imagery from NASA GIBS (see [RadarLayer]), drawn as an overlay on
 *  top of the standard OpenStreetMap base layer. Inflates itself into
 *  [container]. Tiles are cached to disk by osmdroid with a short expiry, so [refresh] just
 *  needs to invalidate the map to pick up whatever the server currently has. The play button
 *  loops through the last 2 hours of 5-minute composite frames; [stopAnimation] returns to the
 *  live layer and is called from outside whenever the Radar tab is no longer visible. While the
 *  tab is visible and the activity is started, the live layer reloads every [AUTO_REFRESH_MS].
 *  A house marker shows home: the device's own fix when it is within +/- 5 miles, otherwise the
 *  Settings zip centroid ([HomeLocation]). */
class RadarPanel(
    container: ViewGroup,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val lifecycle: Lifecycle,
) {

    private val context = container.context
    private var centeredOn: com.landenlabs.homewx.weather.GeoLocation = HomeLocation.DEFAULT

    // Must run before [binding] is inflated: inflating creates the MapView, whose built-in
    // base-map tile provider sizes its download/filesystem thread pools from Configuration at
    // construction and never re-reads it. Kotlin runs initializers in declaration order.
    init {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            // Raise these from osmdroid's defaults (2 download threads, 8 filesystem threads) for
            // the base map and every TilesOverlay provider, and so the animation prefetch can
            // actually saturate PREFETCH_CONCURRENCY.
            tileDownloadThreads = 8
            tileDownloadMaxQueueSize = 100
            tileFileSystemThreads = 16
            tileFileSystemMaxQueueSize = 100
            // Per-tile logging is off for now. Uncomment to log every tile request/response
            // (url, HTTP status, mime type) under tag "OsmDroid", so failures during
            // prefetch/playback show up in logcat instead of silently rendering blank.
            // isDebugMode = true
            // isDebugMapTileDownloader = true
            // Must stay off: besides logging, TilesOverlay draws each tile's top/left edge and
            // z/x/y index onto the map when this is set.
            isDebugTileProviders = false
        }
        // Resolve the cache dir with a Context first, as MapView's constructor does. Without a
        // Context osmdroid's base path is null, it caches "/tiles" as the tile cache for the rest
        // of the process, and the SQLite cache (shared by every layer) can never open.
        Configuration.getInstance().getOsmdroidTileCache(context)
        // Switch osmdroid's shared SQLite tile cache to WAL before the MapView (and its base-map
        // provider) starts using it - see [WalSqlTileWriter].
        WalSqlTileWriter()
    }

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
    /** Save/download timings for the radar and reference providers plus the prefetches; logged
     *  (tag "TileCache") and reset at the end of each prefetch and on each auto-refresh tick. */
    private val tileTiming = TileTimingStats()
    private var animationJob: Job? = null
    private var overzoomJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var homeFixJob: Job? = null
    private var layer = RadarLayer.RADAR
    private val radarOverlay: TilesOverlay
    /** Animation back buffer, the same kind of layer as [radarTileProvider]/[radarOverlay] and
     *  drawn in the same z position. During playback the two alternate: the next frame loads into
     *  whichever one is hidden while the other keeps showing the current frame, and they swap
     *  only once it has fully loaded - see [startAnimation]. Disabled (and its cache empty)
     *  whenever no animation is playing. Two providers are needed because osmdroid's memory tile
     *  cache is keyed by z/x/y only and cleared on every tileSource swap, so one provider can
     *  never hold two frames at once. */
    private val frameTileProvider: MapTileProviderBasic
    private val frameOverlay: TilesOverlay
    /** Animation prefetch progress (0-100) while it is still downloading, else null. */
    private var prefetchPercent: Int? = null
    /** True once [playFrames] has shown its first frame, until [stopAnimation]. */
    private var playbackRunning = false
    private val referenceTileProvider: MapTileProviderBasic
    private val referenceOverlay: TilesOverlay
    private val bordersOverlay = BordersOverlay(container.resources.displayMetrics.density).apply { isEnabled = false }
    private val homeMarker = Marker(binding.radarMapView).apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_home_marker)
        setAnchor(Marker.ANCHOR_CENTER, HOME_MARKER_ANCHOR_Y)
        setInfoWindow(null)
        // Swallow taps so they neither open a (missing) info window nor recenter the map.
        setOnMarkerClickListener { _, _ -> true }
    }

    init {
        radarTileProvider = MapTileProviderBasic(context, liveTileSource(), TimingTileWriter(WalSqlTileWriter(), tileTiming))
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

        frameTileProvider = MapTileProviderBasic(context, liveTileSource(), TimingTileWriter(WalSqlTileWriter(), tileTiming))
        // Same reason as radarTileProvider: needed if a frame is shown before all its tiles
        // landed (FRAME_LOAD_TIMEOUT_MS) so the late ones still get drawn.
        frameTileProvider.tileRequestCompleteHandlers.add(SimpleInvalidationHandler(binding.radarMapView))
        frameOverlay = TilesOverlay(frameTileProvider, context).apply {
            loadingBackgroundColor = Color.TRANSPARENT
            loadingLineColor = Color.TRANSPARENT
            isEnabled = false
        }

        referenceTileProvider = MapTileProviderBasic(context, DARK_REFERENCE_TILE_SOURCE, TimingTileWriter(WalSqlTileWriter(), tileTiming))
        referenceTileProvider.tileRequestCompleteHandlers.add(SimpleInvalidationHandler(binding.radarMapView))
        referenceOverlay = TilesOverlay(referenceTileProvider, context).apply {
            loadingBackgroundColor = Color.TRANSPARENT
            loadingLineColor = Color.TRANSPARENT
            isEnabled = AppSettings.isDarkMapTheme(context)
        }

        binding.radarMapView.apply {
            setTileSource(baseTileSource())
            setMultiTouchControls(true)
            overlays.add(radarOverlay)
            overlays.add(frameOverlay)
            // Dark-theme labels/boundaries, above the radar/satellite so they stay readable.
            overlays.add(referenceOverlay)
            // Above the satellite tiles so borders stay visible; only enabled for those layers.
            overlays.add(bordersOverlay)
            // Last, so the home pin draws on top of every tile layer.
            overlays.add(homeMarker)
            controller.setZoom(HOME_ZOOM)
            centeredOn = HomeLocation.current(context)
            controller.setCenter(GeoPoint(centeredOn.latitude, centeredOn.longitude))
        }
        homeMarker.position = GeoPoint(centeredOn.latitude, centeredOn.longitude)

        binding.radarLayerGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.radarLayerClouds -> RadarLayer.CLOUDS
                R.id.radarLayerInfrared -> RadarLayer.INFRARED
                R.id.radarLayerRain72 -> RadarLayer.RAIN_72H
                R.id.radarLayerRainForecast72 -> RadarLayer.RAIN_FORECAST_72H
                else -> RadarLayer.RADAR
            }
            if (selected == layer) return@setOnCheckedChangeListener
            // Also stops a running animation: its frames were built for the old layer.
            stopAnimation()
            layer = selected
            // Only the opaque satellite imagery needs the borders drawn over it; radar and the
            // rainfall total are transparent where there's no data, so the base map shows through.
            bordersOverlay.isEnabled = layer.isSatellite
            applyPlayButtonState()
            applyLayerAlpha()
            showLiveFrame()
            Log.i(TAG, "layer switched to $layer: zoom=${binding.radarMapView.zoomLevelDouble} " +
                "overlayEnabled=${radarOverlay.isEnabled} overlays=${binding.radarMapView.overlays.size} " +
                "providerSource=${radarTileProvider.tileSource.name()} alpha=${layerAlpha()}")
            if (layer.isSatellite) probeLiveTile()
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { bordersOverlay.load(context) }
            binding.radarMapView.invalidate()
        }

        applyPlayButtonState()
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

    private fun baseTileSource() =
        if (AppSettings.isDarkMapTheme(context)) DARK_BASE_TILE_SOURCE else TileSourceFactory.MAPNIK

    /** Swaps the base map if the Settings theme changed since it was last applied. */
    private fun applyMapTheme() {
        referenceOverlay.isEnabled = AppSettings.isDarkMapTheme(context)
        val wanted = baseTileSource()
        if (binding.radarMapView.tileProvider.tileSource.name() == wanted.name()) return
        Log.i(TAG, "applyMapTheme: base map -> ${wanted.name()}")
        binding.radarMapView.setTileSource(wanted)
    }

    private fun layerAlpha(): Float = when (layer) {
        RadarLayer.RADAR -> 1f
        RadarLayer.CLOUDS -> CLOUDS_ALPHA
        RadarLayer.INFRARED -> INFRARED_ALPHA
        RadarLayer.RAIN_72H, RadarLayer.RAIN_FORECAST_72H -> 1f
    }

    /** The rainfall total is a single rolling sum with no frames to step through, so its play
     *  button is dimmed and inert; every other layer animates as before. */
    private fun applyPlayButtonState() {
        binding.radarPlayButton.isEnabled = layer.isAnimatable
        binding.radarPlayButton.alpha = if (layer.isAnimatable) 1f else 0.3f
    }

    private fun applyLayerAlpha() {
        val filter = if (layerAlpha() >= 1f) null
            else ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 1f, 1f, layerAlpha()) })
        radarOverlay.setColorFilter(filter)
        frameOverlay.setColorFilter(filter)
    }

    /** Picks the layer named by an [IdleSubPanelKeys] Map key (default: radar) through the same
     *  radio group a tap would use, so animation stop, alpha and borders all follow. */
    fun selectLayer(key: String) {
        binding.radarLayerGroup.check(
            when (key) {
                IdleSubPanelKeys.MAP_CLOUDS -> R.id.radarLayerClouds
                IdleSubPanelKeys.MAP_IR -> R.id.radarLayerInfrared
                IdleSubPanelKeys.MAP_RAIN_PAST -> R.id.radarLayerRain72
                IdleSubPanelKeys.MAP_RAIN_FORECAST -> R.id.radarLayerRainForecast72
                else -> R.id.radarLayerRadar
            }
        )
    }

    /** Clears the cached radar tiles and redraws, so reopening this tab shows the latest sweep.
     *  Also re-run by the [AUTO_REFRESH_MS] timer while the tab stays visible. */
    fun refresh() {
        // Home location changed in Settings - move the map there (otherwise leave the user's pan alone).
        val home = HomeLocation.current(context)
        if (home != centeredOn) {
            centeredOn = home
            binding.radarMapView.controller.setCenter(GeoPoint(home.latitude, home.longitude))
        }
        applyMapTheme()
        showLiveFrame()
        updateHomeMarker()
    }

    /** Re-arms the one-shot auto-refresh. Called after every live-frame load, so the next reload
     *  is always [AUTO_REFRESH_MS] after the last one however it was triggered (tab switch, layer
     *  switch, end of animation). The job just ends - no reload, nothing re-armed - if the tab is
     *  hidden or an animation is playing when it fires; the next [refresh]/[stopAnimation] re-arms
     *  it. While the activity is stopped it waits, then reloads as soon as it is started again. */
    private fun scheduleAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            delay(AUTO_REFRESH_MS)
            lifecycle.withStarted {}
            autoRefreshJob = null
            if (root.isShown && animationJob == null) {
                Log.i(TAG, "auto-refresh: reloading layer=$layer")
                tileTiming.report("live loads since last report")
                refresh()
            }
        }
    }

    /** Puts the house marker on the Settings zip centroid right away, then moves it to the
     *  device's own position if a fix within +/- 5 miles comes back. */
    private fun updateHomeMarker() {
        val home = HomeLocation.current(context)
        homeMarker.position = GeoPoint(home.latitude, home.longitude)
        binding.radarMapView.invalidate()
        homeFixJob?.cancel()
        homeFixJob = lifecycleScope.launch {
            val fix = DeviceLocation.accurateFix(context) ?: return@launch
            Log.i(TAG, "updateHomeMarker: device fix ${fix.provider} +/-${fix.accuracy}m")
            homeMarker.position = GeoPoint(fix.latitude, fix.longitude)
            binding.radarMapView.invalidate()
        }
    }

    /** Stops any running animation and drops back to the live tile source. Safe to call even
     *  when nothing is playing, so callers can invoke it unconditionally on tab switch. */
    fun stopAnimation() {
        if (animationJob == null) return
        animationJob?.cancel()
        animationJob = null
        prefetchPercent = null
        playbackRunning = false
        binding.radarPlayButton.setImageResource(R.drawable.ic_play)
        binding.radarPlayButton.contentDescription = context.getString(R.string.radar_play_animation)
        // The live layer always lives in radarOverlay; drop the back buffer and its frame.
        radarOverlay.isEnabled = true
        frameOverlay.isEnabled = false
        frameTileProvider.clearTileCache()
        showLiveFrame()
    }

    private fun startAnimation() {
        if (!layer.isAnimatable) return
        val frameTimes = buildFrameTimestamps()
        Log.i(TAG, "startAnimation: ${frameTimes.size} frames, oldest=${frameTimes.first()} newest=${frameTimes.last()}")
        animationJob = lifecycleScope.launch {
            // Main-thread only: which frames are fully in the disk cache and can be played.
            val cached = BooleanArray(frameTimes.size)
            var cachedCount = 0
            coroutineScope {
                launch {
                    prefetchFrames(frameTimes) { frame -> cached[frame] = true; cachedCount++ }
                }
                val startAfter = minOf(MIN_FRAMES_TO_START, frameTimes.size)
                while (cachedCount < startAfter) delay(FRAME_LOAD_POLL_MS)
                binding.radarPlayButton.setImageResource(R.drawable.ic_pause)
                binding.radarPlayButton.contentDescription = context.getString(R.string.radar_pause_animation)
                playFrames(frameTimes, cached)
            }
        }
    }

    /** Loops forever over the cached frames in time order (skipping ones still downloading),
     *  double-buffered so a frame never flashes blank: the next frame loads into the hidden
     *  overlay while the current one stays on screen, then the two swap. */
    private suspend fun playFrames(frameTimes: List<Date>, cached: BooleanArray) {
        var shown = radarOverlay to radarTileProvider
        var hidden = frameOverlay to frameTileProvider
        var index = -1
        var lastSwap = 0L
        var passStart = SystemClock.uptimeMillis()
        var passFrames = 0
        var passLoadMs = 0L
        while (true) {
            val next = nextCachedFrame(cached, index)
            if (next <= index) {
                // Wrapped around: one full pass through the loop.
                val elapsed = SystemClock.uptimeMillis() - passStart
                Log.i(TAG, "playFrames: pass of $passFrames frames in ${elapsed}ms " +
                    "(${elapsed / passFrames.coerceAtLeast(1)}ms/frame, load avg ${passLoadMs / passFrames.coerceAtLeast(1)}ms, " +
                    "target ${ANIMATION_FRAME_DELAY_MS}ms)")
                passStart = SystemClock.uptimeMillis()
                passFrames = 0
                passLoadMs = 0L
            }
            index = next
            val time = frameTimes[index]
            val loadStart = SystemClock.uptimeMillis()
            loadFrameInto(hidden.first, hidden.second, historyTileSource(time))
            passLoadMs += SystemClock.uptimeMillis() - loadStart
            passFrames++
            val wait = ANIMATION_FRAME_DELAY_MS - (SystemClock.uptimeMillis() - lastSwap)
            if (wait > 0) delay(wait)
            hidden.first.isEnabled = true
            shown.first.isEnabled = false
            binding.radarMapView.invalidate()
            lastSwap = SystemClock.uptimeMillis()
            if (!playbackRunning) Log.i(TAG, "playFrames: first frame on screen, prefetch at ${prefetchPercent ?: 100}%")
            playbackRunning = true
            // Log.d(TAG, "playFrames: showing ${frameTimestampFormat.format(time)}")
            binding.radarUpdatedText.text = prefetchPercent?.let {
                context.getString(R.string.radar_frame_loading, updatedAtFormat.format(time), it)
            } ?: updatedAtFormat.format(time)
            shown = hidden.also { hidden = shown }
        }
    }

    /** The next cached frame after [after], wrapping around; at least one is always cached. */
    private fun nextCachedFrame(cached: BooleanArray, after: Int): Int {
        for (step in 1..cached.size) {
            val i = (after + step).mod(cached.size)
            if (cached[i]) return i
        }
        error("no cached frame")
    }

    /** Points the hidden [overlay]'s [provider] at [source] and has it load every tile in view
     *  into its memory cache without drawing anything: TilesOverlay.drawTiles with a null canvas
     *  only requests tiles (osmdroid's own "download, don't display" path), and
     *  protectDisplayedTilesForCache stops them being evicted before they're shown. Returns
     *  once every tile is in memory, or after [FRAME_LOAD_TIMEOUT_MS] with whatever arrived. */
    private suspend fun loadFrameInto(overlay: TilesOverlay, provider: MapTileProviderBasic, source: OnlineTileSourceBase): Boolean {
        provider.tileSource = source
        provider.clearTileCache()
        val viewPort = RectL()
        val start = SystemClock.uptimeMillis()
        while (true) {
            val projection = binding.radarMapView.projection
            projection.getMercatorViewPort(viewPort)
            overlay.protectDisplayedTilesForCache(null, projection)
            overlay.drawTiles(null, projection, projection.zoomLevel, viewPort)
            val states = overlay.tileStates
            if (states.notFound == 0) return true
            if (SystemClock.uptimeMillis() - start >= FRAME_LOAD_TIMEOUT_MS) {
                Log.w(TAG, "loadFrameInto: ${source.name()} still missing ${states.notFound}/${states.total} tiles " +
                    "after ${FRAME_LOAD_TIMEOUT_MS}ms - showing it anyway")
                return false
            }
            delay(FRAME_LOAD_POLL_MS)
        }
    }

    /** Downloads every frame's visible tiles into osmdroid's on-disk cache before playback starts.
     *  Without this, each frame flip during [startAnimation]'s loop has to fetch its tiles from
     *  the network from scratch - osmdroid's in-memory tile cache is cleared on every tileSource
     *  swap and can only ever hold one frame's worth of tiles - so most frames rendered blank. */
    private suspend fun prefetchFrames(frameTimes: List<Date>, onFrameCached: (frame: Int) -> Unit) {
        val tileIndices = visibleTileIndices()
        if (tileIndices.isEmpty()) {
            Log.w(TAG, "prefetchFrames: no visible tile indices for current viewport/zoom - skipping prefetch")
            frameTimes.indices.forEach(onFrameCached)
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
        prefetchPercent = 0
        // Tiles still to download per frame; a frame becomes playable when its count hits 0.
        val remaining = frameTimes.map { AtomicInteger(tileIndices.size) }
        tileTiming.report("before prefetchFrames (live/background loads)")
        val prefetchStart = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            // Newest first, so playback can start on the latest frames within a second or two.
            // The semaphore is fair (FIFO), so frames finish roughly in this order.
            frameTimes.indices.reversed().flatMap { frame ->
                val source = historyTileSource(frameTimes[frame])
                tileIndices.map { tileIndex ->
                    async {
                        semaphore.withPermit {
                            val start = System.nanoTime()
                            val drawable = try {
                                tileDownloader.downloadTile(tileIndex, writer, source)
                            } catch (e: CantContinueException) {
                                // Log.w(TAG, "prefetchFrames: gave up on tile $tileIndex for ${source.name()}", e)
                                null
                            }
                            tileTiming.recordDownload(System.nanoTime() - start)
                            if (drawable == null) failed.incrementAndGet()
                        }
                        val done = completed.incrementAndGet()
                        val frameDone = remaining[frame].decrementAndGet() == 0
                        if (frameDone || done == total || done % PREFETCH_PROGRESS_STEP == 0) {
                            withContext(Dispatchers.Main) {
                                if (frameDone) onFrameCached(frame)
                                prefetchPercent = done * 100 / total
                                // Once playback is running, playFrames shows the % beside the frame time.
                                if (!playbackRunning) {
                                    binding.radarUpdatedText.text = context.getString(
                                        R.string.radar_loading_animation_progress, done * 100 / total
                                    )
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        prefetchPercent = null
        Log.i(TAG, "prefetchFrames: done, ${total - failed.get()}/$total tiles cached, ${failed.get()} failed")
        tileTiming.report("prefetchFrames $total tiles x$PREFETCH_CONCURRENCY", System.currentTimeMillis() - prefetchStart)
        // Once per animation start, after the prefetch so it doesn't compete with it.
        val probeUrls = tileIndices.take(CONNECTION_PROBE_TILES).map { sampleSource.getTileURLString(it) }
        lifecycleScope.launch(Dispatchers.IO) { probeConnectionReuse(probeUrls, context.packageName) }
    }

    /** Tile indices (z/x/y, encoded) that osmdroid's TilesOverlay will draw for the current
     *  viewport, computed exactly the way its TileLooper does (mercator viewport ->
     *  TileSystem.getTileFromMercator at the map zoom), so a prefetch covers the drawn tiles with
     *  no padding. For layers whose [RadarLayer.maxZoom] is below the map zoom it returns the
     *  maxZoom parents of those tiles instead - the overlay scales those up. */
    private fun visibleTileIndices(): List<Long> {
        val projection = binding.radarMapView.projection
        val viewPort = projection.getMercatorViewPort(RectL())
        val mapZoom = projection.zoomLevel
        val drawRect = Rect()
        TileSystem.getTileFromMercator(viewPort, TileSystem.getTileSize(mapZoom), drawRect)
        val drawZoom = TileSystem.getInputTileZoomLevel(mapZoom)
        val zoom = drawZoom.coerceAtMost(layer.maxZoom)
        val shift = drawZoom - zoom
        val tileUpperBound = 1 shl zoom
        val tiles = LinkedHashSet<Long>()
        for (x in drawRect.left..drawRect.right) {
            for (y in drawRect.top..drawRect.bottom) {
                // Arithmetic shift floors negative (wrapped) indices too.
                val zx = x shr shift
                val zy = y shr shift
                if (zy < 0 || zy >= tileUpperBound) continue
                tiles.add(MapTileIndex.getTileIndex(zoom, zx.mod(tileUpperBound), zy))
            }
        }
        Log.i(TAG, "visibleTileIndices: mapZoom=$mapZoom drawRect=$drawRect -> ${tiles.size} z$zoom tiles")
        return tiles.toList()
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

    private fun showLiveFrame() {
        val source = liveTileSource()
        Log.i(TAG, "showLiveFrame: layer=$layer source=${source.name()} maxZoom=${source.maximumZoomLevel}")
        radarTileProvider.tileSource = source
        radarTileProvider.clearTileCache()
        binding.radarMapView.invalidate()
        binding.radarUpdatedText.text = context.getString(
            when (layer) {
                RadarLayer.RAIN_72H -> R.string.radar_rain_72h_updated
                RadarLayer.RAIN_FORECAST_72H -> R.string.radar_rain_forecast_72h_updated
                else -> R.string.radar_updated
            },
            updatedAtFormat.format(Date())
        )
        // Requests still queued from the previous source/layer (often at a zoom the new source
        // doesn't have) fail after the swap, and osmdroid neither retries them nor redraws on
        // failure - SimpleInvalidationHandler only reacts to success - so those tiles stay blank
        // until a pan/zoom forces a redraw. Redraw a couple of times to re-request them.
        RETRY_REDRAW_DELAYS_MS.forEach { delayMs ->
            binding.radarMapView.postDelayed({ binding.radarMapView.invalidate() }, delayMs)
        }
        prefetchOverzoomParents(source)
        scheduleAutoRefresh()
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
                        val start = System.nanoTime()
                        try {
                            tileDownloader.downloadTile(tile, writer, source)
                        } catch (e: CantContinueException) {
                            // Log.w(TAG, "prefetchOverzoomParents: gave up on tile $tile", e)
                        }
                        tileTiming.recordDownload(System.nanoTime() - start)
                    }
                }.awaitAll()
            }
            tileTiming.report("prefetchOverzoomParents ${tiles.size} tiles")
            Log.i(TAG, "prefetchOverzoomParents: cached ${tiles.size} z${layer.maxZoom} tiles for map zoom $mapZoom")
            binding.radarMapView.invalidate()
        }
    }

    private fun liveTileSource(): OnlineTileSourceBase = when (layer) {
        RadarLayer.RADAR -> XYTileSource("IEM-NEXRAD", 0, 18, 256, ".png", arrayOf(LIVE_TILE_URL), null, EarlyExpiryTilePolicy())
        RadarLayer.RAIN_72H -> XYTileSource(
            "IEM-Q2-72H", 0, RAIN_72H_MAX_ZOOM, 256, ".png", arrayOf(RAIN_72H_TILE_URL), null, EarlyExpiryTilePolicy()
        )
        RadarLayer.RAIN_FORECAST_72H -> WpcQpfTileSource("WPC-QPF-72H-${frameTimestampFormat.format(Date()).take(10)}")
        // The newest frame that should exist by now, named by its own timestamp so each new
        // 10-minute frame gets a fresh disk-cache key rather than depending on cache expiry.
        else -> historyTileSource(buildFrameTimestamps().last())
    }

    private fun historyTileSource(time: Date): OnlineTileSourceBase {
        val stamp = frameTimestampFormat.format(time)
        if (layer.isSatellite) {
            return GibsTileSource("GIBS-${layer.name}-$stamp", layer, gibsTimeFormat.format(time))
        }
        return XYTileSource(
            "IEM-NEXRAD-HISTORY-$stamp", 0, 18, 256, ".png",
            arrayOf("$HISTORY_TILE_URL_PREFIX$stamp/")
        )
    }
}
