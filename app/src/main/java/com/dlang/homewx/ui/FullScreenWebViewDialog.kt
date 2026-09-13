package com.dlang.homewx.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import com.dlang.homewx.R

/** Full-screen [Dialog] that temporarily re-parents an already-configured WebView + its error
 *  TextView out of whichever half-screen tab container currently owns them, then returns both to
 *  [originalParent] on dismiss - the WebView is only moved, never recreated/destroyed, so its
 *  navigation history/session survives the round trip. Takes already-wired views rather than
 *  building its own, so the same dialog can host Drought/Stocks the same way later without
 *  duplicating their WebView/client setup - see [NewsPanel.showWyze] for the current caller. */
class FullScreenWebViewDialog(
    context: Context,
    private val webView: WebView,
    private val errorText: TextView,
    private val originalParent: ViewGroup
) : Dialog(context, R.style.Theme_HomeWx_FullScreenDialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context)
        originalParent.removeView(webView)
        originalParent.removeView(errorText)
        root.addView(webView)
        root.addView(errorText)
        root.addView(buildCloseButton(), closeButtonLayoutParams())
        setContentView(root)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** Pressing back or tapping outside both route through [cancel], which calls this - the
     *  single place that needs to hand the views back, regardless of how the dialog closed. */
    override fun dismiss() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        (errorText.parent as? ViewGroup)?.removeView(errorText)
        if (originalParent.indexOfChild(webView) == -1) originalParent.addView(webView, 0)
        if (originalParent.indexOfChild(errorText) == -1) originalParent.addView(errorText)
        super.dismiss()
    }

    private fun buildCloseButton(): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(context.getColor(R.color.text_primary))
            setBackgroundColor(Color.argb(102, 0, 0, 0))
            val padding = (20 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnClickListener { dismiss() }
        }
    }

    private fun closeButtonLayoutParams(): FrameLayout.LayoutParams {
        val margin = (12 * context.resources.displayMetrics.density).toInt()
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(margin, margin, margin, margin)
        }
    }
}
