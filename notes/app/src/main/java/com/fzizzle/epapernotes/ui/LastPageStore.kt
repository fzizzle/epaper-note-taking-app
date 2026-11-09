package com.fzizzle.epapernotes.ui

import android.content.Context

/** The page each PDF was last left on, in the app's private storage, never in the owner's folders (spec §6.1). */
class LastPageStore(context: Context) {
    private val prefs = context.getSharedPreferences("last-page", Context.MODE_PRIVATE)

    fun get(path: String): Int = prefs.getInt(path, 0)

    fun set(path: String, page: Int) {
        prefs.edit().putInt(path, page).apply()
    }
}
