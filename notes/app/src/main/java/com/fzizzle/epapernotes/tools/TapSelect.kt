package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Item

/**
 * A tap with the lasso selects what's under it (owner, 2026-11-12): quicker than drawing a
 * loop round one word. All in PDF space, like [Lasso] and [Eraser].
 */
object TapSelect {
    /** A lasso stroke no bigger than this both ways is a tap, not a loop: in PDF points, about 2 mm on the page. */
    const val TAP_SPAN_PT = 6f

    /**
     * The same on the screen, in its pixels (2 mm at the tablet's 300 to the inch). The
     * writing screen measures here, not on the page: zoomed in, a small loop drawn on purpose
     * is only a point or two across on the page, and must not be taken for a tap.
     */
    const val TAP_SPAN_PX = 24f

    /** How near a tap must be to a stroke to pick it, on the screen: 1.5 mm, whatever the zoom. */
    const val TAP_REACH_PX = 18f

    /** [span] is in whatever [loop] is measured in. */
    fun isTap(loop: List<Pt>, span: Float = TAP_SPAN_PT): Boolean {
        if (loop.isEmpty()) return false
        return loop.maxOf { it.x } - loop.minOf { it.x } < span && loop.maxOf { it.y } - loop.minOf { it.y } < span
    }

    /**
     * The item under the tap [loop]'s middle: the top one where several lie over each other
     * (the last in drawing order), as a list of one, or none. "Under" is within [reachPt] of
     * it: the eraser's reach unless the caller gives its own.
     */
    fun pick(items: List<Item>, loop: List<Pt>, reachPt: Float = Eraser.RADIUS_PT): List<Item> {
        if (loop.isEmpty()) return emptyList()
        val middle = Pt((loop.minOf { it.x } + loop.maxOf { it.x }) / 2, (loop.minOf { it.y } + loop.maxOf { it.y }) / 2)
        return listOfNotNull(Eraser.hits(items, listOf(middle), reachPt).lastOrNull())
    }
}
