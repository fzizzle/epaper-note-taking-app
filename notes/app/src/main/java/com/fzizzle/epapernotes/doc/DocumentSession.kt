package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.storage.CopyNames
import com.fzizzle.epapernotes.storage.FileIdentity
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.time.LocalDateTime

/**
 * One open document, and the rules that keep its writing safe (spec §7.2, §7.3):
 *
 * - Every stroke goes into the journal before [addStroke] returns, so it survives a crash
 *   from the moment the pen lifts.
 * - The PDF is saved in the background a few seconds after the pen stops, and at once when
 *   the owner leaves the document or the app goes to the background.
 * - A save never writes over a file that something else has changed, or over a damaged file
 *   the owner hasn't decided about. The writing stays in the journal instead.
 * - The journal is only emptied after a save has succeeded, and strokes that arrived during
 *   that save are written back into it.
 *
 * Threads: [addStroke] runs on the pen's thread. Everything that touches the PDF runs on the
 * [scheduler]'s single worker thread. The journal is shared, so it's guarded by [lock].
 */
class DocumentSession(
    pdf: File,
    private val journalDir: File,
    private val open: (File) -> PdfPages,
    private val scheduler: Scheduler,
    private val events: (SessionEvent) -> Unit,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    /** The file being written into. It becomes the copy after a "save to a copy". */
    @Volatile var file: File = pdf
        private set

    private val lock = Any()

    // Guarded by lock: the journal, and the strokes it holds that aren't in a saved PDF yet.
    private var journal = StrokeJournal.forDocument(journalDir, pdf)
    private val unsaved = ArrayList<PendingStroke>()

    // Worker thread only.
    private var pages: PdfPages? = null
    private var applied = 0 // how many of `unsaved` have been added to `pages`
    private var known: FileIdentity? = null // the file as it was when opened or last saved
    private var waitingForRepairDecision = false
    private var stoppedBecauseChanged = false

    /** Opens the document. Call once, before anything else. */
    fun start() {
        // A save that crashed can leave a temporary file behind (spec §7.3).
        SafeSave.cleanStale(file.absoluteFile.parentFile)

        val identity = FileIdentity.of(file)
        val replay: List<PendingStroke>
        synchronized(lock) {
            val old = journal.read()
            replay = when {
                old == null -> emptyList()
                old.base == identity -> old.strokes
                else -> {
                    // Writing from an earlier session, drawn on a version of the file that has
                    // changed since. Never replay it onto the new version, and never throw it
                    // away (spec §7.4): set it aside for plan 5 to turn into a copy.
                    if (old.strokes.isNotEmpty()) setAside()
                    emptyList()
                }
            }
            journal.start(identity)
            replay.forEach { journal.append(it.page, it.stroke) }
            unsaved.addAll(replay)
        }

        scheduler.post {
            val opened = try {
                open(file)
            } catch (e: Exception) {
                events(SessionEvent.OpenFailed(e.message ?: "The file couldn't be opened."))
                return@post
            }
            pages = opened
            known = identity
            replay.forEach { opened.addStroke(it.page, it.stroke) }
            applied = replay.size
            if (opened.wasRepaired) {
                waitingForRepairDecision = true
                events(SessionEvent.NeedsRepairDecision)
            }
            if (replay.isNotEmpty()) {
                events(SessionEvent.Replayed(replay.size))
                scheduleAutosave()
            }
        }
    }

    /** A finished stroke. Safe in the journal when this returns. */
    fun addStroke(page: Int, stroke: Stroke) {
        synchronized(lock) {
            journal.append(page, stroke)
            unsaved.add(PendingStroke(page, stroke))
        }
        scheduler.post {
            val p = pages ?: return@post
            p.addStroke(page, stroke)
            applied++
        }
        scheduleAutosave()
    }

    /**
     * Runs [task] with the open PDF, on the worker thread, for drawing pages or reading their
     * sizes. Nothing happens if the PDF failed to open.
     */
    fun runOnDocument(task: (PdfPages) -> Unit) {
        scheduler.post { pages?.let(task) }
    }

    /** Leaving the document or going to the background: save now rather than in a few seconds. */
    fun saveNow() {
        scheduler.cancel(AUTOSAVE)
        scheduler.post { save() }
    }

    /** The owner's answer to [SessionEvent.NeedsRepairDecision]: write into the repaired file. */
    fun saveIntoRepairedFile() {
        scheduler.post {
            waitingForRepairDecision = false
            save()
        }
    }

    /** The owner's other answer: leave the damaged original alone, and keep writing in a copy. */
    fun saveRepairedToCopy() {
        scheduler.post {
            val p = pages ?: return@post
            val copy = CopyNames.myEdits(file, clock())
            try {
                SafeSave.replace(copy) { p.saveTo(it) }
            } catch (e: Exception) {
                events(SessionEvent.SaveFailed(e.message ?: "The copy couldn't be saved."))
                return@post
            }
            switchTo(copy)
            waitingForRepairDecision = false
            events(SessionEvent.SavedToCopy(copy))
        }
    }

    /** Saves anything unsaved, then closes the PDF and stops the worker. */
    fun close() {
        scheduler.cancel(AUTOSAVE)
        scheduler.post {
            save()
            pages?.close()
            pages = null
        }
        scheduler.shutdown()
    }

    private fun scheduleAutosave() {
        scheduler.postDelayed(AUTOSAVE, AUTOSAVE_DELAY_MS) { save() }
    }

    /** Worker thread only. */
    private fun save() {
        val p = pages ?: return
        val inThisSave = applied
        if (inThisSave == 0) return
        if (waitingForRepairDecision || stoppedBecauseChanged) return

        if (FileIdentity.of(file) != known) {
            // A laptop or sync app changed the file. Writing over it would lose their change,
            // so stop saving into it. The strokes stay in the journal (plan 5 adds the copy).
            stoppedBecauseChanged = true
            events(SessionEvent.ChangedOnDisk)
            return
        }

        try {
            SafeSave.replace(file) { p.saveTo(it) }
        } catch (e: Exception) {
            // Nothing lost: the journal still has everything. Try again later.
            events(SessionEvent.SaveFailed(e.message ?: "The file couldn't be saved."))
            scheduler.postDelayed(AUTOSAVE, RETRY_DELAY_MS) { save() }
            return
        }
        known = FileIdentity.of(file)
        restartJournal(known!!, inThisSave)
        events(SessionEvent.Saved)
    }

    /** After a successful save: a fresh journal holding only the strokes the save didn't include. */
    private fun restartJournal(base: FileIdentity, saved: Int) {
        synchronized(lock) {
            repeat(saved) { unsaved.removeAt(0) }
            applied -= saved
            journal.start(base)
            unsaved.forEach { journal.append(it.page, it.stroke) }
        }
    }

    /** Worker thread only. Continue in [copy], which has just been saved with everything applied. */
    private fun switchTo(copy: File) {
        val base = FileIdentity.of(copy)
        synchronized(lock) {
            val oldJournal = journal
            journal = StrokeJournal.forDocument(journalDir, copy)
            file = copy
            known = base
            repeat(applied) { unsaved.removeAt(0) }
            applied = 0
            journal.start(base)
            unsaved.forEach { journal.append(it.page, it.stroke) }
            oldJournal.clear()
        }
    }

    /** Called with [lock] held. Moves the current journal file aside, untouched. */
    private fun setAside() {
        val kept = File(journalDir, journal.file.name + ".kept-" + System.currentTimeMillis())
        journal.file.renameTo(kept)
        events(SessionEvent.EarlierWritingKept(kept))
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 3000L
        const val RETRY_DELAY_MS = 30_000L
        private const val AUTOSAVE = "autosave"
    }
}
