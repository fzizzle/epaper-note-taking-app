package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserTest {
    private fun line(kind: StrokeKind = StrokeKind.PEN, y: Float = 100f) =
        InkItem("s", Stroke(kind, listOf(StrokePoint(0f, y, 0.5f), StrokePoint(200f, y, 0.5f))))

    @Test fun touchingAnyPartRemovesTheWholeStroke() {
        val s = line()
        assertEquals(listOf<Any>(s), Eraser.hits(listOf(s), listOf(Pt(100f, 101f), Pt(100f, 102f))))
    }

    @Test fun nearMissKeepsTheStroke() {
        assertTrue(Eraser.hits(listOf(line()), listOf(Pt(100f, 106f))).isEmpty())
    }

    @Test fun highlighterIsHitAtItsEdge() {
        val h = line(StrokeKind.HIGHLIGHTER)
        assertEquals(1, Eraser.hits(listOf(h), listOf(Pt(100f, 105f))).size)
    }

    @Test fun tapHitsADot() {
        val dot = InkItem("d", Stroke(StrokeKind.PEN, listOf(StrokePoint(50f, 50f, 0.5f))))
        assertEquals(1, Eraser.hits(listOf(dot), listOf(Pt(52f, 50f))).size)
        assertTrue(Eraser.hits(listOf(dot), listOf(Pt(60f, 50f))).isEmpty())
    }

    @Test fun imageIsHitInside() {
        val img = ImageItem("i", Box(0f, 0f, 50f, 50f), 0, byteArrayOf(1))
        assertEquals(1, Eraser.hits(listOf(img), listOf(Pt(25f, 25f))).size)
        assertTrue(Eraser.hits(listOf(img), listOf(Pt(60f, 25f))).isEmpty())
    }
}
