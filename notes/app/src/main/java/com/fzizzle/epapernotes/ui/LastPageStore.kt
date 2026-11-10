package com.fzizzle.epapernotes.ui

import android.content.Context
import com.fzizzle.epapernotes.library.PageMemory

/** The page each PDF was last left on, in the app's private storage, never in the owner's folders (spec §6.1). */
class LastPageStore(context: Context) : PageMemory {
    private val prefs = context.getSharedPreferences("last-page", Context.MODE_PRIVATE)

    fun get(path: String): Int = prefs.getInt(path, 0)

    fun set(path: String, page: Int) {
        prefs.edit().putInt(path, page).apply()
    }

    /** A renamed or moved file keeps its page. */
    override fun move(from: String, to: String) {
        if (!prefs.contains(from)) return
        prefs.edit().putInt(to, prefs.getInt(from, 0)).remove(from).apply()
    }

    override fun remove(path: String) {
        prefs.edit().remove(path).apply()
    }
}
