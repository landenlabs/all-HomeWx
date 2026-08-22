package com.dlang.homewx.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import com.dlang.homewx.R
import com.dlang.homewx.databinding.PanelRadarBinding
import com.dlang.homewx.weather.HomeLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val HOME_ZOOM = 7.0
private const val LIVE_TILE_URL = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/"

// IEM's time-indexed national composite layer, distinct from the live-only layer above -
// each 5-minute UTC mark back to 2011 is addressable as ridge::USCOMP-N0Q-<yyyyMMddHHmm>.
private const val HISTORY_TILE_URL_PREFIX = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/ridge::USCOMP-N0Q-"
private const val ANIMATION_HISTORY_MINUTES = 120
private const val ANIMATION_STEP_MINUTES = 5
private const val ANIMATION_FRAME_DELAY_MS = 400L

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
    private var animationJob: Job? = null

    init {
        Configuration.getInstance().userAgentValue = context.packageName

        radarTileProvider = MapTileProviderBasic(context, liveTileSource())
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
        binding.radarPlayButton.setImageResource(R.drawable.ic_pause)
        binding.radarPlayButton.contentDescription = context.getString(R.string.radar_pause_animation)
        animationJob = lifecycleScope.launch {
            var index = 0
            while (true) {
                showHistoryFrame(frameTimes[index])
                index = (index + 1) % frameTimes.size
                delay(ANIMATION_FRAME_DELAY_MS)
            }
        }
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
        radarTileProvider.tileSource = historyTileSource(time)
        radarTileProvider.clearTileCache()
        binding.radarMapView.invalidate()
        binding.radarUpdatedText.text = updatedAtFormat.format(time)
    }

    private fun showLiveFrame() {
        radarTileProvider.tileSource = liveTileSource()
        radarTileProvider.clearTileCache()
        binding.radarMapView.invalidate()
        binding.radarUpdatedText.text = "Updated ${updatedAtFormat.format(Date())}"
    }

    private fun liveTileSource() = XYTileSource("IEM-NEXRAD", 0, 18, 256, ".png", arrayOf(LIVE_TILE_URL))

    private fun historyTileSource(time: Date) = XYTileSource(
        "IEM-NEXRAD-HISTORY", 0, 18, 256, ".png",
        arrayOf("$HISTORY_TILE_URL_PREFIX${frameTimestampFormat.format(time)}/")
    )
}
