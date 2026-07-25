package com.aidley.uvwidget

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

/**
 * Fetches the hourly UV forecast from Open-Meteo's air-quality API.
 *
 * Open-Meteo needs no API key and no registration, so there is nothing to configure and no
 * secret to keep out of the repository. `timeformat=unixtime` makes the API return UTC epoch
 * seconds, which avoids parsing local-time strings across daylight-saving boundaries.
 */
object OpenMeteoClient {

    private const val TAG = "OpenMeteoClient"
    private const val ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality"
    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 15_000

    /**
     * Blocking network call — callers must already be off the main thread.
     *
     * Returns null on any network or parsing failure; the caller keeps showing cached data
     * rather than blanking the widget over a transient error.
     */
    fun fetchUvForecast(location: Coordinates, nowMillis: Long): UvForecast? {
        val url = buildUrl(location)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "Open-Meteo returned HTTP $status")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseForecast(body, location, nowMillis)
        } catch (e: IOException) {
            Log.w(TAG, "UV fetch failed", e)
            null
        } catch (e: RuntimeException) {
            // org.json throws JSONException (a RuntimeException on Android) for malformed bodies.
            Log.w(TAG, "UV response could not be parsed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Two days of hourly values starting at 00:00 UTC today, which always brackets "now" and
     * leaves usable data if a refresh is missed overnight.
     */
    internal fun buildUrl(location: Coordinates): String = String.format(
        Locale.US,
        "%s?latitude=%.4f&longitude=%.4f&hourly=uv_index&timeformat=unixtime&forecast_days=2",
        ENDPOINT,
        location.latitude,
        location.longitude
    )

    /** Visible for testing: turns an Open-Meteo response body into a forecast. */
    internal fun parseForecast(body: String, location: Coordinates, nowMillis: Long): UvForecast? {
        val hourly = JSONObject(body).optJSONObject("hourly") ?: return null
        val times = hourly.optJSONArray("time") ?: return null
        val values = hourly.optJSONArray("uv_index") ?: return null

        val count = minOf(times.length(), values.length())
        if (count == 0) return null

        // Open-Meteo sends null for hours it has no value for; drop those rather than
        // reading them as zero, which would paint the widget green in the middle of the day.
        val keptTimes = ArrayList<Long>(count)
        val keptValues = ArrayList<Double>(count)
        for (i in 0 until count) {
            if (values.isNull(i) || times.isNull(i)) continue
            keptTimes += times.getLong(i)
            keptValues += values.getDouble(i)
        }
        if (keptTimes.isEmpty()) return null

        return UvForecast(
            latitude = location.latitude,
            longitude = location.longitude,
            fetchedAtMillis = nowMillis,
            hourTimesSeconds = keptTimes.toLongArray(),
            uvIndexPerHour = keptValues.toDoubleArray()
        )
    }
}
