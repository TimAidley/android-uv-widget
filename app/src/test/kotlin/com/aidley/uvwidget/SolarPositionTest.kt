package com.aidley.uvwidget

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the solar calculation against published sunrise/sunset times. The widget's night
 * colour depends entirely on this, and it is the one piece of the app with no server to blame.
 */
class SolarPositionTest {

    @Test
    fun `london midsummer solar noon elevation`() {
        val noon = utcMillis(2026, 6, 21, 12, 2)
        assertEquals(61.9, SolarPosition.elevationDegrees(noon, LONDON_LAT, LONDON_LON), 0.5)
    }

    @Test
    fun `london midwinter solar noon elevation`() {
        val noon = utcMillis(2026, 12, 21, 11, 58)
        assertEquals(15.1, SolarPosition.elevationDegrees(noon, LONDON_LAT, LONDON_LON), 0.5)
    }

    @Test
    fun `london sunrise and sunset in midwinter match published times`() {
        // Published for 21 December 2026: sunrise 08:03, sunset 15:53 (UTC).
        assertMinutesEqual(8 * 60 + 3, crossingMinutes(LocalDate.of(2026, 12, 21), LONDON_LAT, LONDON_LON, rising = true))
        assertMinutesEqual(15 * 60 + 53, crossingMinutes(LocalDate.of(2026, 12, 21), LONDON_LAT, LONDON_LON, rising = false))
    }

    @Test
    fun `new york summer sunrise matches published time`() {
        // Published for 21 June 2026: sunrise 05:25 EDT = 09:25 UTC.
        assertMinutesEqual(9 * 60 + 25, crossingMinutes(LocalDate.of(2026, 6, 21), 40.7128, -74.0060, rising = true))
    }

    @Test
    fun `southern hemisphere is handled`() {
        // Sydney, 15 January 2026, 20:08 AEDT = 09:08 UTC, just before sunset.
        assertTrue(SolarPosition.isSunUp(utcMillis(2026, 1, 15, 9, 0), SYDNEY_LAT, SYDNEY_LON))
        // 21:00 AEDT = 10:00 UTC, well after sunset.
        assertFalse(SolarPosition.isSunUp(utcMillis(2026, 1, 15, 10, 0), SYDNEY_LAT, SYDNEY_LON))
    }

    @Test
    fun `midnight sun inside the arctic circle`() {
        val midnight = utcMillis(2026, 6, 21, 0, 0) // 02:00 local in Tromso
        assertTrue(SolarPosition.isSunUp(midnight, TROMSO_LAT, TROMSO_LON))
    }

    @Test
    fun `polar night inside the arctic circle`() {
        val noon = utcMillis(2026, 12, 21, 11, 0)
        assertFalse(SolarPosition.isSunUp(noon, TROMSO_LAT, TROMSO_LON))
    }

    @Test
    fun `negative longitudes do not wrap the hour angle incorrectly`() {
        // Honolulu, 21 March 2026 at 22:00 UTC is midday local: the sun must be high.
        val elevation = SolarPosition.elevationDegrees(utcMillis(2026, 3, 21, 22, 0), 21.3069, -157.8583)
        assertTrue("expected a high sun, got $elevation", elevation > 60.0)
    }

    private fun assertMinutesEqual(expected: Int, actual: Int) {
        assertTrue(
            "expected ${format(expected)} but was ${format(actual)}",
            abs(expected - actual) <= TOLERANCE_MINUTES
        )
    }

    private fun format(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** Minutes into the UTC day at which the sun crosses the sunrise/sunset altitude. */
    private fun crossingMinutes(date: LocalDate, latitude: Double, longitude: Double, rising: Boolean): Int {
        val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        for (minute in 0 until 1440) {
            val before = SolarPosition.isSunUp(dayStart + minute * 60_000L, latitude, longitude)
            val after = SolarPosition.isSunUp(dayStart + (minute + 1) * 60_000L, latitude, longitude)
            if (rising && !before && after) return minute + 1
            if (!rising && before && !after) return minute + 1
        }
        throw AssertionError("no crossing found on $date")
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        const val TOLERANCE_MINUTES = 3

        const val LONDON_LAT = 51.5074
        const val LONDON_LON = -0.1278
        const val SYDNEY_LAT = -33.8688
        const val SYDNEY_LON = 151.2093
        const val TROMSO_LAT = 69.6492
        const val TROMSO_LON = 18.9553
    }
}
