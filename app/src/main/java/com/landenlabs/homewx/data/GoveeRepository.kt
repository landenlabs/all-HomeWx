package com.landenlabs.homewx.data

import com.landenlabs.homewx.model.SensorReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoveeRepository(
    private val apiKey: () -> String,
    private val api: GoveeApiClient = GoveeApiClient(apiKey)
) {

    private var cachedDevices: List<GoveeDevice>? = null
    private var cachedDevicesKey: String? = null

    /** No readings (and no network call) while no API key is configured. */
    suspend fun refreshAll(): List<SensorReading> = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) {
            cachedDevices = null
            return@withContext emptyList()
        }
        // The device list belongs to one account - drop it if the key was changed in Settings.
        if (key != cachedDevicesKey) {
            cachedDevices = null
            cachedDevicesKey = key
        }
        val devices = cachedDevices ?: api.listDevices().also { cachedDevices = it }
        devices
            .map { device ->
                try {
                    val reading = api.getDeviceState(device)
                    SensorReading(
                        id = device.device,
                        roomName = device.deviceName,
                        tempF = reading.temperatureF,
                        humidityPct = reading.humidityPct,
                        online = reading.online,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    SensorReading(
                        id = device.device,
                        roomName = device.deviceName,
                        tempF = null,
                        humidityPct = null,
                        online = false,
                        updatedAtMillis = System.currentTimeMillis(),
                        error = e.message
                    )
                }
            }
            .sortedBy { it.roomName }
    }
}
