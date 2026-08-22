package com.dlang.homewx.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dlang.homewx.databinding.PanelRadarBinding
import com.dlang.homewx.weather.HomeLocation
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOME_ZOOM = 7.0

/** NEXRAD composite reflectivity tiles (free, no API key) from the Iowa Environmental Mesonet,
 *  drawn as an overlay on top of the standard OpenStreetMap base layer. Inflates itself into
 *  [container]. Tiles are cached to disk by osmdroid with a short expiry, so [refresh] just
 *  needs to invalidate the map to pick up whatever the server currently has. */
class RadarPanel(container: ViewGroup) {

    private val context = container.context
    private val binding = PanelRadarBinding.inflate(LayoutInflater.from(context), container, false)
    val root: View get() = binding.root

    private val updatedAtFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val radarTileProvider: MapTileProviderBasic

    init {
        Configuration.getInstance().userAgentValue = context.packageName

        val radarTileSource = XYTileSource(
            "IEM-NEXRAD",
            0, 18, 256, ".png",
            arrayOf("https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/")
        )
        radarTileProvider = MapTileProviderBasic(context, radarTileSource)
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

        container.addView(root)
    }

    /** Clears the cached radar tiles and redraws, so reopening this tab shows the latest sweep. */
    fun refresh() {
        radarTileProvider.clearTileCache()
        binding.radarMapView.invalidate()
        binding.radarUpdatedText.text = "Updated ${updatedAtFormat.format(Date())}"
    }
}
