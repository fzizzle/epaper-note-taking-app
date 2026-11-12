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

    /**
     * Tap or loop is decided on the screen, where the hand made it, not on the page: zoomed in
     * four times, a loop round a colon is 2 points across on the page but a plain loop to the eye.
     */
    @Test fun theSpanIsWhateverTheCallerMeasuresIn() {
        val onScreen = listOf(Pt(500f, 500f), Pt(520f, 512f), Pt(508f, 521f)) // 20 px by 21 px
        assertTrue(TapSelect.isTap(onScreen, span = TapSelect.TAP_SPAN_PX))
        assertFalse(TapSelect.isTap(listOf(Pt(500f, 500f), Pt(530f, 500f)), span = TapSelect.TAP_SPAN_PX))
    }

    /** How near counts as "under the tap" is the caller's too: the same finger-width on screen at any zoom. */
    @Test fun aWiderReachPicksAStrokeFurtherOff() {
        val s = line("s")
        val near = tapAt(100f, 108f) // 8 points below the line's middle
        assertEquals(emptyList<Item>(), TapSelect.pick(listOf(s), near))
        assertEquals(listOf<Item>(s), TapSelect.pick(listOf(s), near, reachPt = 9f))
    }
}
