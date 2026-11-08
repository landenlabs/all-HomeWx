package com.landenlabs.homewx.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * LIVE test: talks to the real Google Smart Device Management API and reads your home
 * thermostat. Prints house temperature, target temperature and furnace state.
 *
 * Needs NEST_PROJECT_ID / NEST_CLIENT_ID / NEST_CLIENT_SECRET / NEST_REFRESH_TOKEN in
 * local.properties (see private/google-nest.md). Without them the tests are skipped, not failed.
 *
 * Run:  ./gradlew :app:testDebugUnitTest --tests "*NestApiClientLiveTest" -i
 * (-i shows the printed status lines.)
 *
 * SDM quota is roughly 5 requests/minute, so each test makes one devices.list call at most.
 */
class NestApiClientLiveTest {

    private val client = NestApiClient()

    private fun thermostatsOrSkip(): List<NestThermostat> {
        assumeTrue("Nest credentials not set in local.properties - skipping live test", client.isConfigured)
        return client.listThermostats()
    }

    @Test fun reportsTemperatureTargetAndFurnaceState() {
        val thermostats = thermostatsOrSkip()
        assertTrue("No thermostat returned for this project/home", thermostats.isNotEmpty())

        for (t in thermostats) {
            println("Thermostat '${t.displayName}' (${t.name})")
            println("  online          : ${t.online}")
            println("  house temp      : ${t.temperatureF.fmtF()}")
            println("  target (heat)   : ${t.setpointHeatF.fmtF()}")
            println("  target (cool)   : ${t.setpointCoolF.fmtF()}")
            println("  mode            : ${t.mode}")
            println("  furnace         : ${t.hvacStatus.toFurnaceState()}  (hvac=${t.hvacStatus})")
        }

        val t = thermostats.first()
        assertTrue("Thermostat is offline", t.online)
        assertNotNull("House temperature missing", t.temperatureF)
        assertTrue("House temperature implausible: ${t.temperatureF}", t.temperatureF!! in 20.0..120.0)
        // The target depends on mode: HEAT -> heat setpoint, COOL -> cool setpoint, HEATCOOL -> both.
        when (t.mode) {
            NestThermostatMode.HEAT -> assertNotNull("Heat target missing", t.setpointHeatF)
            NestThermostatMode.COOL -> assertNotNull("Cool target missing", t.setpointCoolF)
            NestThermostatMode.HEATCOOL -> {
                assertNotNull("Heat target missing", t.setpointHeatF)
                assertNotNull("Cool target missing", t.setpointCoolF)
            }
            else -> Unit // OFF / UNKNOWN: no active target
        }
        assertTrue("Furnace state unknown", t.hvacStatus != NestHvacStatus.UNKNOWN)
    }

    /** Idle vs running, as a plain label. COOLING also counts as running (AC). */
    private fun NestHvacStatus.toFurnaceState(): String = when (this) {
        NestHvacStatus.HEATING -> "RUNNING (heating)"
        NestHvacStatus.COOLING -> "RUNNING (cooling)"
        NestHvacStatus.OFF -> "IDLE"
        NestHvacStatus.UNKNOWN -> "UNKNOWN"
    }

    private fun Double?.fmtF(): String = if (this == null) "n/a" else String.format("%.1f°F", this)
}
