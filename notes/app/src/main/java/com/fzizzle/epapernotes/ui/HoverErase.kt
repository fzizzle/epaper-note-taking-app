package com.fzizzle.epapernotes.ui

/**
 * Button 2 pressed while the pen already hovers close to the screen. The owner's pen then
 * never reports touching: the driver gets the button and the tip's pressure but no touch, and
 * the pen stays a pen instead of turning into the eraser (raw events, 2026-11-12). So Onyx
 * neither draws nor erases, and Android sends only hover events, with the button flag and
 * pressure (about 0.1). Plain hovering always reads 0. This turns those hover samples back
 * into an eraser trail, in the page view's pixels.
 *
 * Fed on the UI thread from the page view's hover events.
 */
class HoverErase {
    private val trail = mutableListOf<FloatArray>()

    /**
     * One hover sample. Returns the finished trail when the press ends (the pen lifts or the
     * button is let go), else null.
     */
    fun sample(buttonHeld: Boolean, pressure: Float, x: Float, y: Float): List<FloatArray>? {
        if (buttonHeld && pressure > MIN_PRESSURE) {
            trail += floatArrayOf(x, y)
            return null
        }
        return ended()
    }

    /** The pen left the screen's range: the trail so far, if any. */
    fun ended(): List<FloatArray>? = if (trail.isEmpty()) null else trail.toList().also { trail.clear() }

    /** A real touch came after all: Onyx's eraser or the screen has it, so drop the trail. */
    fun cancel() = trail.clear()

    private companion object {
        /** Well below a press (0.07 at the lightest seen) and above nothing. */
        const val MIN_PRESSURE = 0.02f
    }
}
