package com.fzizzle.epapernotes.library

/**
 * "Start in the last document" (a setting, off unless turned on): where the app begins.
 * A rule of its own, tested on the laptop, because getting it wrong could lock the owner out:
 * a document that crashes the app as it opens would crash it at every start.
 */
object LaunchOpen {
    enum class Start {
        LIBRARY,
        LAST_DOCUMENT,

        /** The library, and say why: the last try to open the document this way never finished. */
        LIBRARY_AFTER_FAILED_OPEN,
    }

    /**
     * [freshStart]: the app was started from nothing, from the launcher, not come back to.
     * [lastExists]: the document opened last is still there. [unfinished]: the last such open
     * was begun and never got as far as showing the document.
     */
    fun decide(freshStart: Boolean, settingOn: Boolean, lastExists: Boolean, unfinished: Boolean): Start = when {
        !freshStart || !settingOn -> Start.LIBRARY
        unfinished -> Start.LIBRARY_AFTER_FAILED_OPEN
        !lastExists -> Start.LIBRARY
        else -> Start.LAST_DOCUMENT
    }
}
