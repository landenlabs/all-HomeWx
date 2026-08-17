package com.dlang.homewx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal single-line time-series chart - no axes library, just enough for a sensor trend. */
class StripChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<Pair<Long, Double>> = emptyList()

    private val linePaint = Paint().apply {
        color = Color.parseColor("#FDB813")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1E2A38")
        strokeWidth = 2f
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#9AA7B4")
        textSize = 28f
        isAntiAlias = true
    }
    private val hourFormat = SimpleDateFormat("h a", Locale.getDefault())

    fun setData(newPoints: List<Pair<Long, Double>>) {
        points = newPoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) {
            canvas.drawText("Not enough history yet", 8f, height / 2f, labelPaint)
            return
        }

        val minValue = points.minOf { it.second }
        val maxValue = points.maxOf { it.second }
        val isFlat = maxValue == minValue
        val valueRange = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val minTime = points.first().first
        val maxTime = points.last().first
        val timeRange = (maxTime - minTime).takeIf { it > 0L } ?: 1L

        val leftPad = 60f
        val bottomPad = 40f
        val topPad = 30f
        val chartWidth = width - leftPad
        val chartHeight = height - bottomPad - topPad

        canvas.drawLine(leftPad, topPad, width.toFloat(), topPad, gridPaint)
        canvas.drawLine(leftPad, topPad + chartHeight, width.toFloat(), topPad + chartHeight, gridPaint)
        canvas.drawText("%.0f°".format(maxValue), 4f, topPad + 10f, labelPaint)
        canvas.drawText("%.0f°".format(minValue), 4f, topPad + chartHeight, labelPaint)

        var prevX = 0f
        var prevY = 0f
        points.forEachIndexed { index, (t, v) ->
            val x = leftPad + chartWidth * (t - minTime).toFloat() / timeRange
            val y = if (isFlat) {
                topPad + chartHeight / 2f
            } else {
                topPad + chartHeight * (1f - ((v - minValue) / valueRange).toFloat())
            }
            if (index > 0) canvas.drawLine(prevX, prevY, x, y, linePaint)
            prevX = x
            prevY = y
        }

        canvas.drawText(hourFormat.format(Date(minTime)), leftPad, height.toFloat(), labelPaint)
        val endLabel = hourFormat.format(Date(maxTime))
        canvas.drawText(endLabel, width - labelPaint.measureText(endLabel) - 4f, height.toFloat(), labelPaint)
    }
}
