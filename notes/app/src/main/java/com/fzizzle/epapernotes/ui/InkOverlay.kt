package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.ink.Stroke

/**
 * The strokes written in this session, per page, so the page view can draw the ones a page
 * rendering doesn't contain yet.
 *
 * Every rendering remembers the page's [version] at the moment it was asked for. The version
 * goes up by one for each new stroke and for each other [changed]. The worker handles things
 * in order, so the rendering contains everything up to that version. Anything [newerThan] that
 * is drawn on top. This holds for any rendering, including an older one kept from before some
 * strokes, at another zoom.
 *
 * Any other change (an erase, a move, a paste) makes renderings from before it [isStale]: a
 * fresh one is asked for. A stale one is still drawn meanwhile, since an old page is better than
 * a blank one on e-ink, and it still gets every stroke it lacks on top, in the place it had
 * before the change, just like the stale picture's own ink. Dropping them would make this
 * session's writing vanish until the fresh rendering arrives (the owner's plan-2 report).
 */
class InkOverlay {
    private class PageInk {
        var version = 0
        var changedAt = 0

        /** Each stroke with the version it brought. */
        val strokes = mutableListOf<Pair<Int, Stroke>>()
    }

    private val pages = mutableMapOf<Int, PageInk>()

    fun add(page: Int, stroke: Stroke) {
        val p = pages.getOrPut(page) { PageInk() }
        p.version++
        p.strokes += p.version to stroke
    }

    /**
     * [page] changed in some way other than a new stroke, and the change has reached the PDF:
     * renderings asked for from now on show it, and older ones are stale.
     */
    fun changed(page: Int) {
        val p = pages.getOrPut(page) { PageInk() }
        p.version++
        p.changedAt = p.version
    }

    /**
     * The page view left [page] and threw its renderings away, so the next one is asked for
     * at the version now: strokes from before the last [changed] can no longer be missing.
     */
    fun left(page: Int) {
        val p = pages[page] ?: return
        p.strokes.removeAll { it.first < p.changedAt }
    }

    /**
     * Forgets every page: a page was added or removed, so the pages after it have new
     * numbers, and every stroke held here has just been saved into the PDF.
     */
    fun clear() = pages.clear()

    /** The page's version now: what a rendering asked for now will contain. */
    fun version(page: Int): Int = pages[page]?.version ?: 0

    /** True when a rendering made at [version] is from before the page's last [changed]. */
    fun isStale(page: Int, version: Int): Boolean = version < (pages[page]?.changedAt ?: 0)

    /** The strokes a rendering made at [version] doesn't have. */
    fun newerThan(page: Int, version: Int): List<Stroke> =
        pages[page]?.strokes?.filter { it.first > version }?.map { it.second }.orEmpty()

    /**
     * How many strokes a rendering made at [sinceVersion] doesn't have: how many the page view
     * draws by hand each time it redraws. Without a version, every stroke held for the page.
     */
    fun count(page: Int, sinceVersion: Int = 0): Int = pages[page]?.strokes?.count { it.first > sinceVersion } ?: 0

    companion object {
        /**
         * Past this many strokes drawn by hand on top of a page's picture, the page view asks
         * for a fresh picture with them in it (PageView.bakeWhenIdle), so a redraw of a full
         * page of writing doesn't get slower and slower.
         */
        const val BAKE_AFTER = 150
    }
}
