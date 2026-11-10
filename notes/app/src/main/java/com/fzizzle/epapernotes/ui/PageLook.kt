package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How much darker the page is drawn on screen (decision 2): pale slide text is hard to read
 * on the panel. Only the page's picture on screen changes; the PDF, snips and thumbnails never do.
 * A gamma curve: white stays white and black stays black, and the greys between go darker.
 */
enum class Contrast(val gamma: Float) { NORMAL(1f), STRONGER(1.5f), STRONGEST(2.2f) }

/**
 * The page's look on screen as plain arithmetic on pixels, so it can be tested without Android:
 * the contrast step, and "Dark text as black".
 */
object PageLook {

    /** For each level 0–255, the level it is drawn at: 255 × (level / 255) ^ gamma. */
    fun table(contrast: Contrast): IntArray =
        IntArray(256) { Math.round(255 * (it / 255.0).pow(contrast.gamma.toDouble())).toInt() }

    /**
     * Passes the red, green and blue of the first [count] ARGB [pixels] through [table], all
     * three alike so a colour keeps its hue (the highlighter stays yellow). Alpha is left alone.
     *
     * With [darkTextAsBlack], a nearly grey pixel is first made neutral grey of its lightness and
     * darkened, so tinted dark text turns black: the colour screen shows grey at 300 ppi but
     * colour only at 150 ppi, so near-black slate text looks blurry while it keeps its tint.
     * Clearly coloured pixels (formulas, the highlighter) keep their colour.
     */
    fun apply(
        pixels: IntArray,
        table: IntArray,
        count: Int = pixels.size,
        darkTextAsBlack: Boolean = false,
        grey: IntArray? = if (darkTextAsBlack) greyTable(table) else null,
    ) {
        // Most of a page is runs of one colour (white paper, solid text), so the last pixel's
        // answer is kept: a repeat costs a comparison. Any value works to start, since it's computed.
        var lastIn = pixels.firstOrNull()?.inv() ?: 0
        var lastOut = 0
        for (i in 0 until count) {
            val c = pixels[i]
            if (c != lastIn) {
                lastIn = c
                lastOut = look(c, table, grey)
            }
            pixels[i] = lastOut
        }
    }

    /**
     * For [apply]'s dark text step: a nearly grey pixel's lightness, darkened and then through
     * the contrast [table], in one lookup. Made once per picture by [applyTo].
     */
    fun greyTable(table: IntArray): IntArray = IntArray(256) { table[darkened(it)] }

    /** One pixel: dark text as black if [grey] is given, then the contrast [table]. */
    private fun look(c: Int, table: IntArray, grey: IntArray?): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return if (grey != null && max(r, max(g, b)) - min(r, min(g, b)) <= NEARLY_GREY) {
            val v = grey[(299 * r + 587 * g + 114 * b) / 1000]
            (c and ALPHA) or (v shl 16) or (v shl 8) or v
        } else {
            (c and ALPHA) or (table[r] shl 16) or (table[g] shl 8) or table[b]
        }
    }

    /** Lightness [v] stretched so everything at or below [DARK] is black and white stays white. */
    private fun darkened(v: Int): Int = ((v - DARK) * 255 / (255 - DARK)).coerceIn(0, 255)

    private const val ALPHA = 0xFF shl 24

    // At most this far between a pixel's strongest and weakest channel, it is treated as grey:
    // the workbook's slate text (43, 47, 64) is 21; its green and blue formulas are 100 or more.
    private const val NEARLY_GREY = 40

    // A grey this dark or darker is drawn black.
    private const val DARK = 96
}

/**
 * Applies [contrast] and [darkTextAsBlack] to a fresh page rendering, in place, once, on the
 * document's worker before it is shown: never per redraw. A colour filter on the paint can't
 * draw a gamma curve and would run on every redraw. Read a band of rows at a time, so no second
 * full-size copy of the pixels is held (a whole page is about 18 MB).
 */
fun PageLook.applyTo(bitmap: Bitmap, contrast: Contrast, darkTextAsBlack: Boolean) {
    if (contrast == Contrast.NORMAL && !darkTextAsBlack) return
    val table = table(contrast)
    val grey = if (darkTextAsBlack) greyTable(table) else null
    val w = bitmap.width
    val h = bitmap.height
    val band = IntArray(w * min(BAND_ROWS, h))
    var y = 0
    while (y < h) {
        val rows = min(BAND_ROWS, h - y) // the last band may be shorter
        bitmap.getPixels(band, 0, w, 0, y, w, rows)
        apply(band, table, w * rows, darkTextAsBlack, grey)
        bitmap.setPixels(band, 0, w, 0, y, w, rows)
        y += rows
    }
}

private const val BAND_ROWS = 64
