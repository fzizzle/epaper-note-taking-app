package com.fzizzle.epapernotes.library

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Tells the library when the folder on show changed on disk (a laptop, sync app or USB wrote
 * to it). Events come in bursts (a copy fires several), so [onChange] runs once, on the main
 * thread, [DEBOUNCE_MS] after the last event. Dot-names (including temporary save files) are
 * ignored. If the folder itself is deleted or moved, [onChange] still runs so the screen can
 * re-list and fall back, and [needsRewatch] says the watch must be started again: a folder
 * recreated under the same name is a new folder to the system, and the old watch sees nothing.
 *
 * The subfolders whose tiles are on screen are watched too ([watchChildren]), for files
 * arriving in them or leaving, so their "N PDFs" stays right (spec §6.1).
 *
 * FileObserver can miss events on emulated external storage, so the screen also re-lists on
 * resume and on refresh; this is a convenience, not the only trigger.
 */
class FolderWatch(private val onChange: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null
    private val fire = Runnable { onChange() }

    /** The folder [watch] was last given, or null once stopped. Main thread. */
    var watching: File? = null
        private set

    /** Subfolders watched for [watchChildren], by folder. Main thread. */
    private val children = mutableMapOf<File, FileObserver>()

    // Set on the observer's thread when the folder itself went (deleted or moved).
    @Volatile private var sawSelfEvent = false

    /**
     * True once since the folder watched was deleted or moved away: call [watch] again for it
     * (it may have been recreated). Reading it clears it.
     */
    val needsRewatch: Boolean
        get() = sawSelfEvent.also { sawSelfEvent = false }

    /**
     * Watches [folder] only (not its subfolders: see [watchChildren]), replacing any earlier
     * watch, of this folder too. A change already seen still calls [onChange]. Main thread.
     */
    fun watch(folder: File) {
        observer?.stopWatching()
        if (folder != watching) stopChildren() // they were the old folder's
        watching = folder
        sawSelfEvent = false
        observer = object : FileObserver(folder, MASK) {
            override fun onEvent(event: Int, path: String?) {
                val self = event and (DELETE_SELF or MOVE_SELF) != 0
                if (self) sawSelfEvent = true
                if (!self && path?.startsWith(".") != false) return
                changed()
            }
        }.also { it.startWatching() }
    }

    /**
     * Also watches [folders] (subfolders of the one on show, at most [MAX_CHILDREN]) for files
     * arriving, leaving or being renamed, replacing the last set. Folders already watched keep
     * their watch. Main thread.
     */
    fun watchChildren(folders: List<File>) {
        val wanted = folders.take(MAX_CHILDREN).toSet()
        (children.keys - wanted).forEach { children.remove(it)?.stopWatching() }
        for (folder in wanted) {
            if (folder in children) continue
            children[folder] = object : FileObserver(folder, CHILD_MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path.startsWith(".")) return
                    changed()
                }
            }.also { it.startWatching() }
        }
    }

    /** Stops watching, the subfolders too, and cancels a pending [onChange]. */
    fun stop() {
        observer?.stopWatching()
        observer = null
        watching = null
        stopChildren()
        handler.removeCallbacks(fire)
    }

    private fun stopChildren() {
        children.values.forEach { it.stopWatching() }
        children.clear()
    }

    /** Any thread. */
    private fun changed() {
        handler.removeCallbacks(fire)
        handler.postDelayed(fire, DEBOUNCE_MS)
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF

        /**
         * A subfolder's tile shows how many PDFs and folders it holds. A small one is shown
         * opened out, as its PDFs themselves, so a file that has just been written through
         * counts too (CLOSE_WRITE): one copied straight in is only whole then, and one written
         * over in place gives no other sign.
         */
        const val CHILD_MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE

        /** Folders with tiles on screen are watched, so this is plenty; it keeps well inside the system's limit. */
        const val MAX_CHILDREN = 64
    }
}
