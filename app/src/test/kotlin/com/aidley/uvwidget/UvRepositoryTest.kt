package com.aidley.uvwidget

import android.content.Context
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Covers what the widget decides to show, from stored state through to the drawn colour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UvRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private lateinit var repository: UvRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("uv_widget", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        repository = UvRepository(context)
    }

    @Test
    fun `with nothing configured the widget reports no location`() {
        val state = repository.currentState(LONDON_MIDDAY)
        assertFalse(state.hasLocation)
        assertNull(state.uvIndex)
        assertEquals(UvColors.UNKNOWN, UvColors.forState(state))
    }

    @Test
    fun `manual location is used when device location is switched off`() {
        prefs.useDeviceLocation = false
        prefs.manualLocation = LONDON

        val state = repository.currentState(LONDON_MIDDAY)
        assertTrue(state.hasLocation)
        assertTrue("midday in London should be daylight", state.isSunUp)
    }

    @Test
    fun `manual location is the fallback when no device fix has been cached`() {
        prefs.useDeviceLocation = true
        prefs.manualLocation = LONDON

        assertEquals(LONDON.latitude, prefs.effectiveLocation?.latitude ?: 0.0, 0.001)
    }

    @Test
    fun `a cached device fix takes priority over the manual location`() {
        prefs.useDeviceLocation = true
        prefs.manualLocation = LONDON
        prefs.cachedDeviceLocation = SYDNEY

        assertEquals(SYDNEY.latitude, prefs.effectiveLocation?.latitude ?: 0.0, 0.001)
    }

    @Test
    fun `forecast survives a round trip through storage`() {
        val forecast = UvForecast(
            latitude = LONDON.latitude,
            longitude = LONDON.longitude,
            fetchedAtMillis = LONDON_MIDDAY,
            hourTimesSeconds = longArrayOf(LONDON_MIDDAY / 1000 - 3600, LONDON_MIDDAY / 1000 + 3600),
            uvIndexPerHour = doubleArrayOf(4.0, 6.0)
        )
        prefs.forecast = forecast

        val restored = Prefs(context).forecast
        assertNotNull(restored)
        assertEquals(forecast, restored)
    }

    @Test
    fun `a stored forecast is shown for the configured location`() {
        prefs.useDeviceLocation = false
        prefs.manualLocation = LONDON
        prefs.forecast = UvForecast(
            latitude = LONDON.latitude,
            longitude = LONDON.longitude,
            fetchedAtMillis = LONDON_MIDDAY,
            hourTimesSeconds = longArrayOf(LONDON_MIDDAY / 1000 - 3600, LONDON_MIDDAY / 1000 + 3600),
            uvIndexPerHour = doubleArrayOf(4.0, 6.0)
        )

        val state = repository.currentState(LONDON_MIDDAY)
        assertEquals(5.0, requireNotNull(state.uvIndex), 0.0001)
        assertEquals(UvColors.forUvIndex(5.0), UvColors.forState(state))
    }

    @Test
    fun `a forecast for somewhere else is ignored`() {
        prefs.useDeviceLocation = false
        prefs.manualLocation = LONDON
        prefs.forecast = UvForecast(
            latitude = SYDNEY.latitude,
            longitude = SYDNEY.longitude,
            fetchedAtMillis = LONDON_MIDDAY,
            hourTimesSeconds = longArrayOf(LONDON_MIDDAY / 1000 - 3600, LONDON_MIDDAY / 1000 + 3600),
            uvIndexPerHour = doubleArrayOf(9.0, 9.0)
        )

        assertNull(repository.currentState(LONDON_MIDDAY).uvIndex)
    }

    @Test
    fun `an expired forecast is not shown`() {
        prefs.useDeviceLocation = false
        prefs.manualLocation = LONDON
        prefs.forecast = UvForecast(
            latitude = LONDON.latitude,
            longitude = LONDON.longitude,
            fetchedAtMillis = LONDON_MIDDAY - UvForecast.EXPIRED_AFTER_MILLIS - 1,
            hourTimesSeconds = longArrayOf(LONDON_MIDDAY / 1000 - 3600, LONDON_MIDDAY / 1000 + 3600),
            uvIndexPerHour = doubleArrayOf(4.0, 6.0)
        )

        assertNull(repository.currentState(LONDON_MIDDAY).uvIndex)
    }

    @Test
    fun `at night the widget is blue even with a healthy forecast`() {
        prefs.useDeviceLocation = false
        prefs.manualLocation = LONDON
        prefs.forecast = UvForecast(
            latitude = LONDON.latitude,
            longitude = LONDON.longitude,
            fetchedAtMillis = LONDON_MIDNIGHT,
            hourTimesSeconds = longArrayOf(LONDON_MIDNIGHT / 1000 - 3600, LONDON_MIDNIGHT / 1000 + 3600),
            uvIndexPerHour = doubleArrayOf(0.0, 0.0)
        )

        val state = repository.currentState(LONDON_MIDNIGHT)
        assertFalse(state.isSunUp)
        assertEquals(UvColors.NIGHT, UvColors.forState(state))
    }

    @Test
    fun `nonsense coordinates are rejected`() {
        assertFalse(Coordinates(91.0, 0.0).isValid)
        assertFalse(Coordinates(0.0, 181.0).isValid)
        assertFalse(Coordinates(Double.NaN, 0.0).isValid)
        assertTrue(LONDON.isValid)
    }

    private companion object {
        val LONDON = Coordinates(51.5074, -0.1278)
        val SYDNEY = Coordinates(-33.8688, 151.2093)

        /** 21 June 2026, 12:00 UTC — unambiguously daylight in London. */
        val LONDON_MIDDAY: Long =
            LocalDateTime.of(2026, 6, 21, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        /** 21 December 2026, 00:00 UTC — unambiguously night in London. */
        val LONDON_MIDNIGHT: Long =
            LocalDateTime.of(2026, 12, 21, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
