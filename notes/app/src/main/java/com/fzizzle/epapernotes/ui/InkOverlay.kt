package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.ink.Stroke

/**
 * The strokes written in this session, per page, so the page view can draw the ones a page
 * rendering doesn't contain yet.
 *
 * Every rendering remembers the page's [version] at the moment it was asked for: how many
 * strokes the page had then. The worker handles things in order, so the rendering contains
 * exactly those. Anything [newerThan] that is drawn on top. This holds for any rendering,
 * including an older one kept from before some strokes, at another zoom.
 */
class InkOverlay {
    private val strokes = mutableMapOf<Int, MutableList<Stroke>>()

    fun add(page: Int, stroke: Stroke) {
        strokes.getOrPut(page) { mutableListOf() }.add(stroke)
    }

    /** How many strokes [page] has had this session. */
    fun version(page: Int): Int = strokes[page]?.size ?: 0

    /** The strokes added to [page] after [version]. */
    fun newerThan(page: Int, version: Int): List<Stroke> = strokes[page]?.drop(version).orEmpty()
}
