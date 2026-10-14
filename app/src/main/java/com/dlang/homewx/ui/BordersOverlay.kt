package com.dlang.homewx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

private const val TAG = "BordersOverlay"
private const val ASSET_NAME = "map_borders.json"

/** One polyline as flat lon,lat pairs plus its bounding box, so off-screen lines are skipped
 *  without projecting any of their points. */
private class BorderLine(val coords: FloatArray) {
    var minLon = Float.MAX_VALUE
    var maxLon = -Float.MAX_VALUE
    var minLat = Float.MAX_VALUE
    var maxLat = -Float.MAX_VALUE

    init {
        for (i in coords.indices step 2) {
            minLon = minOf(minLon, coords[i]); maxLon = maxOf(maxLon, coords[i])
            minLat = minOf(minLat, coords[i + 1]); maxLat = maxOf(maxLat, coords[i + 1])
        }
    }
}

/** State/province and country boundary lines (Natural Earth 50m, bundled in
 *  assets/map_borders.json) drawn over a map. Used on top of the opaque-ish satellite layers,
 *  which hide the base map's own borders. Call [load] once off the main thread. */
class BordersOverlay(density: Float) : Overlay() {
    @Volatile private var states: List<BorderLine> = emptyList()
    @Volatile private var countries: List<BorderLine> = emptyList()

    // Bright amber stays legible over both white clouds and dark IR imagery.
    private val statePaint = linePaint(density * 1f, Color.argb(200, 255, 213, 79))
    private val countryPaint = linePaint(density * 1.8f, Color.argb(235, 255, 193, 7))
    private val path = Path()
    private val point = Point()
    private val geoPoint = GeoPoint(0.0, 0.0)

    private fun linePaint(width: Float, argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = argb
    }

    /** Parses the bundled asset. Blocking - call from a background thread. */
    fun load(context: Context) {
        try {
            val json = JSONObject(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
            states = parse(json.getJSONArray("states"))
            countries = parse(json.getJSONArray("countries"))
            Log.i(TAG, "load: ${states.size} state lines, ${countries.size} country lines")
        } catch (e: Exception) {
            Log.e(TAG, "load: failed to read $ASSET_NAME", e)
        }
    }

    private fun parse(array: JSONArray): List<BorderLine> = List(array.length()) { i ->
        val line = array.getJSONArray(i)
        BorderLine(FloatArray(line.length()) { j -> line.getDouble(j).toFloat() })
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val box = projection.boundingBox
        drawLines(canvas, projection, states, statePaint, box.lonWest, box.lonEast, box.latSouth, box.latNorth)
        drawLines(canvas, projection, countries, countryPaint, box.lonWest, box.lonEast, box.latSouth, box.latNorth)
    }

    private fun drawLines(
        canvas: Canvas, projection: Projection, lines: List<BorderLine>, paint: Paint,
        west: Double, east: Double, south: Double, north: Double
    ) {
        for (line in lines) {
            if (line.maxLon < west || line.minLon > east || line.maxLat < south || line.minLat > north) continue
            path.rewind()
            val c = line.coords
            for (i in c.indices step 2) {
                geoPoint.setCoords(c[i + 1].toDouble(), c[i].toDouble())
                projection.toPixels(geoPoint, point)
                if (i == 0) path.moveTo(point.x.toFloat(), point.y.toFloat())
                else path.lineTo(point.x.toFloat(), point.y.toFloat())
            }
            canvas.drawPath(path, paint)
        }
    }
}
