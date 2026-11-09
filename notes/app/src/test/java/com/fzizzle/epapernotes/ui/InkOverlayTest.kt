package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
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
}
