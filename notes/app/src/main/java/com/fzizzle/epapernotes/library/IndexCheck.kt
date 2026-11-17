package com.fzizzle.epapernotes.library

import java.io.File
import java.io.IOException

/**
 * Brings the saved name index in step with the card, from the library folder [root] down. A
 * folder whose time hasn't moved since it was read is trusted and not listed again; one that
 * moved (or is new) is listed and its PDFs replaced. If the library folder goes missing, the
 * check stops and changes nothing more: a card taken out must never look like deleted folders.
 *
 * Store writes go [batch] folders at a time; between batches the check waits while [pause]
 * says so (a document is open).
 *
 * A folder [disk] can't read (a flaky card, permission denied) is told to [onError] and kept
 * exactly as the index holds it, everything under it too; the rest of the library is still
 * checked. A full run that had to keep one this way doesn't mark the index [IndexStore.complete].
 */
class IndexCheck(
    private val root: File,
    private val store: IndexStore,
    private val disk: IndexDisk,
    private val clock: () -> Long,
    private val pause: () -> Boolean = { false },
    private val batch: Int = 50,
    private val onError: (File, Exception) -> Unit = { _, _ -> },
) {
    /** One folder to visit. [reread]: list it whatever its time, and go only into subfolders the index doesn't hold yet. */
    private class Visit(val path: String, val reread: Boolean = false)

    private enum class Outcome { LISTED, TRUSTED, DROPPED, UNREADABLE, ROOT_MISSING }

    /** What a walk did: folders listed, and whether any had to be kept unread. */
    private class Walked(var listed: Int = 0, var keptUnread: Boolean = false)

    /** Checks the whole library. [full] lists every folder, trusting no folder time. */
    fun run(full: Boolean): CheckResult {
        if (rootMissing()) return CheckResult.RootMissing
        val walked = walk(listOf(Visit("")), full) ?: return CheckResult.RootMissing
        if (full && !walked.keptUnread) store.complete = true
        return CheckResult.Done(walked.listed)
    }

    /**
     * Re-reads [folders] Jot just listed or changed, as changed folders: vanished subfolders
     * dropped, new ones read in full, known ones left to the next check. One no longer there is
     * dropped, and one that can't be read is kept as it is; one outside the library, or whose
     * parent the index doesn't hold yet (the next check reads it as new), is skipped.
     */
    fun reread(folders: List<File>) {
        val visits = folders.mapNotNull { pathOf(it) }
            .filter { it.isEmpty() || store.folder(parentOf(it)) != null }
            .map { Visit(it, reread = true) }
        if (visits.isEmpty() || rootMissing()) return
        walk(visits, full = false)
    }

    /** Visits [start] and what lies under them, depth first. Null if the library folder went missing. */
    private fun walk(start: List<Visit>, full: Boolean): Walked? {
        val todo = ArrayDeque(start.reversed())
        val walked = Walked()
        while (true) {
            val stopped = store.inBatch {
                var visited = 0
                while (visited < batch && todo.isNotEmpty()) {
                    when (visit(todo.removeLast(), full, todo)) {
                        Outcome.LISTED -> walked.listed++
                        Outcome.UNREADABLE -> walked.keptUnread = true
                        Outcome.ROOT_MISSING -> return@inBatch true
                        Outcome.TRUSTED, Outcome.DROPPED -> Unit
                    }
                    visited++
                }
                false
            }
            if (stopped) return null
            if (todo.isEmpty()) return walked
            if (rootMissing()) return null
            while (pause()) Thread.sleep(PAUSE_MS)
        }
    }

    /** Visits one folder, adding the subfolders still to visit to [todo]. */
    private fun visit(visit: Visit, full: Boolean, todo: ArrayDeque<Visit>): Outcome {
        val path = visit.path
        val folder = fileOf(path)
        val time = try {
            disk.timeOf(folder)
        } catch (e: IOException) {
            return unreadable(folder, e)
        } ?: return gone(path)
        val known = store.folder(path)
        if (!visit.reread && !full && known != null && time == known.time && known.readAt - time >= TRUST_AFTER_MS) {
            known.folders.asReversed().forEach { todo.addLast(Visit(childOf(path, it))) }
            return Outcome.TRUSTED
        }
        val read = try {
            disk.read(folder)
        } catch (e: IOException) {
            return unreadable(folder, e)
        } ?: return gone(path)
        store.put(path, read, clock())
        val held = known?.folders.orEmpty()
        val vanished = held - read.folders.toSet()
        if (vanished.isNotEmpty() && rootMissing()) return Outcome.ROOT_MISSING
        vanished.forEach { store.drop(childOf(path, it)) }
        val next = if (visit.reread) read.folders - held.toSet() else read.folders
        next.asReversed().forEach { todo.addLast(Visit(childOf(path, it))) }
        return Outcome.LISTED
    }

    /** A folder that isn't there: dropped, unless the library folder itself is gone too. */
    private fun gone(path: String): Outcome {
        if (rootMissing()) return Outcome.ROOT_MISSING
        store.drop(path)
        return Outcome.DROPPED
    }

    /** Nothing put, nothing dropped, nothing under it visited: an unreadable folder is not a deleted one. */
    private fun unreadable(folder: File, e: IOException): Outcome {
        onError(folder, e)
        return Outcome.UNREADABLE
    }

    /** A library folder that can't even be looked at counts as missing: nothing is changed. */
    private fun rootMissing(): Boolean =
        try {
            disk.timeOf(root) == null
        } catch (e: IOException) {
            onError(root, e)
            true
        }

    private fun fileOf(path: String) = if (path.isEmpty()) root else File(root, path)

    /** [folder]'s path from the library folder, or null if it isn't inside it. */
    private fun pathOf(folder: File): String? {
        val base = root.toPath().toAbsolutePath().normalize()
        val target = folder.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(base)) return null
        return base.relativize(target).joinToString("/")
    }

    private fun parentOf(path: String) = if ('/' in path) path.substringBeforeLast('/') else ""

    private fun childOf(path: String, name: String) = if (path.isEmpty()) name else "$path/$name"

    companion object {
        /** A folder read within this long of its time may have changed again in the same second (card times are kept to the second). */
        const val TRUST_AFTER_MS = 2000L

        private const val PAUSE_MS = 500L
    }
}
