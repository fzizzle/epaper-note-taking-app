package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.storage.CopyNames
import com.fzizzle.epapernotes.storage.FileIdentity
import com.fzizzle.epapernotes.storage.SafeSave
import com.fzizzle.epapernotes.tools.Eraser
import com.fzizzle.epapernotes.tools.Lasso
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One open document, and the rules that keep its writing safe (spec §7.2, §7.3):
 *
 * - Every change (a stroke, an erase, a move, an undo...) goes into the journal before
 *   [record] returns, so it survives a crash from the moment the pen lifts.
 * - The PDF is saved in the background a few seconds after the pen stops, and at once when
 *   the owner leaves the document or the app goes to the background.
 * - A save never writes over a file that something else has changed, or over a damaged file
 *   the owner hasn't decided about. The writing stays in the journal instead.
 * - The journal is only emptied after a save has succeeded, and changes that arrived during
 *   that save are written back into it.
 * - Undo and redo cover this session's changes. The history is in memory only; an undo is
 *   just one more change, journaled and saved like any other.
 * - Two sessions on the same file take turns: a new one waits until the previous one has
 *   finished saving and closed. Turning the tablet closes the writing screen and opens a new
 *   one on the same file, and without this the two would trip over each other's saves.
 *
 * Threads: [addStroke] runs on the pen's thread, [undo] and [redo] on the screen's, and
 * [record] on any. Everything that touches the PDF runs on the [scheduler]'s single worker
 * thread. The journal, the unsaved changes and the history are shared, so they're guarded by
 * [lock]. Events arrive on the worker thread.
 */
class DocumentSession(
    pdf: File,
    private val journalDir: File,
    private val open: (File) -> PdfPages,
    private val scheduler: Scheduler,
    private val events: (SessionEvent) -> Unit,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    /** How long to wait for an earlier session on this file to close. Tests shorten it. */
    private val previousSessionWaitMs: Long = PREVIOUS_SESSION_WAIT_MS,
) {
    /** The file being written into. It becomes the copy after a "save to a copy". */
    @Volatile var file: File = pdf
        private set

    private val lock = Any()

    // Guarded by lock: the journal, the changes it holds that aren't in a saved PDF yet, and
    // this session's history: changes to undo (newest last) and undone ones to redo.
    private var journal = ChangeJournal.forDocument(journalDir, pdf)
    private val unsaved = ArrayList<Change>()
    private val undoable = ArrayDeque<Change>()
    private val redoable = ArrayDeque<Change>()

    /** Opened once the journal is ready, so no stroke can be written before its header. */
    private val journalReady = CountDownLatch(1)

    /** Completed when this session has saved and closed, for the next session on the file. */
    private val closed = CompletableFuture<Unit>()
    private val gateKey = pdf.absolutePath

    // Worker thread only.
    private var pages: PdfPages? = null
    private var applied = 0 // how many of `unsaved` are in `pages`: always the first ones, in order
    private var applyFailed = false // the PDF no longer matches the journal, so it's never saved
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
                previous?.let { runCatching { it.get(previousSessionWaitMs, TimeUnit.MILLISECONDS) } }
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
                // The journal already holds exactly this. Rewrite it anyway: a plan-2 (pnj1)
                // journal becomes pnj2 with fixed item ids before anything new is appended,
                // or the new lines would be read back as plan-2 lines and dropped.
                old != null && old.base == identity -> {
                    journal.rewrite(identity, old.changes)
                    old.changes
                }
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
        // The replayed changes, and any the pen has added since the journal opened.
        applyPending()
        checkRepair(opened)
        if (replay.isNotEmpty()) {
            events(SessionEvent.Replayed(replay.size))
            scheduleAutosave()
        }
    }

    /** A finished stroke, from the pen's thread. Safe in the journal when this returns. */
    fun addStroke(page: Int, stroke: Stroke) {
        record(AddItems(page, listOf(InkItem(ItemIds.next(), stroke))))
    }

    /**
     * A new change, from any thread. Safe in the journal when this returns; it reaches the PDF
     * when the worker gets to it. It can be undone, and it clears what could be redone.
     *
     * The ordering rule: a change is journaled, appended to `unsaved`, and posted to the worker
     * inside one `synchronized(lock)` block (see [commit]). So the order in the journal, in
     * `unsaved` and on the worker is always the same, even with the pen, the screen and the
     * worker recording at once, and `applied` says exactly which changes a save contains.
     * [erase] and [rotate] work out their change on the worker and then come through here like
     * anyone else: their change is applied after everything recorded before it.
     */
    fun record(change: Change) {
        journalReady.await()
        val fresh = withFreshIds(change)
        if (isEmpty(fresh)) return
        synchronized(lock) {
            commit(fresh) {
                undoable.addLast(fresh)
                if (undoable.size > HISTORY_LIMIT) undoable.removeFirst()
                redoable.clear()
            }
        }
        scheduleAutosave()
    }

    /** Takes back this session's last change, by recording its inverse. */
    fun undo() {
        // No wait for the journal: a change to undo means it was ready long ago.
        synchronized(lock) {
            val last = undoable.lastOrNull() ?: return
            commit(last.inverse()) {
                undoable.removeLast()
                redoable.addLast(last)
            }
        }
        scheduleAutosave()
    }

    /** Puts back the last change [undo] took back. */
    fun redo() {
        synchronized(lock) {
            val next = redoable.lastOrNull() ?: return
            commit(next) {
                redoable.removeLast()
                undoable.addLast(next)
            }
        }
        scheduleAutosave()
    }

    /**
     * Called with [lock] held: the ordering rule. The journal comes first, so if writing it
     * fails nothing else changes; [updateHistory] runs only once the change is safe.
     */
    private fun commit(change: Change, updateHistory: () -> Unit) {
        journal.append(change)
        unsaved.add(change)
        updateHistory()
        val history = SessionEvent.HistoryChanged(canUndo = undoable.isNotEmpty(), canRedo = redoable.isNotEmpty())
        scheduler.post {
            applyPending()
            events(history)
        }
    }

    /** Removes the items an eraser [trail] (PDF space) touches on [page]. */
    fun erase(page: Int, trail: List<Pt>) {
        scheduler.post {
            val p = pages ?: return@post
            applyPending()
            val hits = Eraser.hits(p.items(page), trail) // items() once: it's costly for snips
            if (hits.isNotEmpty()) record(RemoveItems(page, hits))
        }
    }

    /** The items a lasso [loop] (PDF space) picks up on [page], given to [answer] on the worker. */
    fun select(page: Int, loop: List<Pt>, answer: (List<Item>) -> Unit) {
        scheduler.post {
            val p = pages ?: return@post answer(emptyList())
            applyPending()
            answer(Lasso.selects(p.items(page), loop))
        }
    }

    /** All of the app's items on [page], given to [answer] on the worker. */
    fun items(page: Int, answer: (List<Item>) -> Unit) {
        scheduler.post {
            val p = pages ?: return@post answer(emptyList())
            applyPending()
            answer(p.items(page))
        }
    }

    /** Turns each of [pages] by 90° clockwise, as one undo step. */
    fun rotate(pages: List<Int>) {
        scheduler.post {
            val p = this.pages ?: return@post
            applyPending()
            val before = pages.associateWith { p.rotation(it) }
            record(RotatePages(before, before.mapValues { (_, degrees) -> (degrees + 90) % 360 }))
        }
    }

    /**
     * Worker thread only. Brings the PDF up to date with everything recorded so far, in order.
     * Every change posts this, so usually it applies just that one. A task that works out a
     * change from the PDF ([erase], [rotate]) calls it first, so it sees every change recorded
     * before it, even one whose own task is still queued behind it: two quick erases can't
     * both remove the same stroke.
     */
    private fun applyPending() {
        val p = pages ?: return
        if (applyFailed) return
        val touched = HashSet<Int>()
        var rotated = false
        while (true) {
            val next = synchronized(lock) { unsaved.getOrNull(applied) } ?: break
            try {
                p.apply(next)
            } catch (e: Exception) {
                // The PDF may now hold part of this change, so saving it could lose or double
                // something. Stop saving; the journal keeps everything for the next open.
                applyFailed = true
                events(SessionEvent.SaveFailed(e.message ?: "A change couldn't be written into the file."))
                break
            }
            applied++
            touched += next.pages
            rotated = rotated || next is RotatePages
        }
        if (touched.isNotEmpty()) events(SessionEvent.PageChanged(touched, rotated))
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
        if (applyFailed || stoppedBecauseChanged || checkRepair(p)) return

        try {
            val now = try { FileIdentity.of(file) } catch (e: Exception) { null }
            if (now != known) {
                // A laptop or sync app changed, moved or deleted the file. Writing over it
                // would lose their change, so stop saving into it. The changes stay in the
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

    /** After a successful save: a fresh journal holding only the changes the save didn't include. */
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

    /** A change with nothing in it isn't worth a journal line or an undo step. */
    private fun isEmpty(change: Change) = when (change) {
        is AddItems -> change.items.isEmpty()
        is RemoveItems -> change.items.isEmpty()
        is ReplaceItems -> change.before.isEmpty() && change.after.isEmpty()
        is RotatePages -> change.before == change.after
    }

    /**
     * A plan-2 stroke's id (`o<object number>`) only names the annotation it was read from. A
     * moved one is a new annotation, so it gets an id of its own before it's journaled; the
     * undo history then holds the id that is really in the file.
     */
    private fun withFreshIds(change: Change): Change {
        if (change !is ReplaceItems || change.after.none { it.id.startsWith("o") }) return change
        return change.copy(after = change.after.map { if (it.id.startsWith("o")) it.withId(ItemIds.next()) else it })
    }

    private fun Item.withId(id: String): Item = when (this) {
        is InkItem -> copy(id = id)
        is ImageItem -> copy(id = id)
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 3000L
        const val HISTORY_LIMIT = 200
        const val RETRY_DELAY_MS = 30_000L
        private const val AUTOSAVE = "autosave"

        /** How long a new session waits for the previous one on the same file to close. */
        private const val PREVIOUS_SESSION_WAIT_MS = 15_000L

        /** Open sessions by file, so a new one can wait for the previous one to close. */
        private val openSessions = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    }
}
