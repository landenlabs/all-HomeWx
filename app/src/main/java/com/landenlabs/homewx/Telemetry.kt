package com.landenlabs.homewx

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.landenlabs.homewx.settings.AppSettings

/** Thin wrapper over Firebase Analytics + Crashlytics. Every call is a silent no-op when Firebase
 *  isn't configured (no google-services.json at build time), so the app runs fine without it.
 *  Collection follows [AppSettings.isTelemetryEnabled] (on by default; Settings > Privacy opts out). */
object Telemetry {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) return
        analytics = FirebaseAnalytics.getInstance(context)
        crashlytics = FirebaseCrashlytics.getInstance().also {
            it.setCustomKey("build_time", BuildConfig.BUILD_TIME)
        }
        applyCollection(AppSettings.isTelemetryEnabled(context))
    }

    /** Saves the user's choice and applies it right away. Opting out also drops crash reports not
     *  yet uploaded and resets the analytics app-instance ID, so nothing queued on this device is
     *  sent and any later opt-in starts unlinked from earlier data. Data Google already holds is
     *  not deleted here; it expires under Firebase's retention periods. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val wasEnabled = AppSettings.isTelemetryEnabled(context)
        AppSettings.setTelemetryEnabled(context, enabled)
        applyCollection(enabled)
        if (wasEnabled && !enabled) {
            crashlytics?.deleteUnsentReports()
            analytics?.resetAnalyticsData()
        }
    }

    /** Both Firebase flags persist on their own too, so they stay off from the next cold start. */
    private fun applyCollection(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    fun logEvent(name: String, params: Bundle? = null) {
        analytics?.logEvent(name, params)
    }

    /** Reports a handled exception; shows up in Crashlytics as a non-fatal tagged with [source]. */
    fun recordNonFatal(source: String, throwable: Throwable) {
        crashlytics?.let {
            it.setCustomKey("error_source", source)
            it.recordException(throwable)
        }
    }
}
