package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.transformed
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Copied items (PDF space of the page they came from) and that page's PDF-to-display transform. */
class Clipboard(val items: List<Item>, val sourceToDisplay: Affine)

/**
 * The copies to add to the target page when pasting at [at] (display points of that page).
 * [displayToPdf] is the target page's display-to-PDF transform. Items keep their display size,
 * are centred on [at], and get fresh ids.
 */
fun Clipboard.placedOn(displayToPdf: Affine, at: Pt): List<Item> {
    if (items.isEmpty()) return emptyList()
    val corners = items.flatMap { listOf(Pt(it.box.x0, it.box.y0), Pt(it.box.x1, it.box.y1)) }.map { sourceToDisplay.map(it) }
    val centre = Pt((corners.minOf { it.x } + corners.maxOf { it.x }) / 2, (corners.minOf { it.y } + corners.maxOf { it.y }) / 2)
    val move = sourceToDisplay.then(Affine.translate(at.x - centre.x, at.y - centre.y)).then(displayToPdf)
    val turns = Math.floorMod(quarterTurnsOf(displayToPdf.inverse()) - quarterTurnsOf(sourceToDisplay), 4)
    val scale = move.uniformScale()
    return items.map { item ->
        val moved = item.transformed(move, scale)
        when (moved) {
            is InkItem -> moved.copy(id = ItemIds.next())
            is ImageItem -> moved.copy(id = ItemIds.next(), quarterTurns = (moved.quarterTurns + turns) % 4)
        }
    }
}

/**
 * Duplicate: copies of [items] with fresh ids, [byPt] points right and down of the originals as
 * the page is shown ([pdfToDisplay]; the page is [pageWidth] by [pageHeight] points as shown),
 * so the copy is seen beside what it was made from. Less far, or not at all, in a direction
 * where that would put it off the page.
 */
fun duplicateOf(items: List<Item>, pdfToDisplay: Affine, pageWidth: Float, pageHeight: Float, byPt: Float = DUPLICATE_BY_PT): List<Item> {
    if (items.isEmpty()) return emptyList()
    val corners = items.flatMap { listOf(Pt(it.box.x0, it.box.y0), Pt(it.box.x1, it.box.y1)) }.map { pdfToDisplay.map(it) }
    val left = corners.minOf { it.x }
    val right = corners.maxOf { it.x }
    val top = corners.minOf { it.y }
    val bottom = corners.maxOf { it.y }
    val dx = byPt.coerceAtMost(pageWidth - right).coerceAtLeast(0f)
    val dy = byPt.coerceAtMost(pageHeight - bottom).coerceAtLeast(0f)
    // A paste onto the same page, centred that much to the right and down.
    return Clipboard(items, pdfToDisplay).placedOn(pdfToDisplay.inverse(), Pt((left + right) / 2 + dx, (top + bottom) / 2 + dy))
}

/** How far a duplicate lands from its original: about 5 mm. */
const val DUPLICATE_BY_PT = 14f

/**
 * The page rotation, in clockwise quarter turns, of a PDF-to-display transform. Read it from where
 * PDF's +x axis lands on the display: the y-up/y-down flip only affects the y axis, and since
 * display y points down, the angle atan2(b, a) is already clockwise. Unrotated page: 0; MuPDF's
 * [0 1 1 0 0 0] for /Rotate 90: 1.
 */
private fun quarterTurnsOf(pdfToDisplay: Affine): Int =
    Math.floorMod((atan2(pdfToDisplay.b, pdfToDisplay.a) / (PI / 2)).roundToInt(), 4)
