package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSelectTest {
    private fun line(id: String, kind: StrokeKind = StrokeKind.PEN, y: Float = 100f) =
        InkItem(id, Stroke(kind, listOf(StrokePoint(0f, y, 0.5f), StrokePoint(200f, y, 0.5f))))

    /** The pen put down and lifted: a few points within a couple of points of each other. */
    private fun tapAt(x: Float, y: Float) = listOf(Pt(x, y), Pt(x + 1f, y + 0.5f), Pt(x + 0.5f, y - 1f))

    @Test fun aSmallScribbleIsATap() {
        assertTrue(TapSelect.isTap(tapAt(50f, 50f)))
        assertTrue(TapSelect.isTap(listOf(Pt(50f, 50f)))) // a single point
    }

    @Test fun aLoopIsNotATap() {
        assertFalse(TapSelect.isTap(listOf(Pt(0f, 0f), Pt(40f, 0f), Pt(40f, 40f), Pt(0f, 40f))))
        assertFalse(TapSelect.isTap(listOf(Pt(0f, 0f), Pt(7f, 0f)))) // long one way is a line, not a tap
        assertFalse(TapSelect.isTap(emptyList()))
    }

    @Test fun aTapOnAStrokePicksIt() {
        val s = line("s")
        assertEquals(listOf<Item>(s), TapSelect.pick(listOf(line("far", y = 300f), s), tapAt(100f, 100f)))
    }

    /** A highlight with a pen stroke written over it: the pen stroke is on top, so it's the one meant. */
    @Test fun theTopItemUnderATapIsPicked() {
        val under = line("highlight", StrokeKind.HIGHLIGHTER)
        val over = line("pen")
        assertEquals(listOf<Item>(over), TapSelect.pick(listOf(under, over), tapAt(100f, 100f)))
    }

    @Test fun aTapOnNothingPicksNothing() = assertEquals(emptyList<Item>(), TapSelect.pick(listOf(line("s")), tapAt(100f, 300f)))

    @Test fun aTapInsideASnipPicksIt() {
        val snip = ImageItem("i", Box(50f, 50f, 150f, 150f), 0, byteArrayOf(1))
        assertEquals(listOf<Item>(snip), TapSelect.pick(listOf(snip), tapAt(100f, 100f)))
    }
}
