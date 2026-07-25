package com.aidley.uvwidget

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Solar position from the NOAA solar calculation equations.
 *
 * The widget needs to know whether the sun is above the horizon so it can show the night
 * colour. Working that out locally rather than asking the network for sunrise/sunset keeps
 * the widget correct even when it is showing cached UV data, and costs one cheap calculation.
 *
 * Accurate to a minute or two of published sunrise/sunset times, which is far better than the
 * widget's refresh interval needs.
 */
object SolarPosition {

    /** Standard altitude of the sun's centre at sunrise/sunset: refraction plus the solar radius. */
    const val HORIZON_DEGREES = -0.833

    /** Elevation of the centre of the sun above the horizon, in degrees. */
    fun elevationDegrees(epochMillis: Long, latitude: Double, longitude: Double): Double {
        // Julian day, then Julian centuries since J2000.0.
        val julianDay = epochMillis / 86_400_000.0 + 2_440_587.5
        val t = (julianDay - 2_451_545.0) / 36_525.0

        val meanLongitude = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val equationOfCentre =
            sin(meanAnomaly.rad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin((2 * meanAnomaly).rad) * (0.019993 - 0.000101 * t) +
                sin((3 * meanAnomaly).rad) * 0.000289

        val trueLongitude = meanLongitude + equationOfCentre
        val apparentLongitude =
            trueLongitude - 0.00569 - 0.00478 * sin((125.04 - 1934.136 * t).rad)

        val meanObliquity =
            23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = meanObliquity + 0.00256 * cos((125.04 - 1934.136 * t).rad)

        val declination = asin(sin(obliquity.rad) * sin(apparentLongitude.rad)).deg

        val varY = tan((obliquity / 2).rad).let { it * it }
        val equationOfTime = 4 * (
            varY * sin(2 * meanLongitude.rad) -
                2 * eccentricity * sin(meanAnomaly.rad) +
                4 * eccentricity * varY * sin(meanAnomaly.rad) * cos(2 * meanLongitude.rad) -
                0.5 * varY * varY * sin(4 * meanLongitude.rad) -
                1.25 * eccentricity * eccentricity * sin(2 * meanAnomaly.rad)
            ).deg

        val minutesIntoUtcDay = (epochMillis / 60_000.0).mod(1440.0)
        val trueSolarTime = (minutesIntoUtcDay + equationOfTime + 4 * longitude).mod(1440.0)
        val hourAngle = (trueSolarTime / 4.0 - 180.0).let { if (it < -180.0) it + 360.0 else it }

        val cosZenith = (
            sin(latitude.rad) * sin(declination.rad) +
                cos(latitude.rad) * cos(declination.rad) * cos(hourAngle.rad)
            ).coerceIn(-1.0, 1.0)

        return 90.0 - acos(cosZenith).deg
    }

    /** True when the sun is above the horizon, using the conventional sunrise/sunset altitude. */
    fun isSunUp(epochMillis: Long, latitude: Double, longitude: Double): Boolean =
        elevationDegrees(epochMillis, latitude, longitude) > HORIZON_DEGREES

    // `mod` (floored) rather than `%` (truncated) so angles stay positive for negative inputs.

    private val Double.rad: Double get() = this * PI_OVER_180
    private val Double.deg: Double get() = this / PI_OVER_180

    private const val PI_OVER_180 = Math.PI / 180.0
}
