package com.fzizzle.epapernotes.ink

import java.util.Locale

/**
 * Turns a [Stroke] into text and back, so it can be kept inside the PDF annotation that
 * draws it. Standard ink annotations have no per-point pressure, so this text is how the app
 * gets its strokes back as editable strokes.
 *
 * Format: `pn1 <kind> <x>,<y>,<p> <x>,<y>,<p> …`, where kind is `pen` or `hl`.
 * It's plain text on purpose, so it can be read in a PDF inspector. `pn1` is a version tag:
 * a later format can use `pn2` without breaking files written today.
 */
object StrokeCodec {
    /** The private key the text is stored under, in the annotation's dictionary. */
    const val KEY = "PNotesStroke"

    private const val VERSION = "pn1"

    fun encode(stroke: Stroke): String = buildString {
        append(VERSION).append(' ').append(kindName(stroke.kind))
        for (p in stroke.points) {
            // Locale.ROOT so the decimal point is always '.', whatever language the tablet uses.
            append(String.format(Locale.ROOT, " %.2f,%.2f,%.3f", p.x, p.y, p.pressure))
        }
    }

    /** Returns null for anything it doesn't recognise. The caller treats that as a foreign annotation. */
    fun decode(text: String): Stroke? {
        val parts = text.trim().split(' ')
        if (parts.size < 3 || parts[0] != VERSION) return null
        val kind = kindFor(parts[1]) ?: return null
        val points = parts.drop(2).map { parsePoint(it) ?: return null }
        return Stroke(kind, points)
    }

    private fun parsePoint(text: String): StrokePoint? {
        val n = text.split(',')
        if (n.size != 3) return null
        val x = n[0].toFloatOrNull() ?: return null
        val y = n[1].toFloatOrNull() ?: return null
        val p = n[2].toFloatOrNull() ?: return null
        return StrokePoint(x, y, p)
    }

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
