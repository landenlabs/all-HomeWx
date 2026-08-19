package com.dlang.homewx.power

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.settings.AppSettings

/**
 * Applies a [LightMode] to the activity's window. QUIET drops brightness to a
 * minimum and lets the OS's own screen-off timeout finish the job; ACTIVE
 * forces the screen back on immediately and keeps it awake.
 */
class ScreenPowerController(private val activity: Activity) {

    private var appliedMode: LightMode? = null

    fun apply(mode: LightMode) {
        if (mode == appliedMode) return
        appliedMode = mode

        val window = activity.window
        when (mode) {
            LightMode.ACTIVE -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBrightness(AppSettings.getScreenBrightnessPercent(activity) / 100f)
                wakeScreen()
            }
            LightMode.QUIET -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBrightness(MIN_BRIGHTNESS)
            }
        }
    }

    /** Re-applies the current mode's brightness - picks up a brightness setting change made
     *  while this activity was paused (e.g. in the Settings screen), which [apply] alone
     *  would otherwise skip since the mode itself hasn't changed. */
    fun refresh() {
        val mode = appliedMode ?: return
        appliedMode = null
        apply(mode)
    }

    private fun setBrightness(value: Float) {
        val params = activity.window.attributes
        params.screenBrightness = value
        activity.window.attributes = params
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val window = activity.window
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.setTurnScreenOn(true)
            activity.setShowWhenLocked(true)
        }
    }

    companion object {
        private const val MIN_BRIGHTNESS = 0.01f
    }
}
