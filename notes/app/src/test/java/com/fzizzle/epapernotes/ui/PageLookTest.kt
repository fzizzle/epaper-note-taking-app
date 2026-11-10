package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLookTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun whiteStaysWhiteAndBlackStaysBlack() {
        for (contrast in Contrast.values()) {
            val t = PageLook.table(contrast)
            assertEquals("$contrast: black", 0, t[0])
            assertEquals("$contrast: white", 255, t[255])
        }
    }

    @Test fun normalChangesNothing() {
        val t = PageLook.table(Contrast.NORMAL)
        assertArrayEquals(IntArray(256) { it }, t)
        val pixels = intArrayOf(argb(255, 12, 128, 250), argb(255, 255, 235, 59), argb(80, 1, 2, 3))
        val before = pixels.copyOf()
        PageLook.apply(pixels, t)
        assertArrayEquals(before, pixels)
    }

    @Test fun strongerDarkensMidGrey() {
        val stronger = PageLook.table(Contrast.STRONGER)[128]
        val strongest = PageLook.table(Contrast.STRONGEST)[128]
        assertTrue("Stronger: $stronger", stronger < 128)
        assertTrue("Strongest $strongest below Stronger $stronger", strongest < stronger)
    }

    @Test fun tablesNeverGoBackwards() {
        for (contrast in Contrast.values()) {
            val t = PageLook.table(contrast)
            assertEquals(256, t.size)
            for (i in 1 until 256) assertTrue("$contrast at $i", t[i] >= t[i - 1])
        }
    }

    /**
     * The highlighter is StrokeStyle's HIGHLIGHTER: colour (1, 0.92, 0.23) at opacity 0.4, which
     * ItemAnnotations writes as a plain fill opacity (ExtGState /ca, normal blending). Over a
     * white page MuPDF draws each channel as 0.4 × colour + 0.6 × white:
     * red 255, green 0.968 × 255 = 247, blue 0.692 × 255 = 176. Blue is the darkest channel,
     * so it is the one that must stay well clear of the black text under the highlight.
     */
    @Test fun aPaleHighlightStaysLighterThanTheText() {
        val highlight = intArrayOf(argb(255, 255, 247, 176))
        PageLook.apply(highlight, PageLook.table(Contrast.STRONGEST))
        val c = highlight[0]
        val darkest = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        assertTrue("darkest channel $darkest", darkest >= 100)
    }

    @Test fun alphaIsLeftAlone() {
        val pixels = intArrayOf(argb(0, 128, 128, 128), argb(77, 128, 128, 128), argb(255, 128, 128, 128))
        PageLook.apply(pixels, PageLook.table(Contrast.STRONGEST))
        assertEquals(0, pixels[0] ushr 24)
        assertEquals(77, pixels[1] ushr 24)
        assertEquals(255, pixels[2] ushr 24)
        // The colour did change, so it's the alpha that was kept, not the whole pixel.
        assertTrue(((pixels[2] shr 16) and 0xFF) < 128)
    }

    @Test fun onlyTheCountedPixelsChange() {
        // A band of rows shorter than the array (the last band of a page) changes only its own part.
        val pixels = IntArray(4) { argb(255, 128, 128, 128) }
        PageLook.apply(pixels, PageLook.table(Contrast.STRONGEST), count = 2)
        assertTrue(pixels[1] != argb(255, 128, 128, 128))
        assertEquals(argb(255, 128, 128, 128), pixels[2])
        assertEquals(argb(255, 128, 128, 128), pixels[3])
    }
}
