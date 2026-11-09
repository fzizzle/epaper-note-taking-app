package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClipboardTest {
    // MediaBox 0 0 600 800. Unrotated: y flips. /Rotate 90: MuPDF's [0 1 1 0 0 0].
    private val unrotated = Affine(1f, 0f, 0f, -1f, 0f, 800f)
    private val rotated90 = Affine(0f, 1f, 1f, 0f, 0f, 0f)

    private val stroke = InkItem(
        "s", Stroke(StrokeKind.PEN, listOf(StrokePoint(100f, 100f, .5f), StrokePoint(180f, 120f, .5f))),
    )
    private val image = ImageItem("i", Box(100f, 200f, 180f, 230f), 0, byteArrayOf(1))

    private fun centreOnDisplay(item: InkItem, toDisplay: Affine): Pt {
        val b = item.box
        val p = toDisplay.map(Pt(b.x0, b.y0)); val q = toDisplay.map(Pt(b.x1, b.y1))
        return Pt((p.x + q.x) / 2, (p.y + q.y) / 2)
    }

    @Test fun pasteCentresOnTheTap() {
        val out = Clipboard(listOf(stroke), unrotated).placedOn(unrotated.inverse(), Pt(300f, 400f))
        val c = centreOnDisplay(out[0] as InkItem, unrotated)
        assertEquals(300f, c.x, 0.01f); assertEquals(400f, c.y, 0.01f)
    }

    @Test fun pasteGetsNewIds() {
        val out = Clipboard(listOf(stroke, image), unrotated).placedOn(unrotated.inverse(), Pt(300f, 400f))
        assertNotEquals("s", out[0].id); assertNotEquals("i", out[1].id)
        assertNotEquals(out[0].id, out[1].id)
    }

    @Test fun pasteOntoRotatedPageStaysUpright() {
        val out = Clipboard(listOf(stroke, image), unrotated).placedOn(rotated90.inverse(), Pt(300f, 400f))
        val before = stroke.box; val after = out[0].box
        val b = unrotated.map(Pt(before.x0, before.y0)).let { p -> unrotated.map(Pt(before.x1, before.y1)).let { q -> Pt(Math.abs(p.x - q.x), Math.abs(p.y - q.y)) } }
        val a = rotated90.map(Pt(after.x0, after.y0)).let { p -> rotated90.map(Pt(after.x1, after.y1)).let { q -> Pt(Math.abs(p.x - q.x), Math.abs(p.y - q.y)) } }
        assertEquals(b.x, a.x, 0.01f); assertEquals(b.y, a.y, 0.01f)
        assertEquals(1, (out[1] as ImageItem).quarterTurns)
    }

    @Test fun pasteBackToUnrotatedLowersQuarterTurns() {
        val snip = ImageItem("i", Box(0f, 0f, 30f, 80f), 1, byteArrayOf(1))
        val out = Clipboard(listOf(snip), rotated90).placedOn(unrotated.inverse(), Pt(300f, 400f))
        assertEquals(0, (out[0] as ImageItem).quarterTurns)
    }
}
