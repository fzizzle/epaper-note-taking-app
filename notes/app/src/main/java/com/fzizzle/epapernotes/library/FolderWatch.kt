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
 * re-list and fall back.
 *
 * FileObserver can miss events on emulated external storage, so the screen also re-lists on
 * resume and on refresh; this is a convenience, not the only trigger.
 */
class FolderWatch(private val onChange: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null
    private val fire = Runnable { onChange() }

    /** Watches [folder] only (not its subfolders), replacing any earlier watch. */
    fun watch(folder: File) {
        stop()
        observer = object : FileObserver(folder, MASK) {
            override fun onEvent(event: Int, path: String?) {
                val self = event and (DELETE_SELF or MOVE_SELF) != 0
                if (!self && path?.startsWith(".") != false) return
                handler.removeCallbacks(fire)
                handler.postDelayed(fire, DEBOUNCE_MS)
            }
        }.also { it.startWatching() }
    }

    /** Stops watching and cancels a pending [onChange]. */
    fun stop() {
        observer?.stopWatching()
        observer = null
        handler.removeCallbacks(fire)
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF
    }
}
