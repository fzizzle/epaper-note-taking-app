package com.fzizzle.epapernotes.ink

import java.util.Base64
import java.util.Locale

/**
 * Items as one line of text, for the journal and the PDF. Never contains `|` or a newline,
 * because the caller joins items with ` | `.
 *
 * Formats: `ink <id> <StrokeCodec text>` and `img <id> <x0>,<y0>,<x1>,<y1> <turns> <base64 png>`.
 */
object ItemCodec {
    fun encode(item: Item): String {
        // An odd id (a space, a `|`) would split the line wrongly when it's read back.
        require(ItemIds.isPlain(item.id)) { "item id must be lower-case letters and digits: '${item.id}'" }
        return encodeChecked(item)
    }

    private fun encodeChecked(item: Item): String = when (item) {
        is InkItem -> "ink ${item.id} ${StrokeCodec.encode(item.stroke)}"
        is ImageItem -> {
            val b = item.box
            val box = String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", b.x0, b.y0, b.x1, b.y1)
            "img ${item.id} $box ${item.quarterTurns} ${Base64.getEncoder().encodeToString(item.png)}"
        }
    }

    /** Returns null for anything it doesn't recognise. */
    fun decode(text: String): Item? {
        val parts = text.trim().split(' ', limit = 3)
        if (parts.size < 3 || parts[1].isEmpty()) return null
        return when (parts[0]) {
            "ink" -> StrokeCodec.decode(parts[2])?.let { InkItem(parts[1], it) }
            "img" -> decodeImage(parts[1], parts[2])
            else -> null
        }
    }

    private fun decodeImage(id: String, rest: String): ImageItem? {
        val parts = rest.split(' ')
        if (parts.size != 3) return null
        val n = parts[0].split(',').map { it.toFloatOrNull() ?: return null }
        if (n.size != 4) return null
        val turns = parts[1].toIntOrNull()?.takeIf { it in 0..3 } ?: return null
        val png = try {
            Base64.getDecoder().decode(parts[2])
        } catch (e: IllegalArgumentException) {
            return null
        }
        return ImageItem(id, Box(n[0], n[1], n[2], n[3]), turns, png)
    }
}
