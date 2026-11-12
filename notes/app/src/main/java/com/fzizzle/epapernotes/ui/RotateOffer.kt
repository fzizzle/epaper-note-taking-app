package com.fzizzle.epapernotes.ui

/**
 * Whether to offer "Rotate all pages?" as a document opens (owner, 2026-11-12). Rotating is
 * usually done once per document, a deck of landscape slides turned to fit the way the tablet
 * is held (spec §6.4), so it's offered once, the first time the file is opened, and only when
 * it would plainly help.
 */
object RotateOffer {
    /** A page counts as sideways when it's at least this many times wider than tall. */
    const val SIDEWAYS_RATIO = 1.2f

    /**
     * True for a file not [openedBefore], on a screen taller than wide, most of whose pages
     * ([sizes], as shown) are sideways.
     */
    fun shouldOffer(sizes: List<SizePt>, viewWidthPx: Int, viewHeightPx: Int, openedBefore: Boolean): Boolean {
        if (openedBefore || sizes.isEmpty() || viewHeightPx <= viewWidthPx) return false
        return sizes.count { it.w >= it.h * SIDEWAYS_RATIO } * 2 > sizes.size
    }
}
