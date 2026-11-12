package com.fzizzle.epapernotes.ui

import android.content.Context

/**
 * How the owner likes pages shown, kept between documents in the app's own preferences.
 * These are the only reading settings stored; nothing here touches the PDF.
 */
class ReadingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)

    /** How much darker the page is drawn on screen ([Contrast]). Normal until chosen. */
    var contrast: Contrast
        get() = runCatching { Contrast.valueOf(prefs.getString(CONTRAST, null) ?: "") }.getOrDefault(Contrast.NORMAL)
        set(value) = prefs.edit().putString(CONTRAST, value.name).apply()

    /** Whether nearly grey text is drawn black on screen (PageLook). On until turned off (owner, 2026-11-10). */
    var darkTextAsBlack: Boolean
        get() = prefs.getBoolean(DARK_TEXT_AS_BLACK, true)
        set(value) = prefs.edit().putBoolean(DARK_TEXT_AS_BLACK, value).apply()

    /** Whether pages are fitted to their printed area on screen, not the whole sheet (ContentBounds). Off until chosen. */
    var trimMargins: Boolean
        get() = prefs.getBoolean(TRIM_MARGINS, false)
        set(value) = prefs.edit().putBoolean(TRIM_MARGINS, value).apply()

    /** Whether a page turn keeps the zoom and the place on the page. On until turned off (owner, 2026-11-12). */
    var keepZoom: Boolean
        get() = prefs.getBoolean(KEEP_ZOOM, true)
        set(value) = prefs.edit().putBoolean(KEEP_ZOOM, value).apply()

    /** How many times a copy has said how to paste (CopyMessage). */
    var copyHintsShown: Int
        get() = prefs.getInt(COPY_HINTS_SHOWN, 0)
        set(value) = prefs.edit().putInt(COPY_HINTS_SHOWN, value).apply()

    private companion object {
        const val CONTRAST = "contrast"
        const val DARK_TEXT_AS_BLACK = "darkTextAsBlack"
        const val TRIM_MARGINS = "trimMargins"
        const val KEEP_ZOOM = "keepZoom"
        const val COPY_HINTS_SHOWN = "copyHintsShown"
    }
}
