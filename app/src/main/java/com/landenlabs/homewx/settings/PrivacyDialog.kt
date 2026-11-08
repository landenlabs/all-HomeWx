package com.landenlabs.homewx.settings

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.landenlabs.homewx.R

/** Settings > Privacy: what data the app keeps, where it goes and how to clear it. The text
 *  ([R.string.privacy_html]) mirrors the README "Privacy" section - keep the two in sync. */
object PrivacyDialog {
    fun show(context: Context) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        // LEGACY mode separates <p> blocks with a blank line; COMPACT uses a single newline (no paragraph gap).
        val text = TextView(context).apply {
            text = HtmlCompat.fromHtml(context.getString(R.string.privacy_html), HtmlCompat.FROM_HTML_MODE_LEGACY).trim()
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setLinkTextColor(ContextCompat.getColor(context, R.color.accent_cool))
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_privacy_title)
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
