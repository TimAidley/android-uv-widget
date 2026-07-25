package com.aidley.uvwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parsing is where a data widget usually goes wrong, so the response shapes are pinned here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenMeteoClientTest {

    private val london = Coordinates(51.5074, -0.1278)

    @Test
    fun `url asks for unix timestamps and two days of hourly uv`() {
        val url = OpenMeteoClient.buildUrl(london)
        assertTrue(url, url.startsWith("https://air-quality-api.open-meteo.com/v1/air-quality?"))
        assertTrue(url, url.contains("latitude=51.5074"))
        assertTrue(url, url.contains("longitude=-0.1278"))
        assertTrue(url, url.contains("hourly=uv_index"))
        assertTrue(url, url.contains("timeformat=unixtime"))
        assertTrue(url, url.contains("forecast_days=2"))
    }

    @Test
    fun `parses a well formed response`() {
        val forecast = OpenMeteoClient.parseForecast(RESPONSE, london, NOW)
        assertNotNull(forecast)
        requireNotNull(forecast)
        assertEquals(4, forecast.hourTimesSeconds.size)
        assertEquals(0.0, forecast.uvIndexPerHour[0], 0.0001)
        assertEquals(3.2, forecast.uvIndexPerHour[2], 0.0001)
        assertEquals(5.6, forecast.uvIndexPerHour[3], 0.0001)
        assertEquals(1_767_222_000L, forecast.hourTimesSeconds[0])
        assertEquals(NOW, forecast.fetchedAtMillis)
    }

    @Test
    fun `skips hours the api has no value for`() {
        val forecast = OpenMeteoClient.parseForecast(RESPONSE_WITH_NULLS, london, NOW)
        requireNotNull(forecast)
        // The null hour is dropped rather than read as zero, which would look like "no UV".
        assertEquals(2, forecast.hourTimesSeconds.size)
        assertEquals(listOf(1_767_225_600L, 1_767_232_800L), forecast.hourTimesSeconds.toList())
    }

    @Test
    fun `returns null for responses that are not forecasts`() {
        assertNull(OpenMeteoClient.parseForecast("{}", london, NOW))
        assertNull(OpenMeteoClient.parseForecast("""{"hourly":{}}""", london, NOW))
        assertNull(OpenMeteoClient.parseForecast("""{"error":true,"reason":"bad"}""", london, NOW))
        assertNull(OpenMeteoClient.parseForecast("""{"hourly":{"time":[],"uv_index":[]}}""", london, NOW))
    }

    @Test
    fun `interpolates between hourly values`() {
        val forecast = requireNotNull(OpenMeteoClient.parseForecast(RESPONSE, london, NOW))
        // Halfway between the 3.2 and 5.6 samples.
        val midpointMillis = (1_767_229_200L + 1_767_232_800L) / 2 * 1000L
        assertEquals(4.4, requireNotNull(forecast.uvIndexAt(midpointMillis)), 0.0001)
    }

    @Test
    fun `returns null outside the forecast window`() {
        val forecast = requireNotNull(OpenMeteoClient.parseForecast(RESPONSE, london, NOW))
        assertNull(forecast.uvIndexAt(1_000_000_000_000L))
        assertNull(forecast.uvIndexAt(1_900_000_000_000L))
    }

    @Test
    fun `staleness and expiry are measured from the fetch time`() {
        val forecast = requireNotNull(OpenMeteoClient.parseForecast(RESPONSE, london, NOW))
        assertTrue(forecast.isStale(NOW + UvForecast.STALE_AFTER_MILLIS + 1))
        assertTrue(!forecast.isStale(NOW + 60_000))
        assertTrue(forecast.isExpired(NOW + UvForecast.EXPIRED_AFTER_MILLIS + 1))
        assertTrue(!forecast.isExpired(NOW + UvForecast.STALE_AFTER_MILLIS + 1))
    }

    private companion object {
        const val NOW = 1_767_229_200_000L

        // Shapes taken from the documented Open-Meteo air-quality response with
        // timeformat=unixtime: hourly.time holds UTC epoch seconds.
        val RESPONSE = """
            {
              "latitude": 51.5,
              "longitude": -0.12,
              "generationtime_ms": 0.12,
              "utc_offset_seconds": 0,
              "hourly_units": {"time": "unixtime", "uv_index": ""},
              "hourly": {
                "time": [1767222000, 1767225600, 1767229200, 1767232800],
                "uv_index": [0.0, 1.4, 3.2, 5.6]
              }
            }
        """.trimIndent()

        val RESPONSE_WITH_NULLS = """
            {
              "hourly": {
                "time": [1767222000, 1767225600, 1767229200, 1767232800],
                "uv_index": [null, 1.4, null, 5.6]
              }
            }
        """.trimIndent()
    }
}
