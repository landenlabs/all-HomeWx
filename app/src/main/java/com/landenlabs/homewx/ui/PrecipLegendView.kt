package com.landenlabs.homewx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

// Server palettes for [PrecipLegend], file-level because an enum's entries initialize before
// its companion object.
/** pyiem.mrms.make_colorramp(): blue -> cyan -> green -> yellow -> red -> magenta -> white-ish. */
private val iemRamp: IntArray = IntArray(256).also { ramp ->
    for (i in 0 until 256) {
        val b = when (i) {
            in 0..36 -> 255
            in 37..76 -> (77 - i) * 6
            in 160..195 -> (i - 160) * 6
            in 196..255 -> 254
            else -> 0
        }
        val g = when (i) {
            in 0..36 -> i * 6
            in 37..115 -> 254
            in 116..155 -> (156 - i) * 6
            in 196..255 -> (i - 196) * 4
            else -> 0
        }
        val r = when (i) {
            in 77..116 -> (i - 77) * 6
            in 117..255 -> 254
            else -> 0
        }
        ramp[i] = Color.rgb(r, g, b)
    }
}

/** Inverse of make_mrms_rasters.convert_to_image, in palette-index space. */
private fun iemIndexForInches(inches: Double): Float {
    val mm = inches * 25.4
    return when {
        mm < 25 -> mm / 0.25
        mm < 125 -> 100 + (mm - 25) / 1.25
        mm < 500 -> 180 + (mm - 125) / 5
        else -> 254.0
    }.toFloat().coerceIn(1f, 254f)
}

/** WPC QPF band lower bound (inches) to fill color, low to high. */
private val wpcBands: List<Pair<Double, Int>> = listOf(
    0.01 to Color.rgb(127, 255, 0),
    0.10 to Color.rgb(0, 255, 0),
    0.25 to Color.rgb(8, 139, 0),
    0.50 to Color.rgb(16, 78, 139),
    0.75 to Color.rgb(30, 144, 255),
    1.00 to Color.rgb(0, 178, 238),
    1.25 to Color.rgb(0, 238, 238),
    1.50 to Color.rgb(137, 104, 205),
    1.75 to Color.rgb(145, 44, 238),
    2.00 to Color.rgb(139, 0, 139),
    2.50 to Color.rgb(139, 0, 0),
    3.00 to Color.rgb(255, 0, 0),
    4.00 to Color.rgb(238, 64, 0),
    5.00 to Color.rgb(255, 127, 0),
    7.00 to Color.rgb(206, 133, 0),
    10.0 to Color.rgb(255, 215, 0),
    15.0 to Color.rgb(255, 255, 0),
    20.0 to Color.rgb(255, 192, 183),
)

private fun formatInches(inches: Double): String =
    if (inches >= 1 && inches % 1.0 == 0.0) inches.toInt().toString()
    else inches.toString().removePrefix("0")

/** Color scale for one of the radar tab's 72-hour rainfall layers, matching what the tile server
 *  paints. [colorAt] maps a 0..1 position along the bar to a color; [labels] are the inch values
 *  to mark, each with its 0..1 position. */
enum class PrecipLegend {

    /** IEM "q2-p72h" (MRMS Q3 72-hour total). The tiles are pre-rendered 8-bit paletted PNGs:
     *  akrherz/iem scripts/mrms/make_mrms_rasters.py encodes millimeters into palette indices
     *  piecewise (0-25 mm in 0.25 mm steps -> 0..99, 25-125 mm in 1.25 mm -> 100..179,
     *  125-500 mm in 5 mm -> 180..254) and colors them with pyiem.mrms.make_colorramp(). Index 0
     *  is OFFSITE (transparent) in IEM's mapfile, so the bar runs 1..254 - the same layout as
     *  IEM's own legend image, mesonet.agron.iastate.edu/images/mrms_q3_p24h.png. */
    RAIN_72H {
        override fun colorAt(fraction: Float): Int =
            iemRamp[(1 + fraction * 253).roundToInt().coerceIn(1, 254)]

        override val labels: List<Pair<String, Float>> =
            listOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0).map { inches ->
                formatInches(inches) to ((iemIndexForInches(inches) - 1) / 253f)
            }
    },

    /** NOAA WPC QPF 72-hour total (MapServer layer 9). A uniqueValue renderer on field "qpf":
     *  each contour band is filled with its lower bound's color, read from
     *  mapservices.weather.noaa.gov/.../wpc_qpf/MapServer/9?f=json. The legend leaves out
     *  the white 0 class and shows the 18 bands 0.01..20 in, equal width each. */
    RAIN_FORECAST_72H {
        override fun colorAt(fraction: Float): Int =
            wpcBands[(fraction * wpcBands.size).toInt().coerceIn(0, wpcBands.size - 1)].second

        override val labels: List<Pair<String, Float>> =
            listOf(0.1, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0).map { inches ->
                formatInches(inches) to (wpcBands.indexOfFirst { it.first == inches } / wpcBands.size.toFloat())
            }
    };

    abstract fun colorAt(fraction: Float): Int
    abstract val labels: List<Pair<String, Float>>
}

/** Thin horizontal color bar for the radar tab's rainfall layers, spanning the map's width
 *  above the layer picker. Labels (inches) are drawn inside the bar in small outlined text so it
 *  can stay one strip tall. Set [legend] to null to clear it. */
class PrecipLegendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var legend: PrecipLegend? = null
        set(value) {
            field = value
            invalidate()
        }

    private val barPaint = Paint().apply { style = Paint.Style.FILL }
    private val tickPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        isFakeBoldText = true
        setShadowLayer(dp(1.5f), 0f, 0f, Color.BLACK)
    }

    init {
        // Shadow layers on text need a software layer on older API levels.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val legend = legend ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return

        // One column per pixel: the IEM ramp is continuous and the WPC bands are wide, so either
        // way this reproduces the server's color at every x.
        var x = 0
        while (x < width) {
            barPaint.color = legend.colorAt(x / w)
            canvas.drawRect(x.toFloat(), 0f, x + 1f, h, barPaint)
            x++
        }

        val baseline = (h - textPaint.descent() - textPaint.ascent()) / 2
        val pad = dp(2f)
        legend.labels.forEachIndexed { i, (value, fraction) ->
            // Units ride on the last label rather than a separate "in" that would collide with it.
            val label = if (i == legend.labels.lastIndex) "$value in" else value
            val tx = fraction * w
            canvas.drawLine(tx, 0f, tx, h, tickPaint)
            val textW = textPaint.measureText(label)
            canvas.drawText(label, (tx + pad).coerceAtMost(w - textW - pad), baseline, textPaint)
        }
    }
}
