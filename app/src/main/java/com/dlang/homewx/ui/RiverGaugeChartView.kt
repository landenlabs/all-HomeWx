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
 * [SensorHistoryChartView]'s Temp/Humidity layout, with Level/Flow instead. A gauge that only
 * reports one of the two parameters just plots a single line; the other axis stays present but
 * empty (harmless - [LineChartSetup.renderDualAxis] already skips a series with fewer than 2 points).
 */
class RiverGaugeChartView(private val context: Context) {

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

        val chartFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        // Watermark added first so it sits behind the chart, same as SensorHistoryChartView.
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
            addView(legendSwatch(R.color.accent_cool))
            addView(legendLabel("Level"))
            addView(legendSwatch(R.color.accent_purple))
            addView(legendLabel("Flow"))
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

    companion object {
        private const val WATERMARK_ALPHA = 204
    }
}
