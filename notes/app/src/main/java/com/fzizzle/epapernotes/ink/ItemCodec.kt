package com.fzizzle.epapernotes.ink

import java.util.Base64
import java.util.Locale

/**
 * Items as one line of text, for the journal and the PDF. Never contains `|` or a newline,
 * because the caller joins items with ` | `.
 *
 * Formats: `ink <id> <StrokeCodec text>` and `img <id> <x0>,<y0>,<x1>,<y1> <turns> <picture>`.
 *
 * `<picture>` is the PNG as base64, or `@<name>` when the caller keeps the picture somewhere
 * else under that name. The journal does: a snip's picture is big, and a snip is mentioned
 * again each time it's moved, erased or brought back (see ChangeJournal). Base64 never
 * contains `@`, so the two can't be mistaken for each other.
 */
object ItemCodec {
    /** The mark in front of a picture's name. */
    private const val NAMED = "@"

    /**
     * [pictureName] is asked for each picture: a name to write in its place, or null to write
     * the picture itself. A name must be plain letters and digits.
     */
    fun encode(item: Item, pictureName: (ByteArray) -> String? = { null }): String {
        // An odd id (a space, a `|`) would split the line wrongly when it's read back.
        require(ItemIds.isPlain(item.id)) { "item id must be lower-case letters and digits: '${item.id}'" }
        return encodeChecked(item, pictureName)
    }

    private fun encodeChecked(item: Item, pictureName: (ByteArray) -> String?): String = when (item) {
        is InkItem -> "ink ${item.id} ${StrokeCodec.encode(item.stroke)}"
        is ImageItem -> {
            val b = item.box
            val box = String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", b.x0, b.y0, b.x1, b.y1)
            val picture = pictureName(item.png)?.let { NAMED + it } ?: Base64.getEncoder().encodeToString(item.png)
            "img ${item.id} $box ${item.quarterTurns} $picture"
        }
    }

    /**
     * Returns null for anything it doesn't recognise. [picture] gives the picture kept under a
     * name; if it has none, the item is null too, rather than a snip without its picture.
     */
    fun decode(text: String, picture: (String) -> ByteArray? = { null }): Item? {
        val parts = text.trim().split(' ', limit = 3)
        if (parts.size < 3 || parts[1].isEmpty()) return null
        return when (parts[0]) {
            "ink" -> StrokeCodec.decode(parts[2])?.let { InkItem(parts[1], it) }
            "img" -> decodeImage(parts[1], parts[2], picture)
            else -> null
        }
    }

    private fun decodeImage(id: String, rest: String, picture: (String) -> ByteArray?): ImageItem? {
        val parts = rest.split(' ')
        if (parts.size != 3) return null
        val n = parts[0].split(',').map { it.toFloatOrNull() ?: return null }
        if (n.size != 4) return null
        val turns = parts[1].toIntOrNull()?.takeIf { it in 0..3 } ?: return null
        val png = if (parts[2].startsWith(NAMED)) {
            picture(parts[2].removePrefix(NAMED)) ?: return null
        } else {
            try {
                Base64.getDecoder().decode(parts[2])
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        return ImageItem(id, Box(n[0], n[1], n[2], n[3]), turns, png)
    }
}
