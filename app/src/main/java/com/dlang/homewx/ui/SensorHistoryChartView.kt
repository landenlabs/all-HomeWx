package com.dlang.homewx.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.dlang.homewx.R
import com.dlang.homewx.settings.AppSettings
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One sensor's temperature+humidity history chart: a room-name watermark behind the plot,
 * dual-axis temp (left, yellow) / humidity (right, blue) lines with green day dividers, and a
 * legend row below standing in for MPAndroidChart's own legend - a Temp/Humidity color key at
 * the start (highest priority, never shrinks), the sensor's current values centered in the row
 * (also never shrinks), and the room name at the end in white, which is the one that gives up
 * space and ellipsizes if the row is too narrow for all three.
 *
 * Shared by [SensorGraphsPanel] (one instance per currently-visible sensor) and
 * [SensorChartPanel] (one instance, for whichever sensor row was tapped on the weather panel).
 * [view] has no layout params set - callers size/margin it for their own parent.
 */
class SensorHistoryChartView(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val xAxisValueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = hourOnlyFormat.format(Date(value.toLong() * 1000L))
    }

    private val watermark = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        textSize = 30f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_secondary), WATERMARK_ALPHA))
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private val chart = LineChart(context).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private val valuesText: TextView
    private val nameText: TextView
    val view: View

    init {
        LineChartSetup.configure(chart, context, description = null, xAxisValueFormatter)
        chart.axisLeft.apply {
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${value.toInt()}°"
            }
            textColor = ContextCompat.getColor(context, R.color.accent_warm)
        }
        LineChartSetup.enableRightAxis(
            chart,
            textColor = ContextCompat.getColor(context, R.color.accent_cool),
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${value.toInt()}%"
            }
        )
        LineChartSetup.setThresholdLines(chart, context, listOf(100f to R.color.white, 32f to R.color.blue2))
        // The legend row built below replaces MPAndroidChart's own built-in legend with
        // Temp/Humidity key + current values + room name, so the chart's legend stays disabled
        // here (LineChartSetup.configure already turns it off by default).

        val chartFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        // Watermark added first so it sits behind the chart, which has a transparent
        // background and shows the watermark through its own empty space.
        chartFrame.addView(watermark)
        chartFrame.addView(chart)

        val (legendRow, builtValuesText, builtNameText) = buildLegendRow()
        valuesText = builtValuesText
        nameText = builtNameText

        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(chartFrame)
            addView(legendRow)
        }
    }

    private fun buildLegendRow(): Triple<View, TextView, TextView> {
        val row = ConstraintLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            }
        }

        fun legendSwatch(colorRes: Int): View {
            val sizePx = (10 * density).toInt()
            return View(context).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = (4 * density).toInt() }
                setBackgroundColor(ContextCompat.getColor(context, colorRes))
            }
        }

        fun legendLabel(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, (12 * density).toInt(), 0)
        }

        val legend = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(legendSwatch(R.color.accent_warm))
            addView(legendLabel("Temp"))
            addView(legendSwatch(R.color.accent_cool))
            addView(legendLabel("Humidity"))
            layoutParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }

        val valuesText = TextView(context).apply {
            id = View.generateViewId()
            textSize = 13f
            layoutParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }

        val nameText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            layoutParams = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                startToEnd = valuesText.id
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                marginStart = (8 * density).toInt()
            }
        }

        row.addView(legend)
        row.addView(valuesText)
        row.addView(nameText)
        return Triple(row, valuesText, nameText)
    }

    /** Cheap to call often (e.g. every state tick) - updates the watermark, the legend row's
     *  room name, and its current-values text, all independent of the (async-loaded) history. */
    fun setRoomNameAndCurrentValues(roomName: String, tempF: Double?, humidityPct: Double?) {
        watermark.text = roomName
        nameText.text = roomName

        val spans = SpannableStringBuilder()
        tempF?.let {
            spans.append("${it.roundToInt()}°", ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent_warm)), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        humidityPct?.let {
            if (spans.isNotEmpty()) spans.append("  ")
            spans.append("${it.roundToInt()}%", ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent_cool)), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        valuesText.text = spans
    }

    fun renderHistory(tempPoints: List<Pair<Long, Double>>, humidityPoints: List<Pair<Long, Double>>) {
        val allTimestamps = (tempPoints.map { it.first } + humidityPoints.map { it.first }).distinct().sorted()
        LineChartSetup.setLimitLines(chart, context, LineChartSetup.dayBoundaryXValues(allTimestamps))
        LineChartSetup.renderDualAxis(
            chart,
            context,
            leftSeries = smoothIfFlat(tempPoints),
            leftColorRes = R.color.accent_warm,
            leftLabel = "Temp",
            rightSeries = smoothIfFlat(humidityPoints),
            rightColorRes = R.color.accent_cool,
            rightLabel = "Humidity"
        )
    }

    /**
     * Noise in a sensor reading is only visually distracting when the surrounding data is
     * otherwise flat - a real trend spanning several units drowns it out on its own. So only
     * smooth a point when its own local window is flat; otherwise leave that point as-is. The
     * check is local (per point, over its own trailing window) rather than computed once over
     * the whole series, so one wider swing elsewhere in the 48h history can't disqualify
     * smoothing for an otherwise-calm stretch.
     *
     * The window is a trailing time span, not a fixed sample count - poll cadence on this app
     * varies a lot (as little as ~20s apart back-to-back, as much as ~80min apart in Quiet
     * mode), so a fixed count of samples covers wildly different amounts of real time depending
     * on when it lands. A time-based window smooths consistently regardless of polling cadence.
     * Both the window length and the flatness threshold are user-tunable (Settings > Display -
     * [AppSettings.getSmoothingWindowMinutes]/[AppSettings.getFlatRangeThreshold]).
     *
     * This alone doesn't make a near-flat series look calm on its own, though - see
     * [LineChartSetup.applyMinimumAxisSpan] for the other half of the fix (the y-axis itself
     * auto-scaling tightly around a small range is what actually made a smoothed-down Basement
     * humidity series - real range ~2 points, but with per-sample jitter of up to ~1 point,
     * comparable in size to the whole signal - still look wildly noisy).
     */
    private fun smoothIfFlat(points: List<Pair<Long, Double>>): List<Pair<Long, Double>> {
        if (points.size < 2) return points
        val windowMillis = AppSettings.getSmoothingWindowMinutes(context) * 60_000L
        val flatRangeThreshold = AppSettings.getFlatRangeThreshold(context)
        var windowStart = 0
        return points.mapIndexed { index, (timeMillis, value) ->
            while (points[windowStart].first < timeMillis - windowMillis) windowStart++
            val window = points.subList(windowStart, index + 1).map { it.second }
            val localRange = window.max() - window.min()
            val smoothedValue = if (localRange < flatRangeThreshold) window.average() else value
            timeMillis to smoothedValue
        }
    }

    companion object {
        /** 80% opacity - fully opaque read as too bold/distracting sitting behind the plotted
         *  lines, but ~29% (alpha 75) wasn't visible enough either. */
        private const val WATERMARK_ALPHA = 204
    }
}
