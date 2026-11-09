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

class LassoTest {
    private val square = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f))

    private fun stroke(inside: Int) = InkItem(
        "s",
        Stroke(StrokeKind.PEN, List(10) { StrokePoint(if (it < inside) 50f else 150f, 50f, 0.5f) }),
    )

    @Test fun strokeMostlyInsideIsSelected() {
        assertEquals(1, Lasso.selects(listOf(stroke(7)), square).size)
    }

    @Test fun strokeMostlyOutsideIsNot() {
        assertTrue(Lasso.selects(listOf(stroke(3)), square).isEmpty())
    }

    @Test fun imageByCentre() {
        val inn = ImageItem("a", Box(30f, 40f, 130f, 60f), 0, byteArrayOf(1))
        val out = ImageItem("b", Box(60f, 40f, 300f, 60f), 0, byteArrayOf(1))
        assertEquals(listOf<Any>(inn), Lasso.selects(listOf(inn, out), square))
    }

    @Test fun tooFewPointsSelectsNothing() {
        assertTrue(Lasso.selects(listOf(stroke(10)), listOf(Pt(0f, 0f), Pt(100f, 100f))).isEmpty())
    }
}
