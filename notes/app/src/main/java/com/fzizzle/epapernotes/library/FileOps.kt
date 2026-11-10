package com.fzizzle.epapernotes.library

import com.fzizzle.epapernotes.doc.ChangeJournal
import com.fzizzle.epapernotes.doc.DocumentSession
import java.io.File
import java.nio.file.Files

/** Where each PDF's last page is remembered, by path. LastPageStore keeps it in preferences. */
interface PageMemory {
    fun move(from: String, to: String)
    fun remove(path: String)
}

/**
 * The library's long-press actions on the owner's real files: new folder, rename, move, delete.
 *
 * The app's own data follows each PDF (context decision 6): its journal, which may hold writing
 * not yet in the PDF, and its remembered page. Both are keyed by the PDF's path, so a rename
 * or move that left them behind would strand the writing. So for every PDF affected (the file,
 * or every PDF under a folder):
 *
 * - first wait for its writing screen's session to finish saving and close;
 * - then do the disk operation;
 * - only if that worked, move (or, for a delete, remove) the journal and the page.
 *
 * A journal is never overwritten, and never deleted except with its own PDF (and even then
 * not one set aside as `.kept-`). If one can't be moved, it's left under its old name and
 * logged: stranded, but not lost.
 *
 * Paths are used as given, absolute and normalized but never canonicalized (Ruling L1): that's
 * how the journals and pages were keyed when the files were opened. Every call blocks (it can
 * wait for a save, and walks folders), so call it off the screen's thread.
 */
class FileOps(
    root: File,
    private val journalDir: File,
    private val lastPages: PageMemory,
    /** How long to wait for a file's session to close. Tests shorten it. */
    private val sessionWaitMs: Long = SESSION_WAIT_MS,
    /** The disk rename. Tests replace it to make the disk refuse, which a read-only folder can't when tests run as root. */
    private val moveOnDisk: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    private val root = root.normal()

    sealed interface Result {
        data object Done : Result
        /** [reason] is plain words, for a toast. */
        data class Refused(val reason: String) : Result
    }

    fun newFolder(parent: File, name: String): Result {
        val folder = parent.normal()
        if (!isFolderInLibrary(folder)) return Result.Refused("That folder isn't in the library.")
        val clean = name.trim()
        nameProblem(clean)?.let { return Result.Refused(it) }
        if (isTaken(folder, clean, except = null)) return Result.Refused("There's already a '$clean' here.")
        return if (File(folder, clean).mkdir()) Result.Done else Result.Refused("The folder couldn't be made.")
    }

    /** A file keeps its ".pdf": typing just "Week 2" gives "Week 2.pdf". */
    fun rename(target: File, newName: String): Result {
        val from = target.normal()
        targetProblem(from)?.let { return Result.Refused(it) }
        val typed = newName.trim()
        nameProblem(typed)?.let { return Result.Refused(it) }
        val keepsPdf = from.isFile && from.extension.equals("pdf", ignoreCase = true) && !typed.endsWith(".pdf", ignoreCase = true)
        val name = if (keepsPdf) "$typed.${from.extension}" else typed
        if (name == from.name) return Result.Done
        // Itself excepted, so "lecture.pdf" can become "Lecture.pdf".
        if (isTaken(from.parentFile!!, name, except = from)) return Result.Refused("There's already a '$name' here.")
        return carry(from, File(from.parentFile, name), failed = "'${from.name}' couldn't be renamed.")
    }

    fun move(target: File, into: File): Result {
        val from = target.normal()
        val folder = into.normal()
        targetProblem(from)?.let { return Result.Refused(it) }
        if (!isFolderInLibrary(folder)) return Result.Refused("That folder isn't in the library.")
        // File.startsWith compares whole names, so "Physics Lab" isn't inside "Physics".
        if (folder.startsWith(from)) return Result.Refused("A folder can't go inside itself.")
        if (folder == from.parentFile) return Result.Done
        if (isTaken(folder, from.name, except = null)) return Result.Refused("There's already a '${from.name}' in '${folder.name}'.")
        return carry(from, File(folder, from.name), failed = "'${from.name}' couldn't be moved there.")
    }

    /** Deletes the real file, or the folder and everything in it, with the app's data for each PDF. */
    fun delete(target: File): Result {
        val from = target.normal()
        targetProblem(from)?.let { return Result.Refused(it) }
        val pdfs = pdfsAt(from)
        waitForSessions(pdfs)?.let { return Result.Refused(it) }
        val allGone = deleteOnDisk(from)
        // After a part-way failure, a PDF still on disk keeps its journal and page.
        for (pdf in pdfs) {
            if (pdf.exists()) continue
            liveJournalFilesOf(pdf).forEach { if (!it.delete()) log("couldn't delete journal ${it.name}") }
            lastPages.remove(pdf.path)
        }
        return if (allGone) Result.Done else Result.Refused("Some of '${from.name}' couldn't be deleted.")
    }

    /** How many PDFs are in [folder] and all its subfolders, for the delete question. */
    fun pdfCountInside(folder: File): Int = pdfsAt(folder.normal()).size

    /** Rename or move [from] to [to] on disk, then the app's data for every PDF it holds. */
    private fun carry(from: File, to: File, failed: String): Result {
        val pdfs = pdfsAt(from)
        waitForSessions(pdfs)?.let { return Result.Refused(it) }
        // No copy-and-delete fallback: renameTo fails across storage devices, and that's refused.
        if (!moveOnDisk(from, to)) return Result.Refused(failed)
        for (pdf in pdfs) {
            val moved = if (pdf == from) to else File(to, pdf.relativeTo(from).path)
            moveJournals(pdf, moved)
            lastPages.move(pdf.path, moved.path)
        }
        return Result.Done
    }

    /** The reason to refuse, or null once every session on [pdfs] has closed. */
    private fun waitForSessions(pdfs: List<File>): String? {
        val busy = pdfs.firstOrNull { !DocumentSession.awaitClosed(it, sessionWaitMs) } ?: return null
        return "'${busy.name}' is still saving. Try again in a moment."
    }

    /**
     * Each of [pdf]'s journal files goes to the same name for [moved]. A journal already there
     * belongs to a file that's gone from that path (the name was free on disk). It's set aside
     * rather than overwritten, so ours, with the writing for this file, takes its place.
     */
    private fun moveJournals(pdf: File, moved: File) {
        val oldName = ChangeJournal.journalFileFor(journalDir, pdf).name
        val newJournal = ChangeJournal.journalFileFor(journalDir, moved)
        for (file in journalFilesOf(pdf)) {
            var dest = File(journalDir, newJournal.name + file.name.removePrefix(oldName))
            if (dest == newJournal && dest.exists() && !dest.renameTo(keptName(newJournal))) {
                log("couldn't set aside ${dest.name}")
            }
            if (dest.exists()) dest = keptName(newJournal)
            if (!file.renameTo(dest)) log("couldn't move journal ${file.name} to ${dest.name}; it stays under its old name")
        }
    }

    /** The journal, a rewrite's leftover `.new`, and any set aside as `.kept-<time>`. */
    private fun journalFilesOf(pdf: File): List<File> {
        val name = ChangeJournal.journalFileFor(journalDir, pdf).name
        return journalDir.listFiles()?.filter { it.name == name || it.name.startsWith("$name.") }.orEmpty()
    }

    /**
     * What a delete removes: the journal and a rewrite's leftover `.new`. Journals set aside as
     * `.kept-` hold earlier writing that plan 5 will rescue, and the delete question doesn't
     * mention them, so they stay.
     */
    private fun liveJournalFilesOf(pdf: File): List<File> {
        val kept = ChangeJournal.journalFileFor(journalDir, pdf).name + ".kept-"
        return journalFilesOf(pdf).filterNot { it.name.startsWith(kept) }
    }

    /** A name as DocumentSession sets journals aside, which plan 5 will turn into copies. */
    private fun keptName(journal: File): File {
        var time = System.currentTimeMillis()
        while (true) {
            val kept = File(journalDir, journal.name + ".kept-" + time)
            if (!kept.exists()) return kept
            time++
        }
    }

    /** [target] itself if it's a file, or every PDF under it, as the library would show them. */
    private fun pdfsAt(target: File): List<File> {
        if (!target.isDirectory) return listOf(target)
        val found = ArrayList<File>()
        for (child in target.listFiles().orEmpty()) {
            if (child.name.startsWith(".") || isLink(child)) continue
            if (child.isDirectory) found += pdfsAt(child)
            else if (child.isFile && child.name.endsWith(".pdf", ignoreCase = true)) found += child
        }
        return found
    }

    /** Doesn't follow links: deleting a folder must never reach outside it. Returns false if anything is left. */
    private fun deleteOnDisk(file: File): Boolean {
        var allGone = true
        if (file.isDirectory && !isLink(file)) {
            for (child in file.listFiles().orEmpty()) allGone = deleteOnDisk(child) && allGone
        }
        return file.delete() && allGone
    }

    private fun targetProblem(target: File): String? = when {
        target == root -> "The library folder itself can't be changed here."
        !isInLibrary(target) -> "That isn't in the library."
        // A link can lead anywhere, even outside the library: leave it alone.
        isLink(target) -> "'${target.name}' is a link to somewhere else, so it's left alone."
        !target.exists() -> "'${target.name}' is no longer there."
        else -> null
    }

    private fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "Type a name first."
        '/' in name -> "A name can't contain '/'."
        name.startsWith(".") -> "A name can't start with a dot."
        else -> null
    }

    /** Ignoring case, as memory cards (FAT, exFAT) do. */
    private fun isTaken(folder: File, name: String, except: File?): Boolean =
        folder.listFiles().orEmpty().any { it.name.equals(name, ignoreCase = true) && it != except }

    private fun isInLibrary(file: File) = file.startsWith(root)

    /** A linked folder could lead outside the library, so it doesn't count. */
    private fun isFolderInLibrary(folder: File) = isInLibrary(folder) && folder.isDirectory && !isLink(folder)

    private fun isLink(file: File) = Files.isSymbolicLink(file.toPath())

    private fun File.normal() = absoluteFile.normalize()

    // System.err reaches logcat on the tablet, and keeps this file free of Android code.
    private fun log(message: String) = System.err.println("FileOps: $message")

    companion object {
        /** A second or two is normal; this only gives up on a session that's stuck. */
        const val SESSION_WAIT_MS = 15_000L
    }
}
