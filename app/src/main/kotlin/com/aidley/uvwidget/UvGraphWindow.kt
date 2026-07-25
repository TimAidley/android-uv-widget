package com.aidley.uvwidget

import kotlin.math.abs

/**
 * Chooses which twelve hours the graph shows.
 *
 * Twelve hours is less than a day, so the window has to be placed. Two rules decide it: show as
 * much daylight as possible, since the dark hours are a flat line at zero and tell the user
 * nothing; but never push "now" into the outer quarter of the graph, because a line hard against
 * an edge leaves no room to read what is about to happen.
 *
 * The second rule wins where they conflict — it is expressed as the range of starts that are even
 * considered, so daylight is only ever optimised within it.
 */
object UvGraphWindow {

    /** How much time the graph spans. */
    const val SPAN_MILLIS = 12L * 60L * 60L * 1000L

    /** How close to either edge the "now" line may come, as a fraction of the width. */
    const val EDGE_FRACTION = 0.25

    /** Granularity of both the candidate starts and the daylight scoring. */
    private const val STEPS_PER_SPAN = 48 // 15 minutes at a twelve-hour span

    /**
     * Start of the window to draw, as epoch millis.
     *
     * [isDaylight] is asked about instants across each candidate window; the one containing the
     * most daylight wins, ties going to whichever keeps "now" closest to the middle.
     */
    fun chooseStart(
        nowMillis: Long,
        spanMillis: Long = SPAN_MILLIS,
        isDaylight: (Long) -> Boolean
    ): Long {
        val step = (spanMillis / STEPS_PER_SPAN).coerceAtLeast(1L)

        // "now" must sit between EDGE_FRACTION and 1 - EDGE_FRACTION across the window, which
        // pins the start to this range and nowhere else.
        val earliest = nowMillis - (spanMillis - (spanMillis * EDGE_FRACTION).toLong())
        val latest = nowMillis - (spanMillis * EDGE_FRACTION).toLong()
        val centred = nowMillis - spanMillis / 2

        var bestStart = centred
        var bestDaylight = -1
        var bestOffCentre = Long.MAX_VALUE

        var start = earliest
        while (start <= latest) {
            var daylight = 0
            var at = start
            while (at < start + spanMillis) {
                if (isDaylight(at)) daylight++
                at += step
            }

            val offCentre = abs(start - centred)
            if (daylight > bestDaylight || (daylight == bestDaylight && offCentre < bestOffCentre)) {
                bestDaylight = daylight
                bestOffCentre = offCentre
                bestStart = start
            }
            start += step
        }

        return bestStart
    }
}
