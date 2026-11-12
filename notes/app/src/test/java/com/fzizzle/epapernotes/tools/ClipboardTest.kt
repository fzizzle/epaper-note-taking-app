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

    // ---- Duplicate (writing-screen design plan): a copy a little down and right of the original, as shown.

    private fun displayBox(item: com.fzizzle.epapernotes.ink.Item, toDisplay: Affine): Box {
        val b = item.box
        val p = toDisplay.map(Pt(b.x0, b.y0)); val q = toDisplay.map(Pt(b.x1, b.y1))
        return Box(minOf(p.x, q.x), minOf(p.y, q.y), maxOf(p.x, q.x), maxOf(p.y, q.y))
    }

    @Test fun aDuplicateHasFreshIdsAndIsMoved() {
        val copies = duplicateOf(listOf(stroke, image), unrotated, pageWidth = 600f, pageHeight = 800f)
        assertEquals(2, copies.size)
        assertEquals(emptySet<String>(), copies.map { it.id }.toSet() intersect setOf("s", "i"))
        for ((original, copy) in listOf(stroke, image).zip(copies)) {
            val was = displayBox(original, unrotated); val now = displayBox(copy, unrotated)
            assertEquals(was.x0 + 14f, now.x0, 0.01f)
            assertEquals(was.y0 + 14f, now.y0, 0.01f) // down, as shown: display y grows downwards
            assertEquals(was.x1 - was.x0, now.x1 - now.x0, 0.01f)
        }
    }

    @Test fun aDuplicateOnATurnedPageMovesRightAndDownAsShown() {
        // /Rotate 90: the page is shown 800 wide and 600 tall.
        val copy = duplicateOf(listOf(stroke), rotated90, pageWidth = 800f, pageHeight = 600f).single()
        val was = displayBox(stroke, rotated90); val now = displayBox(copy, rotated90)
        assertEquals(was.x0 + 14f, now.x0, 0.01f)
        assertEquals(was.y0 + 14f, now.y0, 0.01f)
    }

    /** At the page's bottom-right corner there's only 5 points of room each way: the copy takes that, and stays on the page. */
    @Test fun aDuplicateStaysOnThePage() {
        // Display box (515, 765)-(595, 795) on a 600 x 800 page.
        val corner = ImageItem("c", Box(515f, 5f, 595f, 35f), 0, byteArrayOf(1))
        val now = displayBox(duplicateOf(listOf(corner), unrotated, 600f, 800f).single(), unrotated)
        assertEquals(600f, now.x1, 0.01f)
        assertEquals(800f, now.y1, 0.01f)
        // Hard against the edges there's no room at all: the copy lies on the original.
        val flush = ImageItem("f", Box(520f, 0f, 600f, 30f), 0, byteArrayOf(1))
        assertEquals(displayBox(flush, unrotated), displayBox(duplicateOf(listOf(flush), unrotated, 600f, 800f).single(), unrotated))
    }

    @Test fun aSnipIsDuplicatedWhole() {
        val copy = duplicateOf(listOf(image), unrotated, 600f, 800f).single() as ImageItem
        assertEquals(image.quarterTurns, copy.quarterTurns)
        assertEquals(image.png.toList(), copy.png.toList())
    }

    @Test fun nothingSelectedNothingDuplicated() = assertEquals(emptyList<com.fzizzle.epapernotes.ink.Item>(), duplicateOf(emptyList(), unrotated, 600f, 800f))
}
