package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Item

/**
 * A tap with the lasso selects what's under it (owner, 2026-11-12): quicker than drawing a
 * loop round one word. All in PDF space, like [Lasso] and [Eraser].
 */
object TapSelect {
    /** A lasso stroke no bigger than this both ways (about 2 mm) is a tap, not a loop. */
    const val TAP_SPAN_PT = 6f

    fun isTap(loop: List<Pt>): Boolean {
        if (loop.isEmpty()) return false
        return loop.maxOf { it.x } - loop.minOf { it.x } < TAP_SPAN_PT && loop.maxOf { it.y } - loop.minOf { it.y } < TAP_SPAN_PT
    }

    /**
     * The item under the tap [loop]'s middle: the top one where several lie over each other
     * (the last in drawing order), as a list of one, or none. "Under" is the eraser's reach
     * ([Eraser.RADIUS_PT]), so what a tap selects is what a tap would erase.
     */
    fun pick(items: List<Item>, loop: List<Pt>): List<Item> {
        if (loop.isEmpty()) return emptyList()
        val middle = Pt((loop.minOf { it.x } + loop.maxOf { it.x }) / 2, (loop.minOf { it.y } + loop.maxOf { it.y }) / 2)
        return listOfNotNull(Eraser.hits(items, listOf(middle)).lastOrNull())
    }
}
