package com.dlang.homewx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dlang.homewx.MainActivity
import com.dlang.homewx.R
import com.dlang.homewx.data.DailySnapshot
import com.dlang.homewx.data.DailySnapshotStore
import com.dlang.homewx.data.GoveeRepository
import com.dlang.homewx.data.RiverHistoryStore
import com.dlang.homewx.data.SensorHistoryStore
import com.dlang.homewx.data.WeatherMetricsHistoryStore
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.news.NewsRepository
import com.dlang.homewx.news.NewsSourceId
import com.dlang.homewx.power.LightSensorMonitor
import com.dlang.homewx.rivers.RiverGaugeRepository
import com.dlang.homewx.rivers.RiverGaugeSettings
import com.dlang.homewx.settings.AppSettings
import com.dlang.homewx.state.AppState
import com.dlang.homewx.weather.DailyExtreme
import com.dlang.homewx.weather.DailyExtremes
import com.dlang.homewx.weather.HomeLocation
import com.dlang.homewx.weather.WeatherDailyTracker
import com.dlang.homewx.weather.WeatherForecast
import com.dlang.homewx.weather.WeatherRepository
import com.dlang.homewx.weather.WeatherSourceConfig
import com.dlang.homewx.weather.bestExtreme
import com.dlang.homewx.weather.historicalComparisonWindow
import com.dlang.homewx.weather.hourlyDelta
import com.dlang.homewx.weather.isSameDay
import com.dlang.homewx.weather.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs for as long as the app is installed: watches the ambient light sensor
 * and polls Govee for indoor readings on a cadence that backs off while the
 * room is dark, so the dashboard keeps updating even with the screen off.
 */
class HomeWxMonitorService : LifecycleService() {

    private val lightSensorMonitor by lazy { LightSensorMonitor(applicationContext) }
    private val goveeRepository = GoveeRepository()
    private val sensorHistoryStore by lazy { SensorHistoryStore(applicationContext) }
    private val weatherMetricsHistoryStore by lazy { WeatherMetricsHistoryStore(applicationContext) }
    private val dailySnapshotStore by lazy { DailySnapshotStore(applicationContext) }
    private val weatherDailyTracker = WeatherDailyTracker()
    private val newsRepository = NewsRepository()
    private val riverGaugeRepository = RiverGaugeRepository()
    private val riverHistoryStore by lazy { RiverHistoryStore(applicationContext) }
    private val weatherRepository = WeatherRepository(
        location = HomeLocation.CURRENT,
        activeSource = { WeatherSourceConfig.getActiveSource(applicationContext) },
        forecastDays = { AppSettings.getForecastDays(applicationContext) }
    )
    private var latestForecast: WeatherForecast? = null
    private var historicalAverageFetchedForDay: Long? = null
    private val sensorFailureCounts = mutableMapOf<String, Int>()
    private var sensorFailureCountsDay: Long? = null

    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    /** onAvailable/onLost alone can miss a transition (e.g. a fast flap where the default
     *  network is replaced, or a capability-only change with no onLost/onAvailable pair at
     *  all) and leave UiState.networkReachable stuck on the wrong value indefinitely - that's
     *  the "red bar never clears" bug. onCapabilitiesChanged narrows the flag to the network's
     *  actual internet+validated state, and the watchdog loop below re-asserts ground truth on
     *  a timer regardless of what the callback last said, so a missed event can't stick forever. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "networkCallback: onAvailable $network")
            AppState.uiState.update { it.copy(networkReachable = true) }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "networkCallback: onLost $network")
            AppState.uiState.update { it.copy(networkReachable = false) }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val reachable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (AppState.uiState.value.networkReachable != reachable) {
                Log.i(TAG, "networkCallback: onCapabilitiesChanged reachable=$reachable $network")
            }
            AppState.uiState.update { it.copy(networkReachable = reachable) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // On API 31+ the system can recreate a START_STICKY service directly after an
            // OOM-kill, outside of MainActivity's explicit startForegroundService() call - that
            // path doesn't carry the "allowed to start from background" grant, so
            // startForeground() throws ForegroundServiceStartNotAllowedException here. Bail out
            // instead of crashing; MainActivity restarts this service (with the grant) the next
            // time it's created.
            stopSelf()
            return
        }

        lightSensorMonitor.start()
        AppState.uiState.update { it.copy(networkReachable = isNetworkCurrentlyReachable()) }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Ground-truth watchdog: reconciles networkReachable against an actual query on a
        // timer, independent of networkCallback. This is what actually fixes the "stuck"
        // symptom - even if a callback event is ever missed, this self-corrects within one tick
        // instead of leaving the red bar stuck until the app is restarted.
        lifecycleScope.launch {
            while (true) {
                val reachable = isNetworkCurrentlyReachable()
                if (AppState.uiState.value.networkReachable != reachable) {
                    Log.w(TAG, "Network watchdog correcting stale reachable flag to $reachable")
                }
                AppState.uiState.update { it.copy(networkReachable = reachable) }
                delay(NETWORK_WATCHDOG_INTERVAL_MS)
            }
        }

        lifecycleScope.launch {
            while (true) {
                try {
                    val sensors = goveeRepository.refreshAll()
                    val sensorsWithTrends = withContext(Dispatchers.IO) {
                        val today = startOfDay(System.currentTimeMillis())
                        if (sensorFailureCountsDay != today) {
                            sensorFailureCountsDay = today
                            sensorFailureCounts.clear()
                        }
                        sensors.map { reading ->
                            sensorHistoryStore.record(reading)
                            val past = sensorHistoryStore.getValueNear(
                                reading.id,
                                reading.updatedAtMillis - HOUR_MILLIS,
                                SENSOR_TREND_TOLERANCE_MS
                            )
                            val failureCount = if (reading.error != null) {
                                sensorFailureCounts.merge(reading.id, 1, Int::plus) ?: 1
                            } else {
                                sensorFailureCounts[reading.id] ?: 0
                            }
                            reading.copy(
                                tempTrend1hF = if (reading.tempF != null && past?.tempF != null) reading.tempF - past.tempF else null,
                                humidityTrend1hPct = if (reading.humidityPct != null && past?.humidityPct != null) {
                                    reading.humidityPct - past.humidityPct
                                } else {
                                    null
                                },
                                lastSuccessAtMillis = if (reading.error != null) sensorHistoryStore.getLatestTimestamp(reading.id) else null,
                                failureCountToday = failureCount
                            )
                        }
                    }
                    AppState.uiState.update {
                        it.copy(
                            sensors = sensorsWithTrends,
                            sensorsUpdatedAtMillis = System.currentTimeMillis(),
                            lastError = null
                        )
                    }
                    applyTempSensorOverrideToCurrentConditions()
                    refreshDailyExtremes()
                } catch (e: Exception) {
                    val message = e.message ?: "Govee refresh failed"
                    Log.e(TAG, "Govee refresh failed", e)
                    AppState.recordError("Govee", e)
                    AppState.uiState.update { it.copy(lastError = message) }
                }
                val intervalMs = if (AppState.uiState.value.lightMode == LightMode.QUIET) {
                    QUIET_POLL_INTERVAL_MS
                } else {
                    ACTIVE_POLL_INTERVAL_MS
                }
                delay(intervalMs)
            }
        }

        lifecycleScope.launch {
            var consecutiveFailures = 0
            while (true) {
                try {
                    val current = weatherRepository.getCurrentConditions()
                    val forecast = weatherRepository.getForecast()
                    latestForecast = forecast
                    weatherDailyTracker.recordSample(current.observedAtMillis, current.temperatureF, current.windSpeedMph)
                    withContext(Dispatchers.IO) { weatherMetricsHistoryStore.record(current) }
                    val tempTrend = forecast.hourlyDelta(current.observedAtMillis, 1, current.temperatureF) { it.temperatureF }
                    val tempTrend4h = forecast.hourlyDelta(current.observedAtMillis, 4, current.temperatureF) { it.temperatureF }
                    val pressureTrend = forecast.hourlyDelta(current.observedAtMillis, 6, current.pressureInHg) { it.pressureInHg }
                    AppState.uiState.update {
                        it.copy(
                            currentWeather = current,
                            weatherForecast = forecast,
                            tempTrendNextHourF = tempTrend,
                            tempTrendNext4HourF = tempTrend4h,
                            pressureTrend6hInHg = pressureTrend,
                            weatherError = null
                        )
                    }
                    consecutiveFailures = 0
                    applyTempSensorOverrideToCurrentConditions()
                    refreshDailyExtremes()
                    refreshHistoricalAverageIfNewDay()
                    saveTodaySnapshot()
                } catch (e: Exception) {
                    consecutiveFailures++
                    val message = e.message ?: "Weather refresh failed"
                    Log.e(
                        TAG,
                        "Weather refresh failed (attempt $consecutiveFailures, " +
                            "source=${WeatherSourceConfig.getActiveSource(applicationContext)}, " +
                            "networkReachable=${AppState.uiState.value.networkReachable})",
                        e
                    )
                    AppState.recordError("Weather", e)
                    AppState.uiState.update { it.copy(weatherError = message) }
                }
                val normalIntervalMs = AppSettings.getWeatherSampleIntervalMinutes(applicationContext) * 60_000L
                delay(retryDelayMs(consecutiveFailures, normalIntervalMs))
            }
        }

        lifecycleScope.launch {
            while (true) {
                // Each source is fetched independently so one feed failing doesn't blank out the other.
                val items = NewsSourceId.values().associateWith { source ->
                    runCatching { newsRepository.fetchFeed(source) }.getOrElse { e ->
                        Log.w(TAG, "News feed $source failed", e)
                        AppState.recordError("News:$source", e)
                        emptyList()
                    }
                }
                AppState.uiState.update { it.copy(newsItemsBySource = items) }
                delay(NEWS_POLL_INTERVAL_MS)
            }
        }

        lifecycleScope.launch {
            var consecutiveFailures = 0
            while (true) {
                val gauges = RiverGaugeSettings.getSelectedGauges(applicationContext)
                if (RiverGaugeSettings.isEnabled(applicationContext) && gauges.isNotEmpty()) {
                    try {
                        val latest = withContext(Dispatchers.IO) {
                            gauges.associate { site ->
                                // First time this gauge has ever been polled: backfill a week of
                                // history so its strip chart isn't empty on first visit, instead
                                // of just the one point a plain "latest reading" call would give.
                                if (riverHistoryStore.getLatestTimestamp(site.siteId) == null) {
                                    val history = riverGaugeRepository.getHistory(
                                        site,
                                        System.currentTimeMillis() - RIVER_BACKFILL_MILLIS
                                    )
                                    riverHistoryStore.recordAll(history)
                                    site.siteId to (history.lastOrNull() ?: riverGaugeRepository.getLatestReading(site))
                                } else {
                                    val reading = riverGaugeRepository.getLatestReading(site)
                                    riverHistoryStore.record(reading)
                                    site.siteId to reading
                                }
                            }
                        }
                        AppState.uiState.update {
                            it.copy(riverReadings = latest, riverReadingsUpdatedAtMillis = System.currentTimeMillis(), riverError = null)
                        }
                        consecutiveFailures = 0
                    } catch (e: Exception) {
                        consecutiveFailures++
                        val message = e.message ?: "River gauge refresh failed"
                        Log.e(TAG, "River gauge refresh failed (attempt $consecutiveFailures)", e)
                        AppState.recordError("River", e)
                        AppState.uiState.update { it.copy(riverError = message) }
                    }
                } else {
                    consecutiveFailures = 0
                }
                delay(retryDelayMs(consecutiveFailures, RIVER_POLL_INTERVAL_MS))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // START_NOT_STICKY: don't let the system auto-restart this service after it's killed -
        // that restart path is what triggers ForegroundServiceStartNotAllowedException above.
        // MainActivity.onCreate already restarts it (with the required grant) whenever it comes
        // back up.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lightSensorMonitor.stop()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // onCreate can bail out (stopSelf()) before registering the callback - e.g. the
            // ForegroundServiceStartNotAllowedException path above - in which case this callback
            // was never registered and unregistering it would otherwise crash onDestroy.
        }
        super.onDestroy()
    }

    private fun isNetworkCurrentlyReachable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Backs off quickly on repeated failure instead of waiting out the full poll interval, so
     *  recovery (network coming back, a transient upstream 5xx) shows up within seconds to a
     *  couple minutes rather than up to a full interval later. Resets to [normalIntervalMs] as
     *  soon as [consecutiveFailures] returns to 0. */
    private fun retryDelayMs(consecutiveFailures: Int, normalIntervalMs: Long): Long {
        if (consecutiveFailures <= 0) return normalIntervalMs
        val backoff = RETRY_BASE_MS * (1L shl (consecutiveFailures - 1).coerceAtMost(6))
        return backoff.coerceAtMost(normalIntervalMs)
    }

    /** When the sensor override is on, the selected sensor's own temp/humidity replace the weather provider's in the live display. */
    private fun applyTempSensorOverrideToCurrentConditions() {
        val overrideSensorId = AppSettings.getTempSensorOverrideSensorId(applicationContext)
            .takeIf { AppSettings.isTempSensorOverrideEnabled(applicationContext) }
            ?: return
        val sensor = AppState.uiState.value.sensors.firstOrNull { it.id == overrideSensorId } ?: return
        AppState.uiState.update { state ->
            val current = state.currentWeather ?: return@update state
            state.copy(
                currentWeather = current.copy(
                    temperatureF = sensor.tempF ?: current.temperatureF,
                    humidityPct = sensor.humidityPct ?: current.humidityPct
                )
            )
        }
    }

    private suspend fun refreshDailyExtremes() {
        val nowMillis = System.currentTimeMillis()
        val overrideSensorId = AppSettings.getTempSensorOverrideSensorId(applicationContext)
            .takeIf { AppSettings.isTempSensorOverrideEnabled(applicationContext) }

        val todayTempPoints = latestForecast?.hourly.orEmpty()
            .filter { isSameDay(it.timeMillis, nowMillis) }
            .mapNotNull { entry -> entry.temperatureF?.let { entry.timeMillis to it } }

        val (tempHigh, tempLow) = if (overrideSensorId != null) {
            // Seed from the sensor's own readings so far today, then let the
            // forecast's remaining hours push the extremes further if needed.
            val (sensorHigh, sensorLow) = withContext(Dispatchers.IO) {
                sensorHistoryStore.getTodayTempExtremes(overrideSensorId, startOfDay(nowMillis))
            }
            val sensorHighExtreme = sensorHigh?.let { DailyExtreme(it.value, it.atMillis) }
            val sensorLowExtreme = sensorLow?.let { DailyExtreme(it.value, it.atMillis) }
            bestExtreme(sensorHighExtreme, todayTempPoints, pickHigh = true) to
                bestExtreme(sensorLowExtreme, todayTempPoints, pickHigh = false)
        } else {
            val (trackerHigh, trackerLow) = weatherDailyTracker.tempExtremes()
            bestExtreme(trackerHigh, todayTempPoints, pickHigh = true) to
                bestExtreme(trackerLow, todayTempPoints, pickHigh = false)
        }

        val todayWindPoints = latestForecast?.hourly.orEmpty()
            .filter { isSameDay(it.timeMillis, nowMillis) }
            .mapNotNull { entry -> entry.windSpeedMph?.let { entry.timeMillis to it } }
        val (trackerWindHigh, trackerWindLow) = weatherDailyTracker.windExtremes()
        val windHigh = bestExtreme(trackerWindHigh, todayWindPoints, pickHigh = true)
        val windLow = bestExtreme(trackerWindLow, todayWindPoints, pickHigh = false)

        AppState.uiState.update {
            it.copy(dailyExtremes = DailyExtremes(tempHigh, tempLow, windHigh, windLow))
        }
    }

    /**
     * Upserts today's row on every successful poll with the latest known conditions/extremes,
     * rather than trying to detect the midnight transition and write once - that approach only
     * worked if the service happened to stay alive continuously across midnight, which isn't
     * guaranteed (OEM battery management, app restarts, etc.). This way, today's row is always
     * current, and once the calendar rolls over, it simply stops being touched and stands as
     * the permanent frozen record for that day.
     */
    private suspend fun saveTodaySnapshot() {
        val state = AppState.uiState.value
        val current = state.currentWeather ?: return
        val extremes = state.dailyExtremes
        val historicalAverage = state.historicalTempAverage
        withContext(Dispatchers.IO) {
            dailySnapshotStore.saveSnapshot(
                DailySnapshot(
                    dayStartMillis = startOfDay(System.currentTimeMillis()),
                    conditionText = current.conditionText,
                    iconKey = current.iconKey,
                    tempF = current.temperatureF,
                    feelsLikeF = current.feelsLikeF,
                    humidityPct = current.humidityPct,
                    windSpeedMph = current.windSpeedMph,
                    windDirectionDeg = current.windDirectionDeg,
                    precipitationIn = current.precipitationIn,
                    pressureInHg = current.pressureInHg,
                    tempHighF = extremes.tempHighF?.value,
                    tempHighAtMillis = extremes.tempHighF?.atMillis,
                    tempLowF = extremes.tempLowF?.value,
                    tempLowAtMillis = extremes.tempLowF?.atMillis,
                    windHighMph = extremes.windHighMph?.value,
                    windHighAtMillis = extremes.windHighMph?.atMillis,
                    windLowMph = extremes.windLowMph?.value,
                    windLowAtMillis = extremes.windLowMph?.atMillis,
                    lyAvgHighF = historicalAverage?.avgHighF,
                    lyAvgLowF = historicalAverage?.avgLowF
                )
            )
        }
    }

    /** Historical daily averages don't change intra-day - only worth a network call once per calendar day. */
    private suspend fun refreshHistoricalAverageIfNewDay() {
        val today = startOfDay(System.currentTimeMillis())
        if (historicalAverageFetchedForDay == today) return
        try {
            val (start, end) = historicalComparisonWindow(System.currentTimeMillis())
            val average = weatherRepository.getHistoricalDailyAverage(start, end)
            AppState.uiState.update { it.copy(historicalTempAverage = average) }
            historicalAverageFetchedForDay = today
        } catch (e: Exception) {
            // leave the flag unset so we retry on the next weather poll
            Log.w(TAG, "Historical average refresh failed, will retry next weather poll", e)
            AppState.recordError("HistoricalAverage", e)
        }
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
        private const val TAG = "HomeWxMonitor"
        private const val CHANNEL_ID = "homewx_monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTIVE_POLL_INTERVAL_MS = 2 * 60 * 1000L
        private const val QUIET_POLL_INTERVAL_MS = 15 * 60 * 1000L
        private const val HOUR_MILLIS = 60 * 60 * 1000L
        private const val SENSOR_TREND_TOLERANCE_MS = 20 * 60 * 1000L
        private const val NEWS_POLL_INTERVAL_MS = 10 * 60 * 1000L
        private const val RIVER_POLL_INTERVAL_MS = 15 * 60 * 1000L
        private const val RIVER_BACKFILL_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val NETWORK_WATCHDOG_INTERVAL_MS = 30 * 1000L
        private const val RETRY_BASE_MS = 20 * 1000L

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
