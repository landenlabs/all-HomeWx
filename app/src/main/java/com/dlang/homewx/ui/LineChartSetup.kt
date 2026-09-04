package com.dlang.homewx.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.dlang.homewx.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Calendar

/** Shared axis/touch setup and data-binding for every [LineChart] in the info panel (sensor history + weather graphs). */
object LineChartSetup {

    /** Below this range (in the axis's own units), [applyMinimumAxisSpan] clamps the axis to at
     *  least this span instead of MPAndroidChart's normal tight auto-scale. */
    private const val MIN_AXIS_SPAN = 5.0

    /** x-values (seconds-since-epoch, matching this object's [Entry] convention) where one
     *  calendar day ends and the next begins, given a list of ascending millis timestamps -
     *  skips the very first entry, which isn't a "change". Shared by [ForecastGraphsPanel] and
     *  [SensorGraphsPanel] so both strip chart families draw day dividers the same way. */
    fun dayBoundaryXValues(timestampsMillis: List<Long>): List<Float> {
        val calendar = Calendar.getInstance()
        val boundaries = mutableListOf<Float>()
        var currentDayKey = -1
        timestampsMillis.forEachIndexed { index, millis ->
            calendar.timeInMillis = millis
            val dayKey = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
            if (dayKey != currentDayKey) {
                currentDayKey = dayKey
                if (index > 0) boundaries.add(millis / 1000f)
            }
        }
        return boundaries
    }

    fun configure(chart: LineChart, context: Context, description: String?, xAxisValueFormatter: ValueFormatter) {
        val axisTextColor = ContextCompat.getColor(context, R.color.text_secondary)
        val gridLineColor = ContextCompat.getColor(context, R.color.divider)

        chart.legend.isEnabled = false
        chart.setNoDataText(context.getString(R.string.strip_chart_no_data))
        chart.setNoDataTextColor(axisTextColor)
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        // Tap (or drag) shows a crosshair with the time/value under it - see ChartValueMarkerView.
        chart.setHighlightPerTapEnabled(true)
        chart.setHighlightPerDragEnabled(true)
        chart.marker = ChartValueMarkerView(context, chart)
        if (description != null) {
            // The description doubles as this chart's "which data source is this" label,
            // so it gets its own accent color rather than the neutral axis text color.
            chart.description.apply {
                isEnabled = true
                text = description
                textColor = ContextCompat.getColor(context, R.color.accent_cool)
                textSize = 12f
            }
        } else {
            chart.description.isEnabled = false
        }

        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            textColor = axisTextColor
            this.gridColor = gridLineColor
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = axisTextColor
            this.gridColor = gridLineColor
            valueFormatter = xAxisValueFormatter
        }
    }

    /** Draws a thin vertical marker (no label) at each x-value in [xValues] - e.g. the forecast
     *  graphs' hourly charts use this to mark where one calendar day ends and the next begins.
     *  Replaces whatever limit lines the chart had before, so pass an empty list to clear them. */
    fun setLimitLines(chart: LineChart, context: Context, xValues: List<Float>) {
        chart.xAxis.removeAllLimitLines()
        xValues.forEach { x ->
            chart.xAxis.addLimitLine(
                LimitLine(x).apply {
                    lineColor = ContextCompat.getColor(context, R.color.accent_day_marker)
                    lineWidth = 1f
                    enableDashedLine(6f, 4f, 0f)
                }
            )
        }
    }

    /** Draws fixed horizontal threshold lines on [axis] - e.g. wind speed's "high wind" line, or
     *  temperature's freezing line. Defaults to the left axis (every chart but the sensor
     *  graphs' dual-axis one is left-axis only); pass [chart]'s right axis for a series plotted
     *  there instead. Replaces whatever horizontal limit lines were on that axis, so pass an
     *  empty list to clear them. */
    fun setThresholdLines(chart: LineChart, context: Context, thresholds: List<Pair<Float, Int>>, axis: YAxis = chart.axisLeft) {
        axis.removeAllLimitLines()
        thresholds.forEach { (value, colorRes) ->
            axis.addLimitLine(
                LimitLine(value).apply {
                    lineColor = ContextCompat.getColor(context, colorRes)
                    lineWidth = 1.5f
                }
            )
        }
    }

    /** Adds an invisible limit line per (x, text) pair purely to get its text label drawn near
     *  the x-axis at that x-value - used instead of the axis's own auto-placed tick labels when
     *  we want labels at specific x-values (e.g. noon of each day) rather than wherever
     *  MPAndroidChart's "nice interval" grid computation happens to land. Adds to whatever limit
     *  lines are already on the chart (e.g. from [setLimitLines]) rather than clearing them, so
     *  call this after [setLimitLines], not before. */
    fun addAxisLabelMarkers(chart: LineChart, context: Context, labels: List<Pair<Float, String>>) {
        val axisTextColor = ContextCompat.getColor(context, R.color.text_secondary)
        labels.forEach { (x, text) ->
            chart.xAxis.addLimitLine(
                LimitLine(x, text).apply {
                    lineColor = Color.TRANSPARENT
                    textColor = axisTextColor
                    textSize = 12f
                    labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
                }
            )
        }
    }

    /** Every line graph in the app shares the same line color by default - [colorRes] is an
     *  override for the few that don't (e.g. precipitation chance, filled and light-blue to
     *  match the temperature graph's low-temp line). */
    fun render(
        chart: LineChart,
        context: Context,
        points: List<Pair<Long, Double>>,
        colorRes: Int = R.color.accent_warm,
        filled: Boolean = false,
        spline: Boolean = false
    ) {
        if (points.size < 2) {
            chart.clear()
            return
        }
        val entries = points.map { (timeMillis, value) -> Entry(timeMillis / 1000f, value.toFloat()) }
        val color = ContextCompat.getColor(context, colorRes)
        val dataSet = LineDataSet(entries, null).apply {
            this.color = color
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = if (spline) LineDataSet.Mode.CUBIC_BEZIER else LineDataSet.Mode.LINEAR
            if (filled) {
                setDrawFilled(true)
                fillColor = color
                fillAlpha = 100
            }
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    /** Enables the right y-axis with its own color/formatter - used only by the sensor graphs'
     *  combined temp+humidity chart; every other chart in the app stays left-axis only. */
    fun enableRightAxis(chart: LineChart, textColor: Int, valueFormatter: ValueFormatter) {
        chart.axisRight.apply {
            isEnabled = true
            this.textColor = textColor
            this.valueFormatter = valueFormatter
            setDrawGridLines(false) // avoid doubling up on axisLeft's grid lines
        }
    }

    /** Plots [leftSeries] against the left y-axis and [rightSeries] against the right y-axis on
     *  the same chart - the sensor graphs' temperature (left) + humidity (right) combo. Requires
     *  [enableRightAxis] to have been called on [chart] first. */
    fun renderDualAxis(
        chart: LineChart,
        context: Context,
        leftSeries: List<Pair<Long, Double>>,
        leftColorRes: Int,
        leftLabel: String,
        rightSeries: List<Pair<Long, Double>>,
        rightColorRes: Int,
        rightLabel: String
    ) {
        fun toDataSet(points: List<Pair<Long, Double>>, colorRes: Int, label: String, axis: YAxis.AxisDependency): LineDataSet? {
            if (points.size < 2) return null
            val entries = points.map { (timeMillis, value) -> Entry(timeMillis / 1000f, value.toFloat()) }
            return LineDataSet(entries, label).apply {
                color = ContextCompat.getColor(context, colorRes)
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
                axisDependency = axis
            }
        }

        val dataSets = listOfNotNull(
            toDataSet(leftSeries, leftColorRes, leftLabel, YAxis.AxisDependency.LEFT),
            toDataSet(rightSeries, rightColorRes, rightLabel, YAxis.AxisDependency.RIGHT)
        )
        if (dataSets.isEmpty()) {
            chart.clear()
            return
        }
        chart.data = LineData(dataSets)
        applyMinimumAxisSpan(chart.axisLeft, leftSeries.map { it.second })
        applyMinimumAxisSpan(chart.axisRight, rightSeries.map { it.second })
        chart.invalidate()
    }

    /**
     * MPAndroidChart auto-scales an axis tightly to its data's own min/max (plus a small ~10%
     * padding), which is fine for a series with real range, but makes a genuinely near-flat
     * series (e.g. indoor humidity holding steady within 2 percentage points) look dramatically
     * noisy - ordinary +/-0.2 sample jitter gets stretched to fill the entire chart height. This
     * clamps the axis to span at least [MIN_AXIS_SPAN] units, centered on the data, so a flat
     * series reads as flat instead of zoomed-in-on-itself. Only kicks in below that floor -
     * a series with a real, wider range still gets MPAndroidChart's normal auto-scaling.
     */
    private fun applyMinimumAxisSpan(axis: YAxis, values: List<Double>) {
        if (values.size < 2) return
        val dataMin = values.min()
        val dataMax = values.max()
        if (dataMax - dataMin >= MIN_AXIS_SPAN) {
            axis.resetAxisMinimum()
            axis.resetAxisMaximum()
            return
        }
        val center = (dataMin + dataMax) / 2.0
        axis.axisMinimum = (center - MIN_AXIS_SPAN / 2.0).toFloat()
        axis.axisMaximum = (center + MIN_AXIS_SPAN / 2.0).toFloat()
    }

    /** Like [render], but for the one chart in the app that plots two related series
     *  (daily high/low temperature) - each series gets its own color. */
    fun renderSeries(chart: LineChart, context: Context, series: List<Pair<List<Pair<Long, Double>>, Int>>, spline: Boolean = false) {
        val dataSets = series.mapNotNull { (points, colorRes) ->
            if (points.size < 2) return@mapNotNull null
            val entries = points.map { (timeMillis, value) -> Entry(timeMillis / 1000f, value.toFloat()) }
            LineDataSet(entries, null).apply {
                color = ContextCompat.getColor(context, colorRes)
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                mode = if (spline) LineDataSet.Mode.CUBIC_BEZIER else LineDataSet.Mode.LINEAR
            }
        }
        if (dataSets.isEmpty()) {
            chart.clear()
            return
        }
        chart.data = LineData(dataSets)
        chart.invalidate()
    }

    /** 80% opacity for a watermark sitting behind a plotted line - fully opaque reads as too
     *  bold/distracting, ~29% (alpha 75) wasn't visible enough. Shared by every "N stacked
     *  per-entity dual-axis history chart" card in the app. */
    private const val WATERMARK_ALPHA = 204

    /** Keeps a chart-card watermark's vertical center within the top third of the chart instead
     *  of dead center, where it competes with the y-axis auto-scaling and any "no history yet"
     *  empty-state message. */
    private const val WATERMARK_VERTICAL_BIAS = 0.15f

    /**
     * Builds the watermark-behind-chart frame shared by every "one card per entity" dual-axis
     * history chart in the app (indoor sensors' Temp/Humidity via [SensorHistoryChartView],
     * river gauges' Level/Flow via [RiverGaugeChartView]) - this is the one piece those two
     * classes had each built by hand, and the watermark's vertical position (top third, not
     * dead center) drifted out of sync between them as a result. Extracting it here means a
     * future change to this shell only needs to happen once. [chart] is added on top of (and
     * must already have a transparent background so the watermark shows through empty space).
     */
    fun buildWatermarkedChartFrame(context: Context, chart: LineChart, entityNameTextSizeSp: Float = 30f): Pair<FrameLayout, TextView> {
        val watermark = TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            textSize = entityNameTextSizeSp
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_secondary), WATERMARK_ALPHA))
        }
        val watermarkOverlay = ConstraintLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(
                watermark,
                ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    verticalBias = WATERMARK_VERTICAL_BIAS
                }
            )
        }
        val chartFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        // Watermark overlay added first so it sits behind the chart.
        chartFrame.addView(watermarkOverlay)
        chartFrame.addView(chart)
        return chartFrame to watermark
    }

    /**
     * Builds the legend row shared by every "one card per entity" dual-axis history chart card:
     * a color-key swatch+label per entry in [legendEntries] (highest priority, never shrinks),
     * the entity's current values centered in the row (also never shrinks), and the entity's
     * name at the end in white, which is the one that gives up space and ellipsizes if the row
     * is too narrow for all three.
     */
    fun buildLegendRow(context: Context, legendEntries: List<Pair<Int, String>>): Triple<View, TextView, TextView> {
        val density = context.resources.displayMetrics.density
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
            legendEntries.forEach { (colorRes, label) ->
                addView(legendSwatch(colorRes))
                addView(legendLabel(label))
            }
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
}
