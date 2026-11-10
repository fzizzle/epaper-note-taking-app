package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.storage.FileIdentity
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * What a journal holds: the version of the PDF it was started on, and the changes since.
 * [pendingSave] is the last save the journal saw begin and never saw end, if any.
 */
data class JournalContents(val base: FileIdentity, val changes: List<Change>, val pendingSave: PendingSave? = null)

/**
 * A save that was about to swap a new PDF in: [result] is what that PDF looks like, and it
 * holds the journal's first [count] changes. If the file on disk is [result], the swap
 * happened, and only the changes after those still need putting back.
 */
data class PendingSave(val result: FileIdentity, val count: Int)

/**
 * A small file in the app's private storage that keeps every change from the moment the pen
 * lifts, until the PDF itself has been saved (spec §7.3). If the app is killed or the power
 * goes before the autosave, the changes are read back from here on the next open.
 *
 * Plain text, one item per line:
 *
 *     pnj2 <size> <modified> <sha256>      the PDF version this journal started on
 *     <change line>                        see ChangeCodec
 *     saving <size> <modified> <sha256> <count>
 *                                          a save is about to swap in a PDF that looks like
 *                                          this and holds the first <count> changes above
 *
 * The `saving` line is for a kill between the PDF being swapped in and the journal being
 * rewritten: without it the journal's base no longer matches the file, and writing that is
 * safely in the PDF would look like writing for a file that has changed. A build from before
 * this line existed just drops it, as it does any line it doesn't know.
 *
 * Only lines that end in a newline count. A power cut can leave the last line cut short, and
 * a cut-short line could still look valid, just with points missing.
 *
 * A plan-2 journal (`pnj1`, with `add <page> <stroke text>` lines) is still read: each stroke
 * becomes an added ink item.
 */
class ChangeJournal(val file: File) {

    /** Starts afresh for [base], dropping anything written before. */
    fun start(base: FileIdentity) = rewrite(base, emptyList())

    /**
     * Replaces the journal with [base] and [changes], all at once: written to a temporary file,
     * synced, then moved over the old one. A crash midway leaves the old journal whole, never
     * a half-written one that has lost changes. The folder is flushed too, as for the PDF:
     * otherwise a power cut right after a save could bring the old journal back.
     */
    fun rewrite(base: FileIdentity, changes: List<Change>) {
        file.parentFile?.mkdirs()
        val text = buildString {
            append("$HEADER ${base.encode()}\n")
            changes.forEach { append(ChangeCodec.encode(it)).append('\n') }
        }
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        SafeSave.syncFolder(file)
    }

    /** Adds one change, and returns only once it's on the storage chip. */
    fun append(change: Change) {
        write(ChangeCodec.encode(change) + "\n", append = true)
    }

    /**
     * Notes that a save is about to swap in a PDF that looks like [result] and holds the
     * first [count] changes of this journal. Returns only once it's on the storage chip.
     */
    fun markSaving(result: FileIdentity, count: Int) {
        write("$SAVING ${result.encode()} $count\n", append = true)
    }

    /** Returns null when there's no journal, or its first line isn't a valid header. */
    fun read(): JournalContents? {
        if (!file.exists()) return null
        val text = file.readText()
        // Everything after the last newline is a line the power cut interrupted.
        val complete = text.substring(0, text.lastIndexOf('\n') + 1).lines().filter { it.isNotEmpty() }
        val header = complete.firstOrNull() ?: return null
        val plan2 = header.startsWith("$OLD_HEADER ")
        if (!plan2 && !header.startsWith("$HEADER ")) return null
        val base = FileIdentity.decode(header.substringAfter(' ')) ?: return null
        val changes = ArrayList<Change>()
        var pendingSave: PendingSave? = null
        for (line in complete.drop(1)) {
            if (line.startsWith("$SAVING ")) {
                // Only the last one counts: an earlier one is a save that failed and was tried again.
                parseSaving(line)?.let { pendingSave = it }
                continue
            }
            (if (plan2) parseOldAdd(line) else ChangeCodec.decode(line))?.let { changes += it }
        }
        return JournalContents(base, changes, pendingSave)
    }

    fun clear() {
        file.delete()
    }

    private fun parseSaving(line: String): PendingSave? {
        val parts = line.split(' ')
        if (parts.size != 5) return null
        val result = FileIdentity.decode(parts.subList(1, 4).joinToString(" ")) ?: return null
        val count = parts[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return PendingSave(result, count)
    }

    private fun parseOldAdd(line: String): Change? {
        val parts = line.split(' ', limit = 3)
        if (parts.size != 3 || parts[0] != "add") return null
        val page = parts[1].toIntOrNull() ?: return null
        val stroke = StrokeCodec.decode(parts[2]) ?: return null
        return AddItems(page, listOf(InkItem(ItemIds.next(), stroke)))
    }

    private fun write(text: String, append: Boolean) {
        FileOutputStream(file, append).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            // The whole point of the journal: don't return until the bytes are really stored.
            out.fd.sync()
        }
    }

    companion object {
        private const val HEADER = "pnj2"
        private const val OLD_HEADER = "pnj1"
        private const val SAVING = "saving"

        /** One journal per PDF, named after a fingerprint of its full path. */
        fun forDocument(journalDir: File, pdf: File): ChangeJournal = ChangeJournal(journalFileFor(journalDir, pdf))

        /**
         * The journal file for [pdf]. Every other file of the PDF's in [journalDir] starts with
         * this name too: the temporary `.new` of a rewrite, and journals set aside as `.kept-<time>`.
         */
        fun journalFileFor(journalDir: File, pdf: File): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(pdf.absolutePath.toByteArray(Charsets.UTF_8))
            val name = digest.joinToString("") { "%02x".format(it) }
            return File(journalDir, "$name.journal")
        }
    }
}
