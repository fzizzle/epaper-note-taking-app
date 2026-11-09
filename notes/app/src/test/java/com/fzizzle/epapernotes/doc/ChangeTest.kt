package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeTest {
    private val ink = InkItem("a1", Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 2f, 0.5f), StrokePoint(3f, 4f, 0.5f))))
    private val moved = InkItem("a1", Stroke(StrokeKind.PEN, listOf(StrokePoint(5f, 2f, 0.5f), StrokePoint(7f, 4f, 0.5f))))
    private val image = ImageItem("b2", Box(1f, 2f, 30f, 40f), 1, ByteArray(50) { (it * 7).toByte() })

    private val all = listOf(
        AddItems(0, listOf(ink, image)),
        RemoveItems(2, listOf(image, ink)),
        ReplaceItems(1, listOf(ink), listOf(moved)),
        RotatePages(mapOf(0 to 0, 1 to 90), mapOf(0 to 90, 1 to 180)),
    )

    @Test fun inverseOfInverseIsTheSame() = all.forEach { assertEquals(it, it.inverse().inverse()) }

    @Test fun inversesDoTheOpposite() {
        assertEquals(RemoveItems(0, listOf(ink)), AddItems(0, listOf(ink)).inverse())
        assertEquals(ReplaceItems(1, listOf(moved), listOf(ink)), ReplaceItems(1, listOf(ink), listOf(moved)).inverse())
    }

    @Test fun everyKindRoundTripsThroughText() = all.forEach {
        val text = ChangeCodec.encode(it)
        assertEquals(false, text.contains('\n'))
        assertEquals(it, ChangeCodec.decode(text))
    }

    @Test fun rotatePagesCoversBothMapsKeys() =
        assertEquals(setOf(0, 1), all[3].pages)

    @Test fun garbageDecodesToNull() {
        assertEquals(null, ChangeCodec.decode("zap 1 x"))
        assertEquals(null, ChangeCodec.decode("add x ink a1 pn2 pen 1.00,2.00,0.500"))
        assertEquals(null, ChangeCodec.decode("rot 0=90"))
    }
}
