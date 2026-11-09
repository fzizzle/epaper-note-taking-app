package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.storage.FileIdentity
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A stroke written down in the journal but maybe not yet saved into the PDF. */
data class PendingStroke(val page: Int, val stroke: Stroke)

/** What a journal holds: the version of the PDF it was started on, and the strokes since. */
data class JournalContents(val base: FileIdentity, val strokes: List<PendingStroke>)

/**
 * A small file in the app's private storage that keeps every stroke from the moment the pen
 * lifts, until the PDF itself has been saved (spec §7.3). If the app is killed or the power
 * goes before the autosave, the strokes are read back from here on the next open.
 *
 * Plain text, one item per line:
 *
 *     pnj1 <size> <modified> <sha256>      the PDF version this journal started on
 *     add <page> <stroke text>             one stroke (see StrokeCodec)
 *
 * Only lines that end in a newline count. A power cut can leave the last line cut short, and
 * a cut-short stroke line could still look valid, just with points missing.
 */
class StrokeJournal(val file: File) {

    /** Starts afresh for [base], dropping anything written before. */
    fun start(base: FileIdentity) = rewrite(base, emptyList())

    /**
     * Replaces the journal with [base] and [strokes], all at once: written to a temporary file,
     * synced, then moved over the old one. A crash midway leaves the old journal whole, never
     * a half-written one that has lost strokes.
     */
    fun rewrite(base: FileIdentity, strokes: List<PendingStroke>) {
        file.parentFile?.mkdirs()
        val text = buildString {
            append("$HEADER ${base.encode()}\n")
            strokes.forEach { append("add ${it.page} ${StrokeCodec.encode(it.stroke)}\n") }
        }
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Adds one stroke, and returns only once it's on the storage chip. */
    fun append(page: Int, stroke: Stroke) {
        write("add $page ${StrokeCodec.encode(stroke)}\n", append = true)
    }

    /** Returns null when there's no journal, or its first line isn't a valid header. */
    fun read(): JournalContents? {
        if (!file.exists()) return null
        val text = file.readText()
        // Everything after the last newline is a line the power cut interrupted.
        val complete = text.substring(0, text.lastIndexOf('\n') + 1).lines().filter { it.isNotEmpty() }
        val header = complete.firstOrNull() ?: return null
        if (!header.startsWith("$HEADER ")) return null
        val base = FileIdentity.decode(header.removePrefix("$HEADER ")) ?: return null
        val strokes = complete.drop(1).mapNotNull { parseAdd(it) }
        return JournalContents(base, strokes)
    }

    fun clear() {
        file.delete()
    }

    private fun parseAdd(line: String): PendingStroke? {
        val parts = line.split(' ', limit = 3)
        if (parts.size != 3 || parts[0] != "add") return null
        val page = parts[1].toIntOrNull() ?: return null
        val stroke = StrokeCodec.decode(parts[2]) ?: return null
        return PendingStroke(page, stroke)
    }

    private fun write(text: String, append: Boolean) {
        FileOutputStream(file, append).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            // The whole point of the journal: don't return until the bytes are really stored.
            out.fd.sync()
        }
    }

    companion object {
        private const val HEADER = "pnj1"

        /** One journal per PDF, named after a fingerprint of its full path. */
        fun forDocument(journalDir: File, pdf: File): StrokeJournal {
            val digest = MessageDigest.getInstance("SHA-256").digest(pdf.absolutePath.toByteArray(Charsets.UTF_8))
            val name = digest.joinToString("") { "%02x".format(it) }
            return StrokeJournal(File(journalDir, "$name.journal"))
        }
    }
}
