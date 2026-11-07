package com.landenlabs.homewx.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One-shot device position lookup, used only when it is good enough to beat the Settings zip
 *  centroid: location services on, permission granted, accuracy within [MAX_ACCURACY_METERS]
 *  (+/- 5 miles) and no older than [MAX_AGE_MS]. Anything else returns null so callers fall back
 *  to [HomeLocation]. */
object DeviceLocation {
    const val MAX_ACCURACY_METERS = 8_047f // 5 miles
    private const val MAX_AGE_MS = 30 * 60 * 1000L
    private const val FIX_TIMEOUT_MS = 30_000L
    private const val TAG = "DeviceLocation"

    @SuppressLint("MissingPermission") // checked via hasFine/hasCoarse below
    suspend fun accurateFix(context: Context): Location? {
        val hasFine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasFine || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasCoarse) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!LocationManagerCompat.isLocationEnabled(lm)) return null

        // GPS needs fine permission; network/fused work with approximate (coarse) location too.
        val providers = buildList {
            if (hasFine) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        // A recent fix some other app/system service already got is free - use it if good enough.
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter(::isUsable)
            .minByOrNull { it.accuracy }
            ?.let { return it }

        // Otherwise ask every enabled provider at once and keep the most accurate usable answer.
        val fixes = coroutineScope {
            providers.map { provider ->
                async { withTimeoutOrNull(FIX_TIMEOUT_MS) { currentFix(context, lm, provider) } }
            }.awaitAll()
        }
        val best = fixes.filterNotNull().filter(::isUsable).minByOrNull { it.accuracy }
        Log.i(TAG, "accurateFix: providers=$providers fixes=${fixes.map { it?.accuracy }} chose=${best?.provider}")
        return best
    }

    private fun isUsable(loc: Location): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        return loc.hasAccuracy() && loc.accuracy <= MAX_ACCURACY_METERS && ageMs <= MAX_AGE_MS
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) {
                    if (cont.isActive) cont.resume(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation($provider) failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
