// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * FrameLayout that reports every touch event inside it to [onAnyTouch] before dispatching it to
 * its children as usual (it never consumes or intercepts anything).
 *
 * Used as the root of the radar panel so any interaction with the map - tap, pan, pinch zoom,
 * the osmdroid +/- zoom buttons - can restart the layer picker's fade-out countdown. osmdroid's
 * MapView consumes those gestures internally (and handles its zoom buttons before overlays or
 * touch listeners ever see the event), so observing from the parent is the only spot that sees
 * all of them.
 */
class TouchObserverFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onAnyTouch: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onAnyTouch?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}
