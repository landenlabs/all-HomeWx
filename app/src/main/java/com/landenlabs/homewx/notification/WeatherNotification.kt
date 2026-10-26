package com.landenlabs.homewx.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.landenlabs.homewx.MainActivity
import com.landenlabs.homewx.R
import com.landenlabs.homewx.model.UiState
import com.landenlabs.homewx.settings.AppSettings
import com.landenlabs.homewx.state.AppState
import com.landenlabs.homewx.ui.weatherIconRes
import com.landenlabs.homewx.weather.precipChanceAt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Optional (see [AppSettings.isWeatherNotificationEnabled]) notification showing the current
 * conditions - icon, temperature, precipitation chance (or the rain total while it's actually
 * raining) and wind - which expands to the next [HOURS_AHEAD] hours. It's separate from the
 * monitor service's own foreground notification.
 */
object WeatherNotification {

    private const val CHANNEL_ID = "homewx_weather"
    private const val NOTIFICATION_ID = 2
    private const val HOURS_AHEAD = 6
    private const val LARGE_ICON_PX = 128

    private val COMPASS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )

    /** Posts or updates the notification from the latest [AppState], or removes it if disabled. */
    fun refresh(context: Context) {
        if (AppSettings.isWeatherNotificationEnabled(context)) {
            post(context, AppState.uiState.value)
        } else {
            cancel(context)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, state: UiState) {
        val current = state.currentWeather ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.weather_notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )

        val raining = (current.precipitationIn ?: 0.0) > 0.0
        val precipText = if (raining) {
            "Rain %.2f in".format(current.precipitationIn)
        } else {
            state.weatherForecast?.precipChanceAt(current.observedAtMillis)?.let { "$it% precip" }
        }
        val title = "${current.temperatureF?.roundToInt()?.let { "$it°F" } ?: "--"}  ${current.conditionText}"
        val summary = listOfNotNull(precipText, formatWind(current.windSpeedMph, current.windDirectionDeg)).joinToString("  •  ")

        val hourFormat = SimpleDateFormat("h a", Locale.getDefault())
        val nextHours = state.weatherForecast?.hourly.orEmpty()
            .filter { it.timeMillis > System.currentTimeMillis() }
            .sortedBy { it.timeMillis }
            .take(HOURS_AHEAD)
            .joinToString("\n") { hour ->
                listOfNotNull(
                    hourFormat.format(Date(hour.timeMillis)).padEnd(5),
                    hour.temperatureF?.roundToInt()?.let { "$it°" },
                    hour.precipitationChancePct?.let { "$it%" },
                    formatWind(hour.windSpeedMph, null)
                ).joinToString("   ")
            }

        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_thermometer)
            .setLargeIcon(loadIcon(context, current.iconKey))
            .setContentTitle(title)
            .setContentText(summary)
            .setContentIntent(contentIntent)
            .setWhen(current.observedAtMillis)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (nextHours.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n\n$nextHours"))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun loadIcon(context: Context, iconKey: String): Bitmap? {
        val bitmap = BitmapFactory.decodeResource(context.resources, context.weatherIconRes(iconKey)) ?: return null
        return Bitmap.createScaledBitmap(bitmap, LARGE_ICON_PX, LARGE_ICON_PX, true)
    }

    private fun formatWind(speedMph: Double?, directionDeg: Double?): String? {
        if (speedMph == null) return null
        val compass = directionDeg?.let { COMPASS[((it / 22.5) + 0.5).toInt().mod(COMPASS.size)] }
        return listOfNotNull("Wind", compass, "${speedMph.roundToInt()} mph").joinToString(" ")
    }
}
