package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.StrokeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenPointsTest {
    /** A pretend screen: page 0 above y=1000, page 1 below; PDF point = screen pixel / 2. */
    private val toPdf = { x: Float, y: Float -> (if (y < 1000) 0 else 1) to Pt(x / 2, y / 2) }

    @Test fun pressureIsNormalised() {
        val (_, stroke) = PenPoints.toStroke(StrokeKind.PEN, listOf(floatArrayOf(10f, 10f, 2047.5f)), toPdf)!!
        assertEquals(0.5f, stroke.points.single().pressure, 1e-4f)
    }

    @Test fun pressureIsClamped() {
        val (_, stroke) = PenPoints.toStroke(StrokeKind.PEN, listOf(floatArrayOf(10f, 10f, 9000f), floatArrayOf(12f, 10f, -5f)), toPdf)!!
        assertEquals(1f, stroke.points[0].pressure, 1e-4f)
        assertEquals(0f, stroke.points[1].pressure, 1e-4f)
    }

    @Test fun strokeBelongsToFirstPointsPage() {
        val (page, stroke) = PenPoints.toStroke(StrokeKind.HIGHLIGHTER, listOf(floatArrayOf(10f, 1200f, 100f), floatArrayOf(20f, 1300f, 100f)), toPdf)!!
        assertEquals(1, page)
        assertEquals(StrokeKind.HIGHLIGHTER, stroke.kind)
        assertEquals(Pt(5f, 600f), Pt(stroke.points[0].x, stroke.points[0].y))
    }

    @Test fun pointsOnOtherPagesAreDropped() {
        val (_, stroke) = PenPoints.toStroke(
            StrokeKind.PEN,
            listOf(floatArrayOf(10f, 990f, 100f), floatArrayOf(10f, 995f, 100f), floatArrayOf(10f, 1005f, 100f)),
            toPdf,
        )!!
        assertEquals(2, stroke.points.size)
    }

    @Test fun emptyGivesNull() = assertNull(PenPoints.toStroke(StrokeKind.PEN, emptyList(), toPdf))
}
