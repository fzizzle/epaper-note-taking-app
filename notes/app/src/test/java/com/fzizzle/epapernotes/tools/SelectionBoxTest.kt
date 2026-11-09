package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionBoxTest {
    private val box = SelectionBox(Box(100f, 100f, 200f, 150f))

    @Test fun cornerBeatsMove() {
        assertEquals(SelectionBox.Grip.TOP_LEFT, box.gripAt(105f, 105f, 20f))
        assertEquals(SelectionBox.Grip.BOTTOM_RIGHT, box.gripAt(195f, 145f, 20f))
        assertEquals(SelectionBox.Grip.MOVE, box.gripAt(150f, 125f, 20f))
    }

    @Test fun outsideIsNone() {
        assertEquals(SelectionBox.Grip.NONE, box.gripAt(300f, 300f, 20f))
    }

    @Test fun cornerDragKeepsProportions() {
        val t = box.drag(SelectionBox.Grip.BOTTOM_RIGHT, 100f, 10f)
        val tl = t.map(Pt(100f, 100f))
        val br = t.map(Pt(200f, 150f))
        assertEquals(100f, tl.x, 0.01f); assertEquals(100f, tl.y, 0.01f)
        assertEquals(300f, br.x, 0.01f); assertEquals(200f, br.y, 0.01f)
    }

    @Test fun cannotShrinkBelowMinimum() {
        val t = box.drag(SelectionBox.Grip.BOTTOM_RIGHT, -500f, -500f)
        val br = t.map(Pt(200f, 150f))
        assertEquals(24f, br.y - 100f, 0.01f) // shorter side (height 50) is held at 24
        assertEquals(48f, br.x - 100f, 0.01f)
    }

    @Test fun moveTranslates() {
        val t = box.drag(SelectionBox.Grip.MOVE, 5f, -3f)
        assertEquals(Pt(105f, 97f), t.map(Pt(100f, 100f)))
    }

    @Test fun smallBoxIsNeverEnlarged() {
        val small = SelectionBox(Box(0f, 0f, 10f, 10f))
        assertEquals(com.fzizzle.epapernotes.geometry.Affine.IDENTITY, small.drag(SelectionBox.Grip.BOTTOM_RIGHT, 0f, 0f))
        val br = small.drag(SelectionBox.Grip.BOTTOM_RIGHT, -5f, -5f).map(Pt(10f, 10f))
        assertEquals(10f, br.x, 0.01f); assertEquals(10f, br.y, 0.01f)
    }

    @Test fun flatBoxCornerDragDoesNothing() {
        val flat = SelectionBox(Box(5f, 5f, 5f, 80f))
        assertEquals(com.fzizzle.epapernotes.geometry.Affine.IDENTITY, flat.drag(SelectionBox.Grip.TOP_LEFT, 10f, 10f))
        assertEquals(Pt(15f, 15f), flat.drag(SelectionBox.Grip.MOVE, 10f, 10f).map(Pt(5f, 5f)))
    }
}
