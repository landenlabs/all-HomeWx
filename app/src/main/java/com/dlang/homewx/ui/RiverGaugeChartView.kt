package com.dlang.homewx.ui

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dlang.homewx.R
import com.dlang.homewx.rivers.GaugeReading
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One river gauge's level+flow history chart - a gauge-name watermark behind the plot, dual-
 * axis level (left, blue) / flow (right, purple) lines, and a legend row below - same shape as
 * [SensorHistoryChartView]'s Temp/Humidity layout, with Level/Flow instead. The watermark+chart
 * frame and legend row shells come from [LineChartSetup], shared with [SensorHistoryChartView]
 * so a change to that shell only needs to happen once. A gauge that only reports one of the two
 * parameters just plots a single line; the other axis stays present but empty (harmless -
 * [LineChartSetup.renderDualAxis] already skips a series with fewer than 2 points).
 */
class RiverGaugeChartView(private val context: Context) {

    private val hourOnlyFormat = SimpleDateFormat("h a", Locale.getDefault())
    private val xAxisValueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = hourOnlyFormat.format(Date(value.toLong() * 1000L))
    }

    private val chart = LineChart(context)
    private val watermark: TextView
    private val valuesText: TextView
    private val nameText: TextView
    val view: View

    init {
        LineChartSetup.configure(chart, context, description = null, xAxisValueFormatter)
        chart.axisLeft.apply {
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "%.1f ft".format(value)
            }
            textColor = ContextCompat.getColor(context, R.color.accent_cool)
        }
        LineChartSetup.enableRightAxis(
            chart,
            textColor = ContextCompat.getColor(context, R.color.accent_purple),
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${value.roundToInt()} cfs"
            }
        )

        val (chartFrame, builtWatermark) = LineChartSetup.buildWatermarkedChartFrame(context, chart)
        watermark = builtWatermark

        val (legendRow, builtValuesText, builtNameText) = LineChartSetup.buildLegendRow(
            context,
            listOf(R.color.accent_cool to "Level", R.color.accent_purple to "Flow")
        )
        valuesText = builtValuesText
        nameText = builtNameText

        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(chartFrame)
            addView(legendRow)
        }
    }

    /** Cheap to call often (e.g. every state tick) - updates the watermark, the legend row's
     *  gauge name, and its current-values text, all independent of the (async-loaded) history. */
    fun setGaugeNameAndCurrentValues(gaugeName: String, latest: GaugeReading?) {
        watermark.text = gaugeName
        nameText.text = gaugeName

        val spans = SpannableStringBuilder()
        latest?.gageHeightFt?.let {
            spans.append("%.1f ft".format(it), ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent_cool)), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        latest?.dischargeCfs?.let {
            if (spans.isNotEmpty()) spans.append("  ")
            spans.append("${it.roundToInt()} cfs", ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent_purple)), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        valuesText.text = spans
    }

    fun renderHistory(levelPoints: List<Pair<Long, Double>>, flowPoints: List<Pair<Long, Double>>) {
        val allTimestamps = (levelPoints.map { it.first } + flowPoints.map { it.first }).distinct().sorted()
        LineChartSetup.setLimitLines(chart, context, LineChartSetup.dayBoundaryXValues(allTimestamps))
        LineChartSetup.renderDualAxis(
            chart,
            context,
            leftSeries = levelPoints,
            leftColorRes = R.color.accent_cool,
            leftLabel = "Level",
            rightSeries = flowPoints,
            rightColorRes = R.color.accent_purple,
            rightLabel = "Flow"
        )
    }
}
