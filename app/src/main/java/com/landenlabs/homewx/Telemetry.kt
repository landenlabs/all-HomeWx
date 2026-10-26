package com.landenlabs.homewx

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Thin wrapper over Firebase Analytics + Crashlytics. Every call is a silent no-op when Firebase
 *  isn't configured (no google-services.json at build time), so the app runs fine without it. */
object Telemetry {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) return
        analytics = FirebaseAnalytics.getInstance(context)
        crashlytics = FirebaseCrashlytics.getInstance().also {
            it.setCustomKey("build_time", BuildConfig.BUILD_TIME)
        }
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
