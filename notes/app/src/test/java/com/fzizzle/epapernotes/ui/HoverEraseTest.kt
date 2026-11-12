package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoverEraseTest {
    private val erase = HoverErase()

    private fun List<FloatArray>.xs() = map { it[0] }

    @Test fun plainHoverGivesNothing() {
        assertNull(erase.sample(buttonHeld = false, pressure = 0f, x = 1f, y = 1f))
        assertNull(erase.sample(buttonHeld = true, pressure = 0f, x = 2f, y = 1f)) // button held, pen in the air
        assertNull(erase.ended())
    }

    @Test fun pressingWithTheButtonHeldIsATrail() {
        assertNull(erase.sample(buttonHeld = true, pressure = 0f, x = 0f, y = 0f))
        assertNull(erase.sample(buttonHeld = true, pressure = 0.1f, x = 1f, y = 0f))
        assertNull(erase.sample(buttonHeld = true, pressure = 0.12f, x = 2f, y = 0f))
        // The pen lifts: pressure back to nothing ends the trail.
        assertEquals(listOf(1f, 2f), erase.sample(buttonHeld = true, pressure = 0f, x = 3f, y = 0f)!!.xs())
        assertNull(erase.ended()) // already handed over
    }

    @Test fun leavingTheScreenEndsTheTrail() {
        erase.sample(buttonHeld = true, pressure = 0.1f, x = 5f, y = 6f)
        val trail = erase.ended()!!
        assertEquals(5f, trail.single()[0])
        assertEquals(6f, trail.single()[1])
    }

    @Test fun lettingGoOfTheButtonEndsTheTrail() {
        erase.sample(buttonHeld = true, pressure = 0.1f, x = 1f, y = 0f)
        assertEquals(listOf(1f), erase.sample(buttonHeld = false, pressure = 0.1f, x = 2f, y = 0f)!!.xs())
    }

    @Test fun aRealTouchDropsTheTrail() {
        // If the pen does report a touch after all, Onyx's own eraser has it: no second erase.
        erase.sample(buttonHeld = true, pressure = 0.1f, x = 1f, y = 0f)
        erase.cancel()
        assertNull(erase.ended())
    }

    @Test fun noiseIsNotAPress() {
        assertNull(erase.sample(buttonHeld = true, pressure = 0.005f, x = 1f, y = 0f))
        assertNull(erase.ended())
    }
}
