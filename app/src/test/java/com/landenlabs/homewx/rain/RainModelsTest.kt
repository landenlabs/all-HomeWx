package com.landenlabs.homewx.rain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RainModelsTest {

    private fun millis(year: Int, month: Int, day: Int) =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day) }.timeInMillis

    @Test
    fun monthlyTotalsCoverThePrevious3FullMonthsAndLastYear() {
        val now = millis(2026, 11, 2)
        val days = listOf(
            DailyRain(millis(2026, 7, 31), 9.0),   // before window
            DailyRain(millis(2026, 8, 1), 1.0),
            DailyRain(millis(2026, 8, 31), 0.5),
            DailyRain(millis(2026, 9, 15), 2.0),
            DailyRain(millis(2026, 10, 31), 3.0),
            DailyRain(millis(2026, 11, 1), 9.0),   // current month, excluded
            DailyRain(millis(2026, 10, 10), null)  // missing counts as 0
        )
        val lastYear = listOf(DailyRain(millis(2025, 8, 5), 4.0), DailyRain(millis(2025, 10, 20), 0.25))

        val months = buildMonthlyTotals(days, now, lastYear)

        assertEquals(listOf(millis(2026, 8, 1), millis(2026, 9, 1), millis(2026, 10, 1)), months.map { it.monthStartMillis })
        assertEquals(listOf(1.5, 2.0, 3.0), months.map { it.inches })
        assertEquals(listOf(4.0, 0.0, 0.25), months.map { it.lastYearInches })
    }

    @Test
    fun monthlyLastYearIsNullWhenNotFetched() {
        assertNull(buildMonthlyTotals(emptyList(), millis(2026, 11, 2)).first().lastYearInches)
    }
}
