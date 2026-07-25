package com.aidley.uvwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UvGraphWindowTest {

    private val span = UvGraphWindow.SPAN_MILLIS
    private val hour = 60L * 60L * 1000L

    /** Daylight from [fromHour] to [toHour] of each day, in hours since the epoch. */
    private fun daylight(fromHour: Long, toHour: Long): (Long) -> Boolean = { at ->
        val hourOfDay = (at / hour) % 24
        hourOfDay >= fromHour && hourOfDay < toHour
    }

    @Test
    fun `keeps now inside the middle half of the window`() {
        // Sweep a whole day: whatever the daylight looks like, the edge rule must hold.
        for (nowHour in 0 until 24) {
            val now = nowHour * hour
            val start = UvGraphWindow.chooseStart(now, span, daylight(6, 20))
            val position = (now - start).toDouble() / span
            assertTrue(
                "now at $position of the width, starting from hour $nowHour",
                position >= UvGraphWindow.EDGE_FRACTION - 1e-9 &&
                    position <= 1.0 - UvGraphWindow.EDGE_FRACTION + 1e-9
            )
        }
    }

    @Test
    fun `shifts the window to take in more daylight`() {
        // 06:00, with daylight from 06:00 to 20:00: everything before now is dark, so the window
        // should sit as late as the edge rule allows to pick up the day ahead.
        val now = 6 * hour
        val start = UvGraphWindow.chooseStart(now, span, daylight(6, 20))
        assertEquals(now - (span * UvGraphWindow.EDGE_FRACTION).toLong(), start)
    }

    @Test
    fun `shifts the other way when the daylight is behind`() {
        // 19:00, daylight ending at 20:00: most of what is worth showing has already happened.
        val now = 19 * hour
        val start = UvGraphWindow.chooseStart(now, span, daylight(6, 20))
        assertEquals(now - (span - (span * UvGraphWindow.EDGE_FRACTION).toLong()), start)
    }

    @Test
    fun `centres now when daylight gives no reason to prefer either side`() {
        val now = 12 * hour
        val start = UvGraphWindow.chooseStart(now, span) { true }
        assertEquals(now - span / 2, start)
    }

    @Test
    fun `centres now through polar night when there is no daylight at all`() {
        val now = 12 * hour
        val start = UvGraphWindow.chooseStart(now, span) { false }
        assertEquals(now - span / 2, start)
    }

    @Test
    fun `window always covers now`() {
        for (nowHour in 0 until 24) {
            val now = nowHour * hour
            val start = UvGraphWindow.chooseStart(now, span, daylight(9, 17))
            assertTrue(now >= start)
            assertTrue(now <= start + span)
        }
    }
}
