package com.fzizzle.epapernotes.ink

import java.util.Locale

/**
 * Turns a [Stroke] into text and back, so it can be kept inside the PDF annotation that
 * draws it. Standard ink annotations have no per-point pressure, so this text is how the app
 * gets its strokes back as editable strokes.
 *
 * Format: `pn2 <kind> <widthScale> <x>,<y>,<p> <x>,<y>,<p> …`, where kind is `pen` or `hl`.
 * It's plain text on purpose, so it can be read in a PDF inspector. `pn2` is a version tag.
 * Files written by plan 1 and 2 use `pn1 <kind> <points…>`, which is still read, as width 1.
 */
object StrokeCodec {
    /** The private key the text is stored under, in the annotation's dictionary. */
    const val KEY = "PNotesStroke"

    private const val VERSION = "pn2"
    private const val VERSION_1 = "pn1"

    fun encode(stroke: Stroke): String = buildString {
        append(VERSION).append(' ').append(kindName(stroke.kind))
        append(String.format(Locale.ROOT, " %.3f", stroke.widthScale))
        for (p in stroke.points) {
            // Locale.ROOT so the decimal point is always '.', whatever language the tablet uses.
            append(String.format(Locale.ROOT, " %.2f,%.2f,%.3f", p.x, p.y, p.pressure))
        }
    }

    /**
     * Returns null for anything it doesn't recognise. The caller treats that as a foreign annotation.
     *
     * Reads the text in one pass rather than splitting it and calling `toFloatOrNull` on each
     * number: that was 0.8 s of the 1 s a page of 1,500 strokes takes to read on the tablet,
     * paid at the first erase or lasso on a page (owner, 2026-11-10: the eraser was slow).
     * It gives exactly what the split version gave, down to the bit (StrokeCodecTest).
     */
    fun decode(text: String): Stroke? {
        val t = text.trim()
        val head = t.indexOf(' ').takeIf { it > 0 } ?: return null
        val version2 = t.regionMatches(0, VERSION, 0, head) && head == VERSION.length
        if (!version2 && !(t.regionMatches(0, VERSION_1, 0, head) && head == VERSION_1.length)) return null
        val kindEnd = t.indexOf(' ', head + 1).takeIf { it > 0 } ?: return null
        val kind = kindFor(t.substring(head + 1, kindEnd)) ?: return null
        var at = kindEnd + 1
        val widthScale = if (version2) {
            val end = t.indexOf(' ', at).takeIf { it > 0 } ?: return null
            parseNumber(t, at, end).also { at = end + 1 } ?: return null
        } else {
            1f
        }
        val points = ArrayList<StrokePoint>()
        while (true) {
            val end = t.indexOf(' ', at).let { if (it < 0) t.length else it }
            points += parsePoint(t, at, end) ?: return null
            if (end == t.length) break
            at = end + 1
        }
        return Stroke(kind, points, widthScale)
    }

    /** `x,y,p` in [text] from [start] to [end]. */
    private fun parsePoint(text: String, start: Int, end: Int): StrokePoint? {
        val c1 = text.indexOf(',', start).takeIf { it in start until end } ?: return null
        val c2 = text.indexOf(',', c1 + 1).takeIf { it in c1 + 1 until end } ?: return null
        if (text.indexOf(',', c2 + 1) in c2 + 1 until end) return null
        val x = parseNumber(text, start, c1) ?: return null
        val y = parseNumber(text, c1 + 1, c2) ?: return null
        val p = parseNumber(text, c2 + 1, end) ?: return null
        return StrokePoint(x, y, p)
    }

    /**
     * The number in [text] from [start] to [end]. The usual shape, `-123.45`, is read here:
     * the digits as a whole number divided by a power of ten, both exact as floats, so the
     * division gives the correctly rounded value, just as `toFloat` would. Anything else
     * (an exponent, a leading `+` or `.`, too many digits) goes to `toFloatOrNull`.
     */
    private fun parseNumber(text: String, start: Int, end: Int): Float? {
        var i = start
        val negative = i < end && text[i] == '-'
        if (negative) i++
        var whole = 0
        var digits = 0
        var decimals = -1
        while (i < end) {
            val c = text[i]
            when {
                c in '0'..'9' -> {
                    whole = whole * 10 + (c - '0')
                    digits++
                    if (decimals >= 0) decimals++
                    if (whole >= EXACT_LIMIT) return slow(text, start, end)
                }
                c == '.' && decimals < 0 -> decimals = 0
                else -> return slow(text, start, end)
            }
            i++
        }
        if (decimals == 0 || decimals > MAX_DECIMALS || digits == 0 || (decimals > 0 && digits == decimals)) return slow(text, start, end)
        val value = if (decimals > 0) whole.toFloat() / POWERS_OF_TEN[decimals] else whole.toFloat()
        return if (negative) -value else value
    }

    private fun slow(text: String, start: Int, end: Int): Float? = text.substring(start, end).toFloatOrNull()

    /** Below 2^24 every whole number is exact as a float. */
    private const val EXACT_LIMIT = 1 shl 24
    private const val MAX_DECIMALS = 10
    private val POWERS_OF_TEN = FloatArray(MAX_DECIMALS + 1) { var p = 1f; repeat(it) { p *= 10f }; p }

    private fun kindName(kind: StrokeKind) = when (kind) {
        StrokeKind.PEN -> "pen"
        StrokeKind.HIGHLIGHTER -> "hl"
    }

    private fun kindFor(name: String) = when (name) {
        "pen" -> StrokeKind.PEN
        "hl" -> StrokeKind.HIGHLIGHTER
        else -> null
    }
}
