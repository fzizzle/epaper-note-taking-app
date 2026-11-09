package com.fzizzle.epapernotes.ink

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import kotlin.math.max
import kotlin.math.min

/**
 * One thing the user put on a page that the lasso and eraser can act on: a stroke, or a
 * pasted picture. Every item has an [id] so undo can find it again after it was moved.
 */
sealed interface Item {
    val id: String

    /** The area it covers, in PDF space. */
    val box: Box
}

data class InkItem(override val id: String, val stroke: Stroke) : Item {
    override val box: Box get() = StrokeOutline.bounds(stroke)
}

/**
 * A picture (a snip) placed on a page. [box] is in PDF space.
 *
 * [quarterTurns] (0..3) is the page rotation, in clockwise quarter turns, on which the
 * picture looks upright: a snip taken on a page at /Rotate 90 has 1. It is drawn counter-rotated
 * by that much inside [box], so it looks upright on a page turned that way.
 */
class ImageItem(
    override val id: String,
    override val box: Box,
    val quarterTurns: Int,
    val png: ByteArray,
) : Item {
    // A class, not a data class, so [png] is compared by content.
    fun copy(id: String = this.id, box: Box = this.box, quarterTurns: Int = this.quarterTurns, png: ByteArray = this.png) =
        ImageItem(id, box, quarterTurns, png)

    override fun equals(other: Any?) = other is ImageItem && id == other.id && box == other.box &&
        quarterTurns == other.quarterTurns && png.contentEquals(other.png)

    override fun hashCode() = 31 * (31 * (31 * id.hashCode() + box.hashCode()) + quarterTurns) + png.contentHashCode()

    override fun toString() = "ImageItem(id=$id, box=$box, quarterTurns=$quarterTurns, png=${png.size} bytes)"
}

/** Random 12-character ids, short enough to keep in the journal and the PDF. */
object ItemIds {
    private val random = java.security.SecureRandom()

    fun next(): String = buildString {
        repeat(12) { append("0123456789abcdef"[random.nextInt(16)]) }
    }
}

/**
 * The item after moving/resizing it by [t]. [uniformScale] is how much [t] enlarges lengths;
 * ink uses it to keep its look (see [Stroke.widthScale]). The id stays the same.
 */
fun Item.transformed(t: Affine, uniformScale: Float): Item = when (this) {
    is InkItem -> copy(
        stroke = stroke.copy(
            points = stroke.points.map { val q = t.map(Pt(it.x, it.y)); it.copy(x = q.x, y = q.y) },
            widthScale = stroke.widthScale * uniformScale,
        ),
    )
    is ImageItem -> {
        val a = t.map(Pt(box.x0, box.y0))
        val b = t.map(Pt(box.x1, box.y1))
        // A turn or flip can swap the corners; keep x0<x1 and y0<y1.
        copy(box = Box(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)))
    }
}
