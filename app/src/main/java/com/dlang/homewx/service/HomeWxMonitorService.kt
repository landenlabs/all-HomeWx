package com.dlang.homewx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dlang.homewx.MainActivity
import com.dlang.homewx.R
import com.dlang.homewx.data.GoveeRepository
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.power.LightSensorMonitor
import com.dlang.homewx.state.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs for as long as the app is installed: watches the ambient light sensor
 * and polls Govee for indoor readings on a cadence that backs off while the
 * room is dark, so the dashboard keeps updating even with the screen off.
 */
class HomeWxMonitorService : LifecycleService() {

    private val lightSensorMonitor by lazy { LightSensorMonitor(applicationContext) }
    private val goveeRepository = GoveeRepository()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        lightSensorMonitor.start()

        lifecycleScope.launch {
            while (true) {
                try {
                    val sensors = goveeRepository.refreshAll()
                    AppState.uiState.update { it.copy(sensors = sensors, lastError = null) }
                } catch (e: Exception) {
                    AppState.uiState.update { it.copy(lastError = e.message ?: "Govee refresh failed") }
                }
                val intervalMs = if (AppState.uiState.value.lightMode == LightMode.QUIET) {
                    QUIET_POLL_INTERVAL_MS
                } else {
                    ACTIVE_POLL_INTERVAL_MS
                }
                delay(intervalMs)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        lightSensorMonitor.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.monitor_service_notification_title))
            .setContentText(getString(R.string.monitor_service_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "homewx_monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTIVE_POLL_INTERVAL_MS = 2 * 60 * 1000L
        private const val QUIET_POLL_INTERVAL_MS = 15 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, HomeWxMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
