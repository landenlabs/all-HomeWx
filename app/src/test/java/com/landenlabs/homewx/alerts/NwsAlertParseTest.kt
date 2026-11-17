// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------

package com.landenlabs.homewx.alerts

import com.landenlabs.homewx.alerts.nws.NwsAlertProvider
import com.landenlabs.homewx.ui.unwrapNwsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class NwsAlertParseTest {

    private val now = OffsetDateTime.parse("2026-11-17T12:00:00-05:00").toInstant().toEpochMilli()

    private fun feature(
        id: String,
        event: String,
        severity: String = "Severe",
        status: String = "Actual",
        messageType: String = "Alert",
        expires: String = "2026-11-18T07:00:00-05:00",
        ends: String? = "2026-11-18T19:00:00-05:00"
    ) = """
        {"id":"https://api.weather.gov/alerts/$id","type":"Feature","properties":{
          "id":"$id","areaDesc":"Western Essex; Eastern Rockingham",
          "sent":"2026-11-17T03:12:00-05:00","effective":"2026-11-17T03:12:00-05:00",
          "onset":"2026-11-17T19:00:00-05:00","expires":"$expires",
          "ends":${ends?.let { "\"$it\"" } ?: "null"},
          "status":"$status","messageType":"$messageType","severity":"$severity",
          "certainty":"Likely","urgency":"Expected","event":"$event",
          "senderName":"NWS Boston/Norton MA","headline":"$event issued",
          "description":"* WHAT...Heavy snow.\nTotal 8 to 12\ninches.\n\n* WHERE...Essex.",
          "instruction":null}}
    """.trimIndent()

    private fun collection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    @Test fun parsesFields() {
        val alerts = NwsAlertProvider.parse(collection(feature("a1", "Winter Storm Warning")), now)
        assertEquals(1, alerts.size)
        val a = alerts[0]
        assertEquals("a1", a.id)
        assertEquals("Winter Storm Warning", a.event)
        assertEquals(AlertSeverity.SEVERE, a.severity)
        assertEquals("NWS Boston/Norton MA", a.senderName)
        assertEquals("Western Essex; Eastern Rockingham", a.areaDesc)
        assertNull("JSON null must not become the string \"null\"", a.instruction)
        assertEquals(OffsetDateTime.parse("2026-11-18T19:00:00-05:00").toInstant().toEpochMilli(), a.untilMillis)
    }

    @Test fun dropsTestsCancelsAndEnded() {
        val alerts = NwsAlertProvider.parse(
            collection(
                feature("keep", "Wind Advisory", severity = "Moderate"),
                feature("test", "Test Message", status = "Test"),
                feature("cancel", "Flood Watch", messageType = "Cancel"),
                feature("ended", "Frost Advisory", expires = "2026-11-17T09:00:00-05:00", ends = null)
            ),
            now
        )
        assertEquals(listOf("keep"), alerts.map { it.id })
    }

    @Test fun emptyCollection() {
        assertTrue(NwsAlertProvider.parse("""{"type":"FeatureCollection","features":[]}""", now).isEmpty())
    }

    @Test fun sortsMostSevereFirst() {
        val alerts = NwsAlertProvider.parse(
            collection(
                feature("minor", "Special Weather Statement", severity = "Minor"),
                feature("extreme", "Tornado Warning", severity = "Extreme"),
                feature("moderate", "Wind Advisory", severity = "Moderate")
            ),
            now
        ).sortedForDisplay()
        assertEquals(listOf("extreme", "moderate", "minor"), alerts.map { it.id })
    }

    @Test fun reflowsHardWrappedText() {
        assertEquals(
            "* WHAT...Heavy snow. Total 8 to 12 inches.\n\n* WHERE...Essex.",
            "* WHAT...Heavy snow.\nTotal 8 to 12\ninches.\n\n* WHERE...Essex.".unwrapNwsText()
        )
    }
}
