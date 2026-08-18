package com.dlang.homewx.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.settings.AppSettings
import com.dlang.homewx.state.AppState
import kotlinx.coroutines.flow.update


/**
 * Watches the ambient light sensor and flips [AppState]'s light mode once a
 * reading has been stable past the debounce window - avoids flapping when a
 * light switch bounces or a shadow crosses the sensor briefly. The light
 * (go-active) threshold is user-configurable via [AppSettings]; the dark
 * (go-quiet) threshold trails it by a fixed gap so there's always a
 * hysteresis band between the two.
 */
class LightSensorMonitor(
    private val context: Context,
    private val stableWindowMillis: Long = 4_000L
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var pendingMode: LightMode? = null
    private var pendingSinceMillis: Long = 0L

    val isAvailable: Boolean get() = lightSensor != null

    fun start() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values.firstOrNull() ?: return
        val now = System.currentTimeMillis()
        AppState.uiState.update { it.copy(currentLux = lux) }
        val currentMode = AppState.uiState.value.lightMode

        val lightThresholdLux = AppSettings.getLightThresholdLux(context)
        val darkThresholdLux = (lightThresholdLux - HYSTERESIS_GAP_LUX).coerceAtLeast(0f)

        // Readings between the two thresholds are the hysteresis band: neither
        // confirm the current mode nor start a new debounce timer.
        val candidate = when {
            lux <= darkThresholdLux -> LightMode.QUIET
            lux >= lightThresholdLux -> LightMode.ACTIVE
            else -> return
        }

        if (candidate == currentMode) {
            pendingMode = null
            return
        }

        if (pendingMode != candidate) {
            pendingMode = candidate
            pendingSinceMillis = now
            return
        }

        if (now - pendingSinceMillis >= stableWindowMillis) {
            AppState.uiState.update { it.copy(lightMode = candidate) }
            pendingMode = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val HYSTERESIS_GAP_LUX = 5f
    }
}
