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
 * Any other change (an erase, a move, a paste) may have removed or moved strokes this overlay
 * holds, so it can't be drawn on top of an older rendering. Renderings from before it are
 * [isStale]: still drawn, since an old page is better than a blank one on e-ink, while a fresh
 * one is asked for. Only the strokes written after the change go on top of them.
 */
class InkOverlay {
    private class PageInk {
        var version = 0
        var changedAt = 0

        /** Each stroke since the last change, with the version it brought. */
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
        // A rendering asked for from now on contains them, and a stale one mustn't draw them.
        p.strokes.clear()
    }

    /** The page's version now: what a rendering asked for now will contain. */
    fun version(page: Int): Int = pages[page]?.version ?: 0

    /** True when a rendering made at [version] is from before the page's last [changed]. */
    fun isStale(page: Int, version: Int): Boolean = version < (pages[page]?.changedAt ?: 0)

    /** The strokes since the last [changed] that a rendering made at [version] doesn't have. */
    fun newerThan(page: Int, version: Int): List<Stroke> =
        pages[page]?.strokes?.filter { it.first > version }?.map { it.second }.orEmpty()
}
