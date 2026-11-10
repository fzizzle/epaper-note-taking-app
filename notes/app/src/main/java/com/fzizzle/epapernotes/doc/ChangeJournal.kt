package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.storage.FileIdentity
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

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
 *     img <name> <base64 png>              a snip's picture; <name> is the sha256 of the PNG
 *     <change line>                        see ChangeCodec; a snip in it says `@<name>` where
 *                                          its picture would be
 *     saving <size> <modified> <sha256> <count>
 *                                          a save is about to swap in a PDF that looks like
 *                                          this and holds the first <count> changes above
 *
 * The `saving` line is for a kill between the PDF being swapped in and the journal being
 * rewritten: without it the journal's base no longer matches the file, and writing that is
 * safely in the PDF would look like writing for a file that has changed. A build from before
 * this line existed just drops it, as it does any line it doesn't know.
 *
 * A snip's picture is written once per journal file, the first time a change mentions it, in
 * the same write as that change and just before it. Every later mention (the snip moved,
 * erased, brought back by undo) is the short name. Before, each of those lines carried the
 * whole picture, written and synced while the pen waited: a 200 KB snip moved ten times made
 * a 5.6 MB journal. A `img` line is not a change: the `saving` line's <count> counts change
 * lines only. Journals from before this, with the picture inside each line, read as they did.
 *
 * A change naming a picture the file has no whole line for is dropped when read, like any
 * line that can't be made sense of. That can't come from a power cut, since the picture is
 * always written before the first line that names it. It is also what a build from before
 * `img` lines would do with such a change, if one were ever installed over this one while a
 * journal held unsaved snips: it would drop those changes without a word. Nothing guards
 * against that; installing an older build over a newer one isn't done.
 *
 * Only lines that end in a newline count. A power cut can leave the last line cut short, and
 * a cut-short line could still look valid, just with points missing.
 *
 * A plan-2 journal (`pnj1`, with `add <page> <stroke text>` lines) is still read: each stroke
 * becomes an added ink item.
 */
class ChangeJournal(val file: File) {

    /**
     * The names of the pictures the file has a line for. Null means "not known": the next
     * [append] looks in the file itself. It's null whenever there could be any doubt: for a
     * journal object made on a file that's already there (after a crash), and after any write
     * that failed. Believing a picture is in the file when it isn't would lose a snip, while
     * looking again only costs a read, so doubt always means looking.
     */
    private var written: MutableSet<String>? = null

    // The picture named last, and its name. A move mentions the same picture twice, and the
    // next move mentions it again, so this saves working the name out each time.
    private var lastPicture: ByteArray? = null
    private var lastName = ""

    /** Starts afresh for [base], dropping anything written before. */
    fun start(base: FileIdentity) = rewrite(base, emptyList())

    /**
     * Replaces the journal with [base] and [changes], all at once: written to a temporary file,
     * synced, then moved over the old one. A crash midway leaves the old journal whole, never
     * a half-written one that has lost changes. The folder is flushed too, as for the PDF:
     * otherwise a power cut right after a save could bring the old journal back.
     */
    fun rewrite(base: FileIdentity, changes: List<Change>) {
        // Not known until the new file is in place: if this fails midway, the old file stays.
        written = null
        file.parentFile?.mkdirs()
        // The pictures these changes mention, each once, ahead of all the changes. Pictures
        // only the dropped changes mentioned aren't carried over.
        val names = HashSet<String>()
        val pictures = StringBuilder()
        val lines = StringBuilder()
        changes.forEach { lines.append(line(it, names, pictures)).append('\n') }
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { out ->
            out.write("$HEADER ${base.encode()}\n".toByteArray(Charsets.UTF_8))
            out.write(pictures.toString().toByteArray(Charsets.UTF_8))
            out.write(lines.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        SafeSave.syncFolder(file)
        written = names
    }

    /**
     * Adds one change, and returns only once it's on the storage chip. A picture the file
     * doesn't hold yet goes in with it, just ahead: one write and one sync for both, so there
     * is never a moment when later lines could count on a picture that isn't safely stored.
     */
    fun append(change: Change) {
        val names = written ?: picturesInFile()
        written = null // stays unknown if anything below fails
        val text = StringBuilder()
        val line = line(change, names, text)
        text.append(line).append('\n')
        write(text.toString())
        written = names
    }

    /**
     * [change] as its journal line, with each picture as its name. A picture not yet in
     * [names] is added to it, and its `img` line is added to [pictures].
     */
    private fun line(change: Change, names: MutableSet<String>, pictures: StringBuilder): String =
        ChangeCodec.encode(change) { png ->
            nameOf(png).also { name ->
                if (names.add(name)) {
                    pictures.append("$PICTURE $name ").append(Base64.getEncoder().encodeToString(png)).append('\n')
                }
            }
        }

    /** A picture's name: the sha256 of its bytes, so the same picture always has the same name. */
    private fun nameOf(png: ByteArray): String {
        if (png === lastPicture) return lastName
        val name = sha256(png)
        lastPicture = png
        lastName = name
        return name
    }

    /**
     * Which pictures the file holds, read from the file. Used when this object can't be sure
     * (see [written]).
     *
     * A cut-short last line is cut off first. It doesn't count anyway (see the class comment),
     * and only a write that failed partway, with the app still running, leaves one that gets
     * here. What's written next would be joined onto it, and if that were a picture's line,
     * the picture would be spoiled while every later mention counted on it.
     */
    private fun picturesInFile(): MutableSet<String> {
        val names = HashSet<String>()
        if (!file.exists()) return names
        val bytes = file.readBytes()
        val whole = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (whole < bytes.size) {
            RandomAccessFile(file, "rw").use {
                it.setLength(whole.toLong())
                it.fd.sync()
            }
        }
        String(bytes, 0, whole, Charsets.UTF_8).lineSequence().forEach { line -> pictureOn(line)?.let { names += it.first } }
        return names
    }

    /**
     * The name and picture on an `img` line, or null if it isn't a sound one. The name must be
     * the sha256 of the bytes: a damaged picture is never handed out as a snip.
     */
    private fun pictureOn(line: String): Pair<String, ByteArray>? {
        val parts = line.split(' ')
        if (parts.size != 3 || parts[0] != PICTURE) return null
        val png = try {
            Base64.getDecoder().decode(parts[2])
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (sha256(png) == parts[1]) parts[1] to png else null
    }

    /**
     * Notes that a save is about to swap in a PDF that looks like [result] and holds the
     * first [count] changes of this journal. Returns only once it's on the storage chip.
     */
    fun markSaving(result: FileIdentity, count: Int) {
        write("$SAVING ${result.encode()} $count\n")
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
        val pictures = HashMap<String, ByteArray>()
        var pendingSave: PendingSave? = null
        for (line in complete.drop(1)) {
            if (!plan2 && line.startsWith("$PICTURE ")) {
                // Not a change: kept for the change lines after it that name it.
                pictureOn(line)?.let { (name, png) -> pictures[name] = png }
                continue
            }
            if (line.startsWith("$SAVING ")) {
                // Only the last one counts: an earlier one is a save that failed and was tried again.
                parseSaving(line)?.let { pendingSave = it }
                continue
            }
            (if (plan2) parseOldAdd(line) else ChangeCodec.decode(line) { pictures[it] })?.let { changes += it }
        }
        return JournalContents(base, changes, pendingSave)
    }

    fun clear() {
        file.delete()
        written = null
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

    /** Adds [text] to the end of the file: one write, one sync. */
    private fun write(text: String) {
        try {
            FileOutputStream(file, true).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                // The whole point of the journal: don't return until the bytes are really stored.
                out.fd.sync()
            }
        } catch (e: IOException) {
            // Part of it may be in the file, or none: look before the next append.
            written = null
            throw e
        }
    }

    companion object {
        private const val HEADER = "pnj2"
        private const val OLD_HEADER = "pnj1"
        private const val SAVING = "saving"
        private const val PICTURE = "img"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** One journal per PDF, named after a fingerprint of its full path. */
        fun forDocument(journalDir: File, pdf: File): ChangeJournal = ChangeJournal(journalFileFor(journalDir, pdf))

        /**
         * The journal file for [pdf]. Every other file of the PDF's in [journalDir] starts with
         * this name too: the temporary `.new` of a rewrite, and journals set aside as `.kept-<time>`.
         */
        fun journalFileFor(journalDir: File, pdf: File): File {
            return File(journalDir, sha256(pdf.absolutePath.toByteArray(Charsets.UTF_8)) + ".journal")
        }
    }
}
