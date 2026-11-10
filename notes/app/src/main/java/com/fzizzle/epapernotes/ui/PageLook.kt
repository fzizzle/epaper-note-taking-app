package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.pow

/**
 * How much darker the page is drawn on screen (decision 2): pale slide text is hard to read
 * on the panel. Only the page's picture on screen changes; the PDF, snips and thumbnails never do.
 * A gamma curve: white stays white and black stays black, and the greys between go darker.
 */
enum class Contrast(val gamma: Float) { NORMAL(1f), STRONGER(1.5f), STRONGEST(2.2f) }

/** The contrast step as plain arithmetic on pixels, so it can be tested without Android. */
object PageLook {

    /** For each level 0–255, the level it is drawn at: 255 × (level / 255) ^ gamma. */
    fun table(contrast: Contrast): IntArray =
        IntArray(256) { Math.round(255 * (it / 255.0).pow(contrast.gamma.toDouble())).toInt() }

    /**
     * Passes the red, green and blue of the first [count] ARGB [pixels] through [table], all
     * three alike so a colour keeps its hue (the highlighter stays yellow). Alpha is left alone.
     */
    fun apply(pixels: IntArray, table: IntArray, count: Int = pixels.size) {
        for (i in 0 until count) {
            val c = pixels[i]
            pixels[i] = (c and ALPHA) or
                (table[(c shr 16) and 0xFF] shl 16) or
                (table[(c shr 8) and 0xFF] shl 8) or
                table[c and 0xFF]
        }
    }

    private const val ALPHA = 0xFF shl 24
}

/**
 * Applies [contrast] to a fresh page rendering, in place, once, on the document's worker
 * before it is shown: never per redraw. A colour filter on the paint can't draw a gamma curve
 * and would run on every redraw. Read a band of rows at a time, so no second full-size copy of
 * the pixels is held (a whole page is about 18 MB).
 */
fun PageLook.applyTo(bitmap: Bitmap, contrast: Contrast) {
    if (contrast == Contrast.NORMAL) return
    val table = table(contrast)
    val w = bitmap.width
    val h = bitmap.height
    val band = IntArray(w * min(BAND_ROWS, h))
    var y = 0
    while (y < h) {
        val rows = min(BAND_ROWS, h - y) // the last band may be shorter
        bitmap.getPixels(band, 0, w, 0, y, w, rows)
        apply(band, table, w * rows)
        bitmap.setPixels(band, 0, w, 0, y, w, rows)
        y += rows
    }
}

private const val BAND_ROWS = 64
