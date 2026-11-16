package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepOnPageTest {
    private fun shift(box: Box) = KeepOnPage.shift(box, 600f, 800f)

    @Test fun aBoxOnThePageIsLeftAlone() {
        assertEquals(Pt(0f, 0f), shift(Box(10f, 10f, 100f, 100f)))
    }

    @Test fun aBoxPastTheRightEdgeIsPushedBack() {
        assertEquals(Pt(-40f, 0f), shift(Box(580f, 100f, 640f, 150f)))
    }

    @Test fun aBoxAboveThePageComesDown() {
        assertEquals(Pt(0f, 30f), shift(Box(10f, -30f, 60f, 20f)))
    }

    @Test fun aBoxWhollyBesideThePageComesOntoIt() {
        assertEquals(Pt(-150f, -150f), shift(Box(700f, 900f, 750f, 950f)))
    }

    @Test fun aBoxWiderThanThePageCoversIt() {
        assertEquals(Pt(-50f, 0f), shift(Box(50f, 0f, 750f, 100f)))
        assertEquals(Pt(100f, 0f), shift(Box(-200f, 0f, 500f, 100f)))
    }

    // MuPDF's matrix for a page turned a quarter: display (x, y) = PDF (y, x).
    private val turned = Affine(0f, 1f, 1f, 0f, 0f, 0f)

    private fun displayBox(item: Item): Box {
        val p = turned.map(Pt(item.box.x0, item.box.y0)); val q = turned.map(Pt(item.box.x1, item.box.y1))
        return Box(minOf(p.x, q.x), minOf(p.y, q.y), maxOf(p.x, q.x), maxOf(p.y, q.y))
    }

    @Test fun onPageMovesInkAndSnipsTogether() {
        val ink = InkItem("s", Stroke(StrokeKind.PEN, listOf(StrokePoint(900f, 100f, .5f), StrokePoint(950f, 130f, .5f))))
        val image = ImageItem("i", Box(920f, 300f, 980f, 340f), 1, byteArrayOf(1))
        val out = onPage(listOf(ink, image), turned, 600f, 800f)
        val a = displayBox(out[0]); val b = displayBox(out[1])
        for (box in listOf(a, b)) {
            assertTrue(box.x0 >= -0.01f && box.y0 >= -0.01f && box.x1 <= 600.01f && box.y1 <= 800.01f)
        }
        val before = displayBox(image)
        val beforeInk = displayBox(ink)
        assertEquals(before.x0 - beforeInk.x0, b.x0 - a.x0, 0.01f)
        assertEquals(before.y0 - beforeInk.y0, b.y0 - a.y0, 0.01f)
    }

    @Test fun onPageLeavesItemsOnThePageAlone() {
        val items = listOf<Item>(ImageItem("i", Box(10f, 10f, 50f, 50f), 0, byteArrayOf(1)))
        assertSame(items, onPage(items, Affine(1f, 0f, 0f, -1f, 0f, 800f), 600f, 800f))
    }
}
