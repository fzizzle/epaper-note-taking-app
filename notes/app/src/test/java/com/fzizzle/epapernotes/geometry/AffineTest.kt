package com.fzizzle.epapernotes.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class AffineTest {
    private fun assertNear(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, 1e-4f)
        assertEquals(expected.y, actual.y, 1e-4f)
    }

    @Test fun mapsPoint() = assertNear(Pt(7f, 13f), Affine(2f, 0f, 0f, 3f, 5f, 7f).map(Pt(1f, 2f)))

    @Test fun inverseUndoes() {
        val m = Affine(0f, 1f, -1f, 0f, 792f, -100f) // a 90° turn with an offset
        assertNear(Pt(150f, 842f), m.inverse().map(m.map(Pt(150f, 842f))))
    }

    @Test fun thenAppliesInOrder() {
        val m = Affine.scale(2f).then(Affine.translate(10f, 0f)) // scale first, then move
        assertNear(Pt(12f, 4f), m.map(Pt(1f, 2f)))
    }
}
