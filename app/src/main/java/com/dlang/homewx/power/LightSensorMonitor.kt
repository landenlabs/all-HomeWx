package com.dlang.homewx.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.state.AppState
import kotlinx.coroutines.flow.update


/**
 * Watches the ambient light sensor and flips [AppState]'s light mode once a
 * reading has been stable past the debounce window - avoids flapping when a
 * light switch bounces or a shadow crosses the sensor briefly.
 */
class LightSensorMonitor(
    context: Context,
    private val darkThresholdLux: Float = 3f,
    private val lightThresholdLux: Float = 8f,
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
        val currentMode = AppState.uiState.value.lightMode

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
}
