package com.aidley.uvwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotoneCurveTest {

    @Test
    fun `passes through the given points`() {
        val curve = MonotoneCurve.through(
            doubleArrayOf(0.0, 1.0, 2.0, 3.0),
            doubleArrayOf(0.0, 2.0, 5.0, 1.0)
        )!!
        assertEquals(0.0, curve.valueAt(0.0)!!, 1e-9)
        assertEquals(2.0, curve.valueAt(1.0)!!, 1e-9)
        assertEquals(5.0, curve.valueAt(2.0)!!, 1e-9)
        assertEquals(1.0, curve.valueAt(3.0)!!, 1e-9)
    }

    @Test
    fun `does not dip below zero leaving a flat night`() {
        // The shape around sunrise that makes a plain cubic spline undershoot.
        val curve = MonotoneCurve.through(
            doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0),
            doubleArrayOf(0.0, 0.0, 0.0, 3.0, 6.0)
        )!!
        var x = 0.0
        while (x <= 4.0) {
            assertTrue("negative at $x", curve.valueAt(x)!! >= 0.0)
            x += 0.01
        }
    }

    @Test
    fun `stays monotone between rising points`() {
        val curve = MonotoneCurve.through(
            doubleArrayOf(0.0, 1.0, 2.0, 3.0),
            doubleArrayOf(0.0, 1.0, 6.0, 7.0)
        )!!
        var previous = curve.valueAt(0.0)!!
        var x = 0.01
        while (x <= 3.0) {
            val value = curve.valueAt(x)!!
            assertTrue("fell at $x", value >= previous - 1e-9)
            previous = value
            x += 0.01
        }
    }

    @Test
    fun `is null outside the known points`() {
        val curve = MonotoneCurve.through(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 2.0))!!
        assertNull(curve.valueAt(-0.1))
        assertNull(curve.valueAt(1.1))
    }

    @Test
    fun `needs at least two points`() {
        assertNull(MonotoneCurve.through(doubleArrayOf(1.0), doubleArrayOf(1.0)))
        assertNull(MonotoneCurve.through(doubleArrayOf(), doubleArrayOf()))
    }

    @Test
    fun `rejects points that do not advance`() {
        assertNull(MonotoneCurve.through(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 2.0)))
    }

    @Test
    fun `peak finds the high point between the hours`() {
        val curve = MonotoneCurve.through(
            doubleArrayOf(0.0, 1.0, 2.0),
            doubleArrayOf(0.0, 8.0, 0.0)
        )!!
        assertTrue(curve.peakBetween(0.0, 2.0) >= 8.0)
    }

    @Test
    fun `builds from a forecast`() {
        val forecast = UvForecast(
            latitude = 51.5,
            longitude = -0.1,
            fetchedAtMillis = 0L,
            hourTimesSeconds = longArrayOf(0L, 3600L, 7200L),
            uvIndexPerHour = doubleArrayOf(0.0, 3.0, 5.0)
        )
        val curve = MonotoneCurve.from(forecast)
        assertNotNull(curve)
        assertEquals(3.0, curve!!.valueAt(3600.0)!!, 1e-9)
    }
}
