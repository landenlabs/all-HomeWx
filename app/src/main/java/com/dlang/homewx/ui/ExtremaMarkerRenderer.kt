package com.dlang.homewx.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.dlang.homewx.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.renderer.XAxisRenderer
import com.github.mikephil.charting.utils.MPPointD
import com.github.mikephil.charting.utils.Transformer
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * Draws a small upward-pointing triangle just under [chart]'s x-axis line at the x-position of
 * each visible axis group's maximum (yellow) and - for [minAxis] only - minimum (purple). The
 * triangle sits between the axis line and the tick labels; [install] bumps the x-axis's label
 * offset by the triangle's height so the labels move down instead of being covered.
 *
 * "Axis group" = every data set sharing an [YAxis.AxisDependency], so the daily high/low chart's
 * two series yield one max (the highest high) and one min (the lowest low), and a dual-axis
 * chart yields a max per axis. Only the visible x-range is considered, so zooming re-targets.
 */
class ExtremaMarkerRenderer(
    private val chart: LineChart,
    viewPortHandler: ViewPortHandler,
    xAxis: XAxis,
    transformer: Transformer,
    private val minAxis: YAxis.AxisDependency?
) : XAxisRenderer(viewPortHandler, xAxis, transformer) {

    private val density = chart.resources.displayMetrics.density
    private val sizePx = SIZE_DP * density
    private val maxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chart.context.getColor(R.color.accent_warm)
        style = Paint.Style.FILL
    }
    private val minPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chart.context.getColor(R.color.accent_purple)
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun renderAxisLabels(c: Canvas) {
        super.renderAxisLabels(c)
        val data = chart.data ?: return
        val lowX = chart.lowestVisibleX
        val highX = chart.highestVisibleX
        for (axis in YAxis.AxisDependency.values()) {
            var maxX = 0f; var maxY = Float.NEGATIVE_INFINITY
            var minX = 0f; var minY = Float.POSITIVE_INFINITY
            for (set in data.dataSets) {
                if (set.axisDependency != axis) continue
                for (i in 0 until set.entryCount) {
                    val e = set.getEntryForIndex(i)
                    if (e.x < lowX || e.x > highX) continue
                    if (e.y > maxY) { maxY = e.y; maxX = e.x }
                    if (e.y < minY) { minY = e.y; minX = e.x }
                }
            }
            if (maxY == Float.NEGATIVE_INFINITY || maxY == minY) continue
            drawTriangle(c, axis, maxX, maxPaint)
            if (axis == minAxis) drawTriangle(c, axis, minX, minPaint)
        }
    }

    private fun drawTriangle(c: Canvas, axis: YAxis.AxisDependency, x: Float, paint: Paint) {
        val px = chart.getTransformer(axis).getPixelForValues(x, 0f).let { p: MPPointD -> p.x.toFloat().also { MPPointD.recycleInstance(p) } }
        if (!mViewPortHandler.isInBoundsX(px)) return
        val top = mViewPortHandler.contentBottom() + 1f * density
        path.reset()
        path.moveTo(px, top)
        path.lineTo(px - sizePx / 2f, top + sizePx)
        path.lineTo(px + sizePx / 2f, top + sizePx)
        path.close()
        c.drawPath(path, paint)
    }

    companion object {
        private const val SIZE_DP = 7f

        /** Installs the renderer on [chart] and makes room for it under the axis line. */
        fun install(chart: LineChart, minAxis: YAxis.AxisDependency?) {
            // Default label offset is 5dp; XAxis.setYOffset takes dp.
            chart.xAxis.yOffset = 5f + SIZE_DP + 1f
            chart.setXAxisRenderer(
                ExtremaMarkerRenderer(chart, chart.viewPortHandler, chart.xAxis, chart.getTransformer(YAxis.AxisDependency.LEFT), minAxis)
            )
        }
    }
}
