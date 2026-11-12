package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkOverlayTest {
    private fun stroke(n: Int) = Stroke(StrokeKind.PEN, listOf(StrokePoint(n.toFloat(), 0f, 0.5f)))

    @Test fun nothingWrittenNothingToDraw() {
        val ink = InkOverlay()
        assertEquals(0, ink.version(3))
        assertEquals(emptyList<Stroke>(), ink.newerThan(3, 0))
    }

    @Test fun aRenderingShowsOnlyTheStrokesItLacks() {
        val ink = InkOverlay()
        ink.add(0, stroke(1))
        ink.add(0, stroke(2))
        val renderedWith = ink.version(0) // a rendering asked for now includes both
        ink.add(0, stroke(3))
        assertEquals(listOf(stroke(3)), ink.newerThan(0, renderedWith))
    }

    /** The zoom bug: an older rendering, made before some strokes, must get them drawn on top. */
    @Test fun anOlderRenderingGetsTheStrokesItLacks() {
        val ink = InkOverlay()
        val oldRendering = ink.version(0) // rendered before any writing
        ink.add(0, stroke(1))
        ink.add(0, stroke(2))
        assertEquals(listOf(stroke(1), stroke(2)), ink.newerThan(0, oldRendering))
    }

    @Test fun pagesAreSeparate() {
        val ink = InkOverlay()
        ink.add(1, stroke(1))
        assertEquals(0, ink.version(0))
        assertEquals(1, ink.version(1))
    }

    /**
     * A change other than a new stroke (an erase, a move) may touch strokes the overlay holds,
     * so renderings from before it are stale, and only strokes written after it are drawn on top.
     */
    @Test fun changeMakesOlderRenderingsStale() {
        val ink = InkOverlay()
        ink.add(0, stroke(1))
        assertEquals(1, ink.version(0))
        ink.changed(0)
        assertEquals(2, ink.version(0))
        ink.add(0, stroke(2))
        assertEquals(3, ink.version(0))
        assertTrue(ink.isStale(0, 1))
        assertFalse(ink.isStale(0, 2))
        assertEquals(listOf(stroke(2)), ink.newerThan(0, 2))
        // A stale rendering made after A has A, so only B goes on top.
        assertEquals(listOf(stroke(2)), ink.newerThan(0, 1))
        assertFalse(ink.isStale(1, 0))
    }

    /**
     * A change must not make this session's writing vanish: a stale rendering still gets every
     * stroke it lacks drawn on top, until the page is left (owner's vanishing-strokes report).
     */
    @Test fun strokesSurviveAChange() {
        val ink = InkOverlay()
        ink.add(0, stroke(1))
        ink.changed(0)
        ink.add(0, stroke(2))
        assertEquals(listOf(stroke(1), stroke(2)), ink.newerThan(0, 0))
        assertEquals(listOf(stroke(2)), ink.newerThan(0, 2))
        // Leaving the page drops what no rendering asked for from now on can lack.
        ink.left(0)
        assertEquals(3, ink.version(0))
        assertEquals(listOf(stroke(2)), ink.newerThan(0, 0))
    }

    /** How many strokes a rendering lacks: what decides when a full page is worth rendering afresh. */
    @Test fun countFollowsAddsAndLeaving() {
        val ink = InkOverlay()
        assertEquals(0, ink.count(0))
        ink.add(0, stroke(1))
        ink.add(0, stroke(2))
        ink.add(1, stroke(3))
        assertEquals(2, ink.count(0))
        assertEquals(1, ink.count(0, sinceVersion = 1))
        assertEquals(1, ink.count(1))
        // After a change and leaving, the strokes from before the change are no longer held.
        ink.changed(0)
        ink.add(0, stroke(4))
        ink.left(0)
        assertEquals(1, ink.count(0))
        assertEquals(1, ink.count(1))
    }

    /**
     * What the quiet re-render of a full page relies on: a rendering asked for at the page's
     * version now holds every stroke, so nothing is drawn on top of it, and it isn't stale.
     */
    @Test fun aRenderingAtTheLatestVersionLeavesNothingToDraw() {
        val ink = InkOverlay()
        repeat(InkOverlay.BAKE_AFTER + 1) { ink.add(0, stroke(it)) }
        assertEquals(InkOverlay.BAKE_AFTER + 1, ink.count(0, sinceVersion = 0))
        val fresh = ink.version(0)
        assertEquals(0, ink.count(0, sinceVersion = fresh))
        assertEquals(emptyList<Stroke>(), ink.newerThan(0, fresh))
        assertFalse(ink.isStale(0, fresh))
        // A stroke written while that rendering was being made still goes on top of it.
        ink.add(0, stroke(999))
        assertEquals(listOf(stroke(999)), ink.newerThan(0, fresh))
    }

    /**
     * A page added or removed renumbers the pages after it, and every stroke has just been
     * saved: the overlay forgets everything, so nothing is drawn on top of the wrong page.
     */
    @Test fun clearForgetsEveryPage() {
        val ink = InkOverlay()
        ink.add(0, stroke(1))
        ink.changed(0)
        ink.add(3, stroke(2))
        ink.clear()
        assertEquals(0, ink.version(0))
        assertEquals(0, ink.version(3))
        assertEquals(emptyList<Stroke>(), ink.newerThan(0, 0))
        assertEquals(emptyList<Stroke>(), ink.newerThan(3, 0))
        assertEquals(0, ink.count(3))
        assertFalse(ink.isStale(0, 0))
        // Writing goes on as on a fresh overlay.
        ink.add(3, stroke(4))
        assertEquals(1, ink.version(3))
        assertEquals(listOf(stroke(4)), ink.newerThan(3, 0))
    }
}
