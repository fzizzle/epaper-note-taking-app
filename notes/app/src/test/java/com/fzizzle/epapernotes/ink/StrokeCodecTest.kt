package com.fzizzle.epapernotes.ink

import java.util.Locale
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
