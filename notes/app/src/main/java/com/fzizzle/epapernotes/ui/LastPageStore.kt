package com.fzizzle.epapernotes.ui

import android.content.Context
import com.fzizzle.epapernotes.library.PageMemory

/**
 * The page each PDF was last left on, and how many pages it had then (for the library's
 * progress bars), in the app's private storage, never in the owner's folders (spec §6.1).
 */
class LastPageStore(context: Context) : PageMemory {
    private val prefs = context.getSharedPreferences("last-page", Context.MODE_PRIVATE)
    // In a file of their own: the pages' file holds nothing but paths, and stays as it was.
    private val counts = context.getSharedPreferences("page-count", Context.MODE_PRIVATE)

    fun get(path: String): Int = prefs.getInt(path, 0)

    /** Whether [path] has ever been left on a page: that is, opened. */
    fun has(path: String): Boolean = prefs.contains(path)

    /** How many pages [path] had when it was last left; 0 when that isn't known. */
    fun pageCount(path: String): Int = counts.getInt(path, 0)

    fun set(path: String, page: Int) {
        prefs.edit().putInt(path, page).apply()
    }

    fun set(path: String, page: Int, pageCount: Int) {
        set(path, page)
        counts.edit().putInt(path, pageCount).apply()
    }

    /**
     * A renamed or moved file keeps its page. One that had none (never opened) takes none: a
     * page left over for an earlier file of that name (deleted outside the app, say) is dropped.
     */
    override fun move(from: String, to: String) {
        if (!prefs.contains(from)) {
            counts.edit().remove(to).apply()
            return prefs.edit().remove(to).apply()
        }
        prefs.edit().putInt(to, prefs.getInt(from, 0)).remove(from).apply()
        if (counts.contains(from)) counts.edit().putInt(to, counts.getInt(from, 0)).remove(from).apply()
        else counts.edit().remove(to).apply()
    }

    override fun remove(path: String) {
        prefs.edit().remove(path).apply()
        counts.edit().remove(path).apply()
    }
}
