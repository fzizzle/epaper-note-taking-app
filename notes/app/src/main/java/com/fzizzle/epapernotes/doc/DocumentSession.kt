package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.storage.CopyNames
import com.fzizzle.epapernotes.storage.FileIdentity
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * - Two sessions on the same file take turns: a new one waits until the previous one has
 *   finished saving and closed. Turning the tablet closes the writing screen and opens a new
 *   one on the same file, and without this the two would trip over each other's saves.
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
    private var journal = ChangeJournal.forDocument(journalDir, pdf)
    private val unsaved = ArrayList<Change>()

    /** Opened once the journal is ready, so no stroke can be written before its header. */
    private val journalReady = CountDownLatch(1)

    /** Completed when this session has saved and closed, for the next session on the file. */
    private val closed = CompletableFuture<Unit>()
    private val gateKey = pdf.absolutePath

    // Worker thread only.
    private var pages: PdfPages? = null
    private var applied = 0 // how many of `unsaved` have been added to `pages`
    private var known: FileIdentity? = null // the file as it was when opened or last saved
    private var repairDecided = false
    private var waitingForRepairDecision = false
    private var stoppedBecauseChanged = false

    /** Opens the document, on the worker. Call once, before anything else. */
    fun start() {
        val previous = openSessions.put(gateKey, closed)
        scheduler.post {
            try {
                // Wait for an earlier session on this file to finish its last save.
                previous?.let { runCatching { it.get(PREVIOUS_SESSION_WAIT_S, TimeUnit.SECONDS) } }
                openOnWorker()
            } finally {
                journalReady.countDown()
            }
        }
    }

    private fun openOnWorker() {
        // A save that crashed can leave this file's temporary copy behind (spec §7.3). Only
        // this file's: another file's may be in the middle of being saved.
        SafeSave.tempFileFor(file).delete()

        val identity = try {
            FileIdentity.of(file)
        } catch (e: Exception) {
            events(SessionEvent.OpenFailed("${file.name} couldn't be read."))
            return
        }

        val replay: List<Change>
        synchronized(lock) {
            val old = journal.read()
            replay = when {
                // The journal already holds exactly this: keep it as it is.
                old != null && old.base == identity -> old.changes
                else -> {
                    if (old != null && old.changes.isNotEmpty()) {
                        // Writing from an earlier session, drawn on a version of the file that
                        // has changed since. Never replay it onto the new version, and never
                        // throw it away (spec §7.4): set it aside for plan 5 to make a copy.
                        setAside()
                    }
                    journal.start(identity)
                    emptyList()
                }
            }
            unsaved.addAll(replay)
        }
        journalReady.countDown()

        val opened = try {
            open(file)
        } catch (e: Exception) {
            events(SessionEvent.OpenFailed(e.message ?: "The file couldn't be opened."))
            return
        }
        pages = opened
        known = identity
        replay.forEach { applyTo(opened, it) }
        applied = replay.size
        checkRepair(opened)
        if (replay.isNotEmpty()) {
            events(SessionEvent.Replayed(replay.size))
            scheduleAutosave()
        }
    }

    /** A finished stroke. Safe in the journal when this returns. */
    fun addStroke(page: Int, stroke: Stroke) {
        journalReady.await()
        synchronized(lock) {
            val change = AddItems(page, listOf(InkItem(ItemIds.next(), stroke)))
            journal.append(change)
            unsaved.add(change)
        }
        scheduler.post {
            val p = pages ?: return@post
            p.addStroke(page, stroke)
            applied++
        }
        scheduleAutosave()
    }

    /** Until PdfPages takes changes (a later task), only added ink reaches the PDF. */
    private fun applyTo(p: PdfPages, change: Change) {
        if (change is AddItems) change.items.filterIsInstance<InkItem>().forEach { p.addStroke(change.page, it.stroke) }
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
            repairDecided = true
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
                switchTo(copy)
            } catch (e: Exception) {
                events(SessionEvent.SaveFailed(e.message ?: "The copy couldn't be saved."))
                return@post
            }
            repairDecided = true
            waitingForRepairDecision = false
            events(SessionEvent.SavedToCopy(copy))
        }
    }

    /** Saves anything unsaved, then closes the PDF and stops the worker. */
    fun close() {
        scheduler.cancel(AUTOSAVE)
        scheduler.post {
            try {
                save()
                pages?.close()
                pages = null
            } finally {
                closed.complete(Unit)
                openSessions.remove(gateKey, closed)
            }
        }
        scheduler.shutdown()
    }

    private fun scheduleAutosave() {
        scheduler.postDelayed(AUTOSAVE, AUTOSAVE_DELAY_MS) { save() }
    }

    /** MuPDF can repair a damaged file while opening it, or later while reading it. Ask first, either way. */
    private fun checkRepair(p: PdfPages): Boolean {
        if (repairDecided || !p.wasRepaired) return false
        if (!waitingForRepairDecision) {
            waitingForRepairDecision = true
            events(SessionEvent.NeedsRepairDecision)
        }
        return true
    }

    /** Worker thread only. Any failure is reported: a failed save must never be silent (spec §7.3). */
    private fun save() {
        val p = pages ?: return
        val inThisSave = applied
        if (inThisSave == 0) return
        if (stoppedBecauseChanged || checkRepair(p)) return

        try {
            val now = try { FileIdentity.of(file) } catch (e: Exception) { null }
            if (now != known) {
                // A laptop or sync app changed, moved or deleted the file. Writing over it
                // would lose their change, so stop saving into it. The strokes stay in the
                // journal (plan 5 adds the copy).
                stoppedBecauseChanged = true
                events(SessionEvent.ChangedOnDisk)
                return
            }

            SafeSave.replace(file) { p.saveTo(it) }
            known = FileIdentity.of(file)
            restartJournal(known!!, inThisSave)
            events(SessionEvent.Saved)
        } catch (e: Exception) {
            // Nothing lost: the journal still has everything. Try again later.
            events(SessionEvent.SaveFailed(e.message ?: "The file couldn't be saved."))
            scheduler.postDelayed(AUTOSAVE, RETRY_DELAY_MS) { save() }
        }
    }

    /** After a successful save: a fresh journal holding only the strokes the save didn't include. */
    private fun restartJournal(base: FileIdentity, saved: Int) {
        synchronized(lock) {
            val remaining = unsaved.drop(saved)
            journal.rewrite(base, remaining)
            unsaved.clear()
            unsaved.addAll(remaining)
            applied -= saved
        }
    }

    /** Worker thread only. Continue in [copy], which has just been saved with everything applied. */
    private fun switchTo(copy: File) {
        val base = FileIdentity.of(copy)
        synchronized(lock) {
            val remaining = unsaved.drop(applied)
            val oldJournal = journal
            journal = ChangeJournal.forDocument(journalDir, copy)
            journal.rewrite(base, remaining)
            file = copy
            known = base
            unsaved.clear()
            unsaved.addAll(remaining)
            applied = 0
            oldJournal.clear()
        }
    }

    /**
     * Called with [lock] held. Keeps the current journal file aside, untouched. If moving it
     * fails, it's copied instead: the fresh journal that follows must never be what's left.
     */
    private fun setAside() {
        val kept = File(journalDir, journal.file.name + ".kept-" + System.currentTimeMillis())
        if (!journal.file.renameTo(kept)) journal.file.copyTo(kept, overwrite = false)
        events(SessionEvent.EarlierWritingKept(kept))
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 3000L
        const val RETRY_DELAY_MS = 30_000L
        private const val AUTOSAVE = "autosave"

        /** How long a new session waits for the previous one on the same file to close. */
        private const val PREVIOUS_SESSION_WAIT_S = 15L

        /** Open sessions by file, so a new one can wait for the previous one to close. */
        private val openSessions = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    }
}
