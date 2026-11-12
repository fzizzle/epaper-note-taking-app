package com.fzizzle.epapernotes.ui

/**
 * When the page may be shown again after an erase. Showing it flashes the panel (fast-ink has
 * to pause), so a run of erases is shown once, when the eraser rests, not once per erase
 * (owner, 2026-11-12: "the erased part of the screen flashes after each erase").
 *
 * The eraser rests when [restMs] have passed since the last erase and button 2 isn't held
 * near the screen any more: the owner's erases come over a second apart with the button held
 * throughout, so time alone would show every one. A run is shown after [MAX_HOLD_MS] at the
 * latest, so erased ink never stays on the panel for long.
 *
 * Plain arithmetic on times in milliseconds, so it's tested on the laptop. UI thread only.
 */
class EraseSettle(private val restMs: Long = REST_MS) {
    /** The first and the latest erase not shown yet; null when there's none. */
    private var firstAt: Long? = null
    private var lastAt = 0L

    /** An erase removed something just now. */
    fun erased(nowMs: Long) {
        if (firstAt == null) firstAt = nowMs
        lastAt = nowMs
    }

    /** The page has been shown: the next erase starts a new run. */
    fun shown() {
        firstAt = null
    }

    /**
     * How long a show must still wait: 0 when it may go now. While [eraserHeld] the answer is
     * never 0 before the limit: ask again when it's up.
     */
    fun waitMs(nowMs: Long, eraserHeld: Boolean = false): Long {
        val first = firstAt ?: return 0L
        val untilLimit = first + MAX_HOLD_MS - nowMs
        if (untilLimit <= 0) return 0L
        val rest = lastAt + restMs - nowMs
        val wait = if (eraserHeld) maxOf(rest, CHECK_MS) else maxOf(rest, 0L)
        return minOf(wait, untilLimit)
    }

    companion object {
        const val REST_MS = 400L

        /** While button 2 is held, how soon to look again. */
        const val CHECK_MS = 150L

        /** From a run's first erase to its show, at the longest. */
        const val MAX_HOLD_MS = 3000L
    }
}
