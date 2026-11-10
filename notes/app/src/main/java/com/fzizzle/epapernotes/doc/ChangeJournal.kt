package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.storage.FileIdentity
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What a journal holds: the version of the PDF it was started on, and the changes since. */
data class JournalContents(val base: FileIdentity, val changes: List<Change>)

/**
 * A small file in the app's private storage that keeps every change from the moment the pen
 * lifts, until the PDF itself has been saved (spec §7.3). If the app is killed or the power
 * goes before the autosave, the changes are read back from here on the next open.
 *
 * Plain text, one item per line:
 *
 *     pnj2 <size> <modified> <sha256>      the PDF version this journal started on
 *     <change line>                        see ChangeCodec
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
     * a half-written one that has lost changes.
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
    }

    /** Adds one change, and returns only once it's on the storage chip. */
    fun append(change: Change) {
        write(ChangeCodec.encode(change) + "\n", append = true)
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
        val changes = complete.drop(1).mapNotNull { if (plan2) parseOldAdd(it) else ChangeCodec.decode(it) }
        return JournalContents(base, changes)
    }

    fun clear() {
        file.delete()
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
