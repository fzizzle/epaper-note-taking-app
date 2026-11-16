package com.fzizzle.epapernotes.ui

/**
 * Whether a pen stroke has begun and its points haven't reached the app yet. Onyx reports the
 * lift first and hands the points over a moment later, so for that moment the pen is no longer
 * writing but the stroke isn't recorded either. A reload of a changed file must wait it out:
 * opened in that gap, the new version would get a stroke written on the old one's pages.
 *
 * Points that never come (a stroke Onyx keeps for a shortcut, or one a pause cut short) stop
 * counting [WAIT_MS] after the lift or the cut, so they can't hold a reload off for ever.
 *
 * Plain arithmetic on times in milliseconds, so it's tested on the laptop. Written on Onyx's
 * thread ([cut] on the UI thread), read on the UI thread.
 */
class StrokeHandover {
    @Volatile private var begun = false
    @Volatile private var liftedAt = NOT_LIFTED

    /** The pen came down: a stroke is on its way. */
    fun begun() {
        liftedAt = NOT_LIFTED
        begun = true
    }

    /** The pen lifted; the points may still be to come. */
    fun lifted(nowMs: Long) {
        liftedAt = nowMs
    }

    /** The points have been handed over, and recorded. */
    fun handedOver() {
        begun = false
    }

    /**
     * Fast-ink was paused. A stroke under way is cut short, and Onyx may send no lift for it,
     * or no points: its wait starts now, as at a lift. One already lifted keeps its wait.
     */
    fun cut(nowMs: Long) {
        if (begun && liftedAt == NOT_LIFTED) liftedAt = nowMs
    }

    fun coming(nowMs: Long): Boolean {
        if (!begun) return false
        val lifted = liftedAt
        return lifted == NOT_LIFTED || nowMs - lifted < WAIT_MS
    }

    companion object {
        /** How long after the lift points that haven't come stop counting. Onyx hands them over within milliseconds. */
        const val WAIT_MS = 1_000L

        private const val NOT_LIFTED = -1L
    }
}
