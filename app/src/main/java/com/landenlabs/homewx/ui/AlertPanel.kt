// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.landenlabs.homewx.R
import com.landenlabs.homewx.alerts.AlertSeverity
import com.landenlabs.homewx.alerts.WeatherAlert
import com.landenlabs.homewx.databinding.ItemWeatherAlertBinding
import com.landenlabs.homewx.databinding.PanelAlertsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@ColorRes
fun AlertSeverity.colorRes(): Int = when (this) {
    AlertSeverity.EXTREME -> R.color.alert_extreme
    AlertSeverity.SEVERE -> R.color.alert_severe
    AlertSeverity.MODERATE -> R.color.alert_moderate
    AlertSeverity.MINOR -> R.color.alert_minor
    AlertSeverity.UNKNOWN -> R.color.alert_unknown
}

/**
 * The flashing alert icon in the weather panel's top-start corner plus the alert list it opens
 * over the weather panel. Both views live in activity_main.xml (every layout variant); this class
 * only drives them. The icon is tinted by the most severe active alert and pulses slowly
 * ([FLASH_HALF_PERIOD_MS] fade out, same back in) for as long as it is visible.
 */
class AlertPanel(
    private val icon: ImageView,
    private val panel: PanelAlertsBinding,
    onIconClick: () -> Unit,
    onBack: () -> Unit
) {
    private val context: Context get() = icon.context
    private val adapter = AlertAdapter()
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var alerts: List<WeatherAlert> = emptyList()

    private val flash = ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, FLASH_MIN_ALPHA).apply {
        duration = FLASH_HALF_PERIOD_MS
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }

    val isOpen: Boolean get() = panel.root.visibility == View.VISIBLE

    init {
        icon.setOnClickListener { onIconClick() }
        panel.alertBackButton.setOnClickListener { onBack() }
        panel.alertRecyclerView.layoutManager = LinearLayoutManager(context)
        panel.alertRecyclerView.adapter = adapter
    }

    /** Shows/hides the icon for [alerts] (already filtered to active, most severe first) and
     *  refreshes the list if it's open. Cheap to call on every state tick. */
    fun bind(alerts: List<WeatherAlert>, updatedAtMillis: Long?, sourceLabel: String?) {
        val changed = alerts != this.alerts
        this.alerts = alerts
        if (alerts.isEmpty()) {
            flash.cancel()
            icon.visibility = View.GONE
            icon.alpha = 1f
        } else {
            val color = context.getColor(alerts.first().severity.colorRes())
            icon.imageTintList = ColorStateList.valueOf(color)
            icon.visibility = View.VISIBLE
            if (!flash.isStarted) flash.start()
        }
        val res = context.resources
        panel.alertPanelTitle.text = when (alerts.size) {
            0 -> res.getString(R.string.alert_panel_none)
            1 -> res.getString(R.string.alert_panel_title, 1)
            else -> res.getString(R.string.alert_panel_title_plural, alerts.size)
        }
        panel.alertPanelSource.text = res.getString(
            R.string.alert_panel_source,
            sourceLabel ?: "--",
            updatedAtMillis?.let { timeFormat.format(Date(it)) } ?: "--"
        )
        if (changed) adapter.submit(alerts)
    }

    fun open() {
        panel.root.visibility = View.VISIBLE
        panel.alertRecyclerView.scrollToPosition(0)
    }

    fun close() {
        panel.root.visibility = View.GONE
    }

    /** Stops the pulse while the activity is in the background; [resume] restarts it if needed. */
    fun pause() = flash.pause()

    fun resume() {
        if (icon.visibility == View.VISIBLE) {
            if (flash.isPaused) flash.resume() else if (!flash.isStarted) flash.start()
        }
    }

    private companion object {
        const val FLASH_HALF_PERIOD_MS = 1200L
        const val FLASH_MIN_ALPHA = 0.25f
    }
}

private class AlertAdapter : RecyclerView.Adapter<AlertAdapter.Holder>() {
    private var items: List<WeatherAlert> = emptyList()
    private val dateFormat = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())

    fun submit(newItems: List<WeatherAlert>) {
        items = newItems
        @Suppress("NotifyDataSetChanged") // a handful of items, replaced wholesale every ~5 min
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemWeatherAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val alert = items[position]
        val b = holder.binding
        val color = b.root.context.getColor(alert.severity.colorRes())
        b.alertSeverityStripe.setBackgroundColor(color)
        b.alertEventText.setTextColor(color)
        b.alertEventText.text = alert.event
        b.alertHeadlineText.setOrGone(alert.headline)

        val severityLine = listOfNotNull(
            alert.severity.takeIf { it != AlertSeverity.UNKNOWN }?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
            alert.urgency, alert.certainty
        ).filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }.joinToString(" · ")
        row(b.alertSeverityLabel, b.alertSeverityValue, severityLine)
        row(b.alertIssuedLabel, b.alertIssuedValue, alert.issuedMillis.formatted())
        // Effective is usually the same instant as issued - only worth a row when it differs.
        row(b.alertEffectiveLabel, b.alertEffectiveValue, alert.effectiveMillis.takeIf { it != alert.issuedMillis }.formatted())
        row(b.alertOnsetLabel, b.alertOnsetValue, alert.onsetMillis.takeIf { it != alert.effectiveMillis }.formatted())
        row(b.alertExpiresLabel, b.alertExpiresValue, alert.expiresMillis.formatted())
        row(b.alertEndsLabel, b.alertEndsValue, alert.endsMillis.takeIf { it != alert.expiresMillis }.formatted())
        row(b.alertAreaLabel, b.alertAreaValue, alert.areaDesc)
        row(b.alertSenderLabel, b.alertSenderValue, alert.senderName)

        b.alertDescriptionText.setOrGone(alert.description?.unwrapNwsText())
        val instruction = alert.instruction?.unwrapNwsText()
        b.alertInstructionText.setOrGone(instruction)
        b.alertInstructionLabel.visibility = b.alertInstructionText.visibility
    }

    private fun Long?.formatted(): String? = this?.let { dateFormat.format(Date(it)) }

    private fun row(label: TextView, value: TextView, text: String?) {
        value.setOrGone(text)
        label.visibility = value.visibility
    }

    private fun TextView.setOrGone(value: String?) {
        if (value.isNullOrBlank()) {
            visibility = View.GONE
        } else {
            text = value
            visibility = View.VISIBLE
        }
    }

    class Holder(val binding: ItemWeatherAlertBinding) : RecyclerView.ViewHolder(binding.root)
}

/** NWS text is hard-wrapped at ~70 columns for teletype. Re-joins wrapped lines within a
 *  paragraph so it reflows to the panel width, keeping blank-line paragraph breaks and
 *  the "* WHAT...", "* WHERE..." bullet lines. */
internal fun String.unwrapNwsText(): String =
    trim()
        .replace("\r\n", "\n")
        .split(Regex("\n\\s*\n"))
        .joinToString("\n\n") { paragraph ->
            paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .fold(StringBuilder()) { sb, line ->
                    if (sb.isNotEmpty()) sb.append(if (line.startsWith("*") || line.startsWith("-")) "\n" else " ")
                    sb.append(line)
                }.toString()
        }
