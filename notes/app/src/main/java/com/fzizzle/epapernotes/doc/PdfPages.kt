package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Item
import java.io.Closeable
import java.io.File

/**
 * The parts of an open PDF the document session needs. PdfFile is the real one. Tests use a
 * fake, so the saving rules can be checked on the laptop without MuPDF.
 */
interface PdfPages : Closeable {
    val pageCount: Int
    val wasRepaired: Boolean

    /** The app's own items on the page, in drawing order. Annotations from other apps are left out. */
    fun items(page: Int): List<Item>

    /**
     * Writes [change] into the document. An id that isn't found is skipped, which makes a
     * replay after a half-finished save harmless. Never touches an annotation the app didn't make.
     */
    fun apply(change: Change)

    /**
     * Draws again, in place, up to [limit] of the app's strokes on [page] that an earlier
     * version saved with a faulty drawing (mirrored in its box, or with holes at its ends). Ids and drawing order stay the
     * same. A stroke that can't be redrawn is skipped, and not tried again while the file is
     * open. [InkRedraw.redrawn] > 0 means the file needs saving even though nothing was journaled.
     */
    fun redrawFaultyInk(page: Int, limit: Int): InkRedraw

    /** Whether [page] holds any of the app's own items, without decoding them: cheap enough to ask of every page. */
    fun hasOwnItems(page: Int): Boolean

    /** The page's rotation: 0, 90, 180 or 270. */
    fun rotation(page: Int): Int

    fun saveTo(target: File)
}

/** What one [PdfPages.redrawFaultyInk] did: how many strokes it drew again, and whether the page has more to try. */
data class InkRedraw(val redrawn: Int, val more: Boolean)
