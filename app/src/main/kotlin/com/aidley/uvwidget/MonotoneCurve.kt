package com.aidley.uvwidget

import kotlin.math.sqrt

/**
 * Monotone cubic interpolation through the hourly forecast points (Fritsch–Carlson).
 *
 * A plain cubic spline would overshoot: given hours reading 0, 0, 3 around sunrise it dips below
 * zero before climbing, drawing negative UV. The monotone variant limits the tangents so the
 * curve never turns back on itself between two points, which keeps the graph honest and keeps the
 * fill from spilling under the axis.
 *
 * Tangents are computed once up front because drawing samples the curve once per pixel column.
 */
class MonotoneCurve private constructor(
    private val xs: DoubleArray,
    private val ys: DoubleArray,
    private val tangents: DoubleArray
) {

    val firstX: Double get() = xs.first()
    val lastX: Double get() = xs.last()

    /** Interpolated value at [x], or null when [x] falls outside the known points. */
    fun valueAt(x: Double): Double? {
        if (x < xs.first() || x > xs.last()) return null

        var low = 0
        var high = xs.size - 1
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (xs[middle] <= x) low = middle else high = middle
        }

        val width = xs[high] - xs[low]
        if (width <= 0.0) return ys[low]

        // Hermite basis on the unit interval.
        val t = (x - xs[low]) / width
        val t2 = t * t
        val t3 = t2 * t
        val value = (2 * t3 - 3 * t2 + 1) * ys[low] +
            (t3 - 2 * t2 + t) * width * tangents[low] +
            (-2 * t3 + 3 * t2) * ys[high] +
            (t3 - t2) * width * tangents[high]

        // The UV index has no meaningful negative value, and neither does the area under it.
        return value.coerceAtLeast(0.0)
    }

    /** Highest value on the curve between [from] and [to], sampled at [steps] points. */
    fun peakBetween(from: Double, to: Double, steps: Int = 240): Double {
        var peak = 0.0
        for (i in 0..steps) {
            val x = from + (to - from) * i / steps
            valueAt(x)?.let { if (it > peak) peak = it }
        }
        return peak
    }

    companion object {

        /** Null when there are fewer than two usable points, which cannot describe a curve. */
        fun through(xs: DoubleArray, ys: DoubleArray): MonotoneCurve? {
            if (xs.size < 2 || xs.size != ys.size) return null

            val n = xs.size
            val secants = DoubleArray(n - 1)
            for (i in 0 until n - 1) {
                val run = xs[i + 1] - xs[i]
                if (run <= 0.0) return null // points must be strictly increasing in x
                secants[i] = (ys[i + 1] - ys[i]) / run
            }

            val tangents = DoubleArray(n)
            tangents[0] = secants[0]
            tangents[n - 1] = secants[n - 2]
            for (i in 1 until n - 1) {
                tangents[i] = (secants[i - 1] + secants[i]) / 2.0
            }

            // Fritsch–Carlson limiting: clamp tangents into the circle of radius 3 so the
            // interpolant stays monotone on every interval.
            for (i in 0 until n - 1) {
                if (secants[i] == 0.0) {
                    tangents[i] = 0.0
                    tangents[i + 1] = 0.0
                    continue
                }
                val alpha = tangents[i] / secants[i]
                val beta = tangents[i + 1] / secants[i]
                if (alpha < 0) tangents[i] = 0.0
                if (beta < 0) tangents[i + 1] = 0.0
                val sum = alpha * alpha + beta * beta
                if (sum > 9.0) {
                    val scale = 3.0 / sqrt(sum)
                    tangents[i] = scale * alpha * secants[i]
                    tangents[i + 1] = scale * beta * secants[i]
                }
            }

            return MonotoneCurve(xs, ys, tangents)
        }

        /** The forecast as a curve over epoch seconds, or null if it holds too few points. */
        fun from(forecast: UvForecast): MonotoneCurve? = through(
            DoubleArray(forecast.hourTimesSeconds.size) { forecast.hourTimesSeconds[it].toDouble() },
            forecast.uvIndexPerHour
        )
    }
}
