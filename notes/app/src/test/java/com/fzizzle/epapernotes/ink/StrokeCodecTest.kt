package com.fzizzle.epapernotes.ink

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokeCodecTest {
    private val pen = Stroke(StrokeKind.PEN, listOf(StrokePoint(10f, 20.5f, 0.25f), StrokePoint(11.126f, 21f, 1f)))

    @Test fun encodesPen() = assertEquals("pn2 pen 1.000 10.00,20.50,0.250 11.13,21.00,1.000", StrokeCodec.encode(pen))

    @Test fun readsPn1AsWidthOne() =
        assertEquals(1f, StrokeCodec.decode("pn1 pen 1.00,2.00,0.500")!!.widthScale, 0f)

    @Test fun widthScaleRoundTrips() {
        val s = Stroke(StrokeKind.PEN, listOf(StrokePoint(10f, 20.5f, 0.25f)), widthScale = 1.5f)
        assertEquals(s, StrokeCodec.decode(StrokeCodec.encode(s)))
    }

    @Test fun roundTrip() = assertEquals(2, StrokeCodec.decode(StrokeCodec.encode(pen))!!.points.size)

    @Test fun highlighterKind() =
        assertEquals(StrokeKind.HIGHLIGHTER, StrokeCodec.decode("pn1 hl 1.00,2.00,0.500")!!.kind)

    @Test fun singlePointStroke() = assertEquals(1, StrokeCodec.decode("pn1 pen 5.00,5.00,0.100")!!.points.size)

    @Test fun unknownVersionIsNull() = assertNull(StrokeCodec.decode("pn9 pen 1,2,3"))

    @Test fun garbageIsNull() = assertNull(StrokeCodec.decode("pn1 pen 1.00,abc"))

    @Test fun emptyPointsIsNull() = assertNull(StrokeCodec.decode("pn1 pen"))

    /** The decoder before it was made fast: plain split and toFloatOrNull. The fast one must agree with it exactly. */
    private fun referenceDecode(text: String): Stroke? {
        val parts = text.trim().split(' ')
        val version2 = parts.firstOrNull() == "pn2"
        if (!version2 && parts.firstOrNull() != "pn1") return null
        val pointsFrom = if (version2) 3 else 2
        if (parts.size <= pointsFrom) return null
        val kind = when (parts[1]) { "pen" -> StrokeKind.PEN; "hl" -> StrokeKind.HIGHLIGHTER; else -> return null }
        val widthScale = if (version2) parts[2].toFloatOrNull() ?: return null else 1f
        val points = parts.drop(pointsFrom).map { t ->
            val n = t.split(',')
            if (n.size != 3) return null
            StrokePoint(n[0].toFloatOrNull() ?: return null, n[1].toFloatOrNull() ?: return null, n[2].toFloatOrNull() ?: return null)
        }
        return Stroke(kind, points, widthScale)
    }

    private fun assertSameAsReference(text: String) {
        val expected = referenceDecode(text)
        val actual = StrokeCodec.decode(text)
        assertEquals(text, expected, actual)
        // Same bits too, so -0.0 and 0.0 aren't taken for each other.
        expected?.points?.zip(actual!!.points)?.forEach { (e, a) ->
            assertEquals(text, e.x.toRawBits(), a.x.toRawBits())
            assertEquals(text, e.y.toRawBits(), a.y.toRawBits())
            assertEquals(text, e.pressure.toRawBits(), a.pressure.toRawBits())
        }
    }

    @Test fun decodesExactlyAsBeforeOnRandomStrokes() {
        val r = Random(7)
        repeat(3000) {
            val points = List(r.nextInt(1, 40)) { StrokePoint(r.nextFloat() * 1600f - 50f, r.nextFloat() * 2400f - 50f, r.nextFloat()) }
            val kind = if (r.nextBoolean()) StrokeKind.PEN else StrokeKind.HIGHLIGHTER
            assertSameAsReference(StrokeCodec.encode(Stroke(kind, points, r.nextFloat() * 3f)))
        }
    }

    @Test fun decodesExactlyAsBeforeOnUnusualText() {
        listOf(
            "pn2 pen 1.000 -0.00,0.00,0.000", "pn2 pen 1 10,20,1", "pn2 pen 1.5 .5,5.,0.5", "pn2 pen 1.000 +1.00,2.00,0.500",
            "pn2 pen 1.000 1e2,2.00,0.500", "pn2 pen 1.000 123456789.123,2.00,0.5", "pn2 pen 1.000 0.12345678901,1,1",
            "pn2 pen 1.000 NaN,1,1", "pn2 pen 1.000 1.00,2.00", "pn2 pen 1.000 1.00,2.00,0.5,7", "pn2 pen 1.000  1.00,2.00,0.5",
            "pn2 pen 1.000 1.00,2.00,0.5 ", " pn2 pen 1.000 1.00,2.00,0.5", "pn2 pen 1.000 1.00,,0.5", "pn2 pen 1.000 -,1,1",
            "pn2 pen 1.000 1.-0,1,1", "pn2 pen x 1,1,1", "pn1 hl 3.25,4.5,0.125 7,8,9", "pn2 pen 1.000 99999999,1,1",
            "pn2 pen 1.000 16777217,1,1", "pn2 pen 1.000 1.00\t,2,3",
        ).forEach { assertSameAsReference(it) }
    }

    @Test fun ignoresDeviceLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("pn2 pen 1.000 10.00,20.50,0.250 11.13,21.00,1.000", StrokeCodec.encode(pen))
        } finally {
            Locale.setDefault(old)
        }
    }
}
