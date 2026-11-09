package com.fzizzle.epapernotes.ink

import com.fzizzle.epapernotes.geometry.Affine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ItemCodecTest {
    private val ink = InkItem("0a1b2c3d4e5f", Stroke(StrokeKind.PEN, listOf(StrokePoint(10f, 20f, 0.5f), StrokePoint(30f, 40f, 1f))))
    private val image = ImageItem("ffeeddccbbaa", Box(10f, 20f, 110.5f, 220f), 1, byteArrayOf(1, 2, 3))

    @Test fun inkRoundTrips() = assertEquals(ink, ItemCodec.decode(ItemCodec.encode(ink)))

    @Test fun imageRoundTrips() = assertEquals(image, ItemCodec.decode(ItemCodec.encode(image)))

    @Test fun rejectsGarbage() = assertNull(ItemCodec.decode("img x"))

    @Test fun neverContainsSeparators() {
        for (item in listOf(ink, image)) {
            val text = ItemCodec.encode(item)
            assertFalse(text.contains('|'))
            assertFalse(text.contains('\n'))
        }
    }

    @Test fun transformScalesWidth() {
        val moved = ink.transformed(Affine.scale(2f), 2f) as InkItem
        assertEquals(listOf(20f, 60f), moved.stroke.points.map { it.x })
        assertEquals(listOf(40f, 80f), moved.stroke.points.map { it.y })
        assertEquals(2f, moved.stroke.widthScale, 0f)
        assertEquals(ink.id, moved.id)
    }

    @Test fun imageBoxStaysSortedWhenFlipped() {
        val flipped = image.transformed(Affine(-1f, 0f, 0f, -1f, 0f, 0f), 1f) as ImageItem
        assertEquals(Box(-110.5f, -220f, -10f, -20f), flipped.box)
    }

    @Test fun idsAreTwelveHexChars() {
        val id = ItemIds.next()
        assertEquals(12, id.length)
        assertEquals(true, id.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
