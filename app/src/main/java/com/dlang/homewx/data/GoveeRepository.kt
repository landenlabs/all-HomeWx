package com.dlang.homewx.data

import com.dlang.homewx.model.SensorReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoveeRepository(private val api: GoveeApiClient = GoveeApiClient()) {

    private var cachedDevices: List<GoveeDevice>? = null

    suspend fun refreshAll(): List<SensorReading> = withContext(Dispatchers.IO) {
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
