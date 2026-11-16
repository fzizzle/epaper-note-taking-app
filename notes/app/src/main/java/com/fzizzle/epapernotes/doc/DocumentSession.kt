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
import com.fzizzle.epapernotes.tools.TapSelect
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One open document, and the rules that keep its writing safe (spec §7.2, §7.3):
 *
 * - Every change (a stroke, an erase, a move, an undo...) goes into the journal before
 *   [record] returns, so it survives a crash from the moment the pen lifts.
 * - If the journal can't be written (a full tablet), the change is still kept: in memory, and
 *   saved to the PDF at once. The owner is told that writing isn't safe from a crash for now.
 * - The PDF is saved in the background a few seconds after the pen stops, and at once when
 *   the owner leaves the document or the app goes to the background.
 * - A save never writes over a file that something else has changed, or over a damaged file
 *   the owner hasn't decided about. A changed file is left as it is, and the writing goes
 *   into a "(my edits …)" copy beside it, which the session then continues in (spec §7.4); a
 *   damaged one waits in the journal for the owner's answer. "Changed" means its contents: a
 *   file whose date alone is different is the same file.
 * - The journal is only emptied after a save has succeeded, and changes that arrived during
 *   that save are written back into it. Just before the new PDF is swapped in, the journal
 *   notes what it looks like, so a kill between the two is understood on the next open.
 * - Undo and redo cover this session's changes. The history is in memory only; an undo is
 *   just one more change, journaled and saved like any other.
 * - Two sessions on the same file take turns: a new one waits until the previous one has
 *   finished saving and closed. Leaving a document and opening it again at once starts a new
 *   session while the old one may still be saving its last changes, and without this the two
 *   would trip over each other's saves. (Turning the tablet doesn't: the writing screen
 *   handles the turn itself, see configChanges in the manifest, so its session carries on.)
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
    /** How a file's identity is read: the whole file, every time. Tests count the reads. */
    private val identityOf: (File) -> FileIdentity = FileIdentity::of,
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
    // Guarded by lock too. A journal write failed, and the owner has been told. From then on
    // the journal file may not hold the same changes as `unsaved`. Cleared when the journal is
    // next rewritten whole, so the message isn't shown at every stroke.
    private var journalBroken = false

    /**
     * How many times this session has added or removed a page, so a request can say which page
     * numbers it was made with: the screen latches it when the pen lands. It goes up, and the
     * change's mapping is kept in [pageChanges], in the same locked step that moves `unsaved`
     * to the new numbers ([restartJournal]). Written under lock, on the worker; read anywhere.
     */
    @Volatile var pageNumbering = 0
        private set

    // Guarded by lock. Each page change of this session, as old page number to new (null: the
    // page is gone). Entry k takes a page number from numbering k to numbering k + 1.
    private val pageChanges = ArrayList<(Int) -> Int?>()

    /** Opened once the journal is ready, so no stroke can be written before its header. */
    private val journalReady = CountDownLatch(1)

    /** Completed when this session has saved and closed, for the next session on the file. */
    private val closed = CompletableFuture<Unit>()
    private val gateKey = pdf.absolutePath
    /**
     * Worker thread only. Each copy's path after a "save to a copy": the session gates those
     * files too. Every one, not just the last: a session can move into a copy, then that copy
     * can be changed elsewhere and it moves into a second.
     */
    private val copyGateKeys = mutableSetOf<String>()

    // Worker thread only.
    private var pages: PdfPages? = null
    /**
     * How many pages the document on screen has, as the session knows it: taken when a PDF is
     * opened, and moved by the session's own page adds and removes. Never read from the open
     * PDF later, because a file overwritten in place under a PDF opened by path changes what
     * that PDF counts (findings log, 2026-11-16), and a copy of it would then match.
     */
    private var knownPageCount = 0
    private var applied = 0 // how many of `unsaved` are in `pages`: always the first ones, in order
    private var applyFailed = false // the PDF no longer matches the journal, so it's never saved
    private var inkCheckNs = 0L // time spent looking for (and redrawing) old faulty ink since opening, for the log
    private var inkCheckRedrawn = 0
    private var inkRedrawn = false // strokes were drawn again (redrawFaultyInk) since the last save: save even with nothing journaled
    private var inkPass = 0 // which pass of the ink redraw is the current one: a reload starts a new pass, and the old one stops
    private var known: FileIdentity? = null // the file as it was when opened or last saved
    private var repairDecided = false
    private var waitingForRepairDecision = false

    /** Opens the document, on the worker. Call once, before anything else. */
    fun start() {
        val previous = openSessions.put(gateKey, closed)
        scheduler.post {
            try {
                // Wait for an earlier session on this file to finish its last save.
                previous?.let { runCatching { it.get(previousSessionWaitMs, TimeUnit.MILLISECONDS) } }
                openOnWorker()
            } catch (t: Throwable) {
                // Whatever went wrong (the journal couldn't be read or rewritten, memory ran
                // out), say so: otherwise the screen waits for a document that never comes.
                System.err.println("DocumentSession: opening failed: $t")
                events(SessionEvent.OpenFailed("${file.name} couldn't be opened."))
            } finally {
                journalReady.countDown()
            }
        }
    }

    private fun openOnWorker() {
        // A save that crashed can leave this file's temporary copy behind (spec §7.3). Only
        // this file's: another file's may be in the middle of being saved.
        SafeSave.tempFileFor(file).delete()

        // Timed in three parts, like a save: reading the whole file for its identity, the
        // journal, and MuPDF opening the PDF.
        val started = System.nanoTime()
        val identity = try {
            identityOf(file)
        } catch (e: Exception) {
            events(SessionEvent.OpenFailed("${file.name} couldn't be read."))
            return
        }

        val identityNs = System.nanoTime() - started
        val replay: List<Change>
        // Writing from an earlier session, drawn on a version of the file that has changed
        // since. It goes into a copy once the PDF is open ([rescueIntoCopy]).
        var earlier: List<Change>? = null
        synchronized(lock) {
            val old = journal.read()
            val pending = old?.pendingSave
            replay = when {
                // The journal was started on this very file (its date may differ: only the
                // contents count). Rewrite it anyway: a plan-2 (pnj1) journal becomes pnj2
                // with fixed item ids before anything new is appended, or the new lines would
                // be read back as plan-2 lines and dropped.
                old != null && old.base.sameContentAs(identity) -> {
                    journal.rewrite(identity, old.changes)
                    old.changes
                }
                // The app was killed in the middle of a save: the new PDF was swapped in, but
                // the journal never heard. The file is the one that save was writing, so it
                // already holds the first changes; only the ones after them are put back.
                old != null && pending != null && pending.result.sameContentAs(identity) -> {
                    val rest = old.changes.drop(pending.count)
                    journal.rewrite(identity, rest)
                    rest
                }
                else -> {
                    when {
                        old == null || old.changes.isEmpty() -> journal.start(identity)
                        // The app was killed while a page was added or removed: some of these
                        // changes may have page numbers the file doesn't go by. They're never
                        // put onto it or a copy of it, and never thrown away: set aside.
                        old.renumbering -> {
                            setAside()
                            journal.start(identity)
                        }
                        // Never put onto the new version, and never thrown away (spec §7.4).
                        // The journal stays as it is until the copy is safely saved.
                        else -> earlier = old.changes
                    }
                    emptyList()
                }
            }
            unsaved.addAll(replay)
        }
        val toRescue = earlier
        // With writing to rescue, the journal isn't ready for new strokes until that's done.
        if (toRescue == null) journalReady.countDown()

        val journalNs = System.nanoTime() - started - identityNs
        val opened = try {
            open(file)
        } catch (e: Exception) {
            journalReady.countDown()
            events(SessionEvent.OpenFailed(e.message ?: "The file couldn't be opened."))
            return
        }
        val totalNs = System.nanoTime() - started
        System.err.println(
            "DocumentSession: open ms=${totalNs / 1_000_000} identity=${identityNs / 1_000_000} journal=${journalNs / 1_000_000} " +
                "pdf=${(totalNs - identityNs - journalNs) / 1_000_000}",
        )
        pages = opened
        knownPageCount = opened.pageCount
        known = identity
        if (toRescue != null) {
            try {
                rescueIntoCopy(opened, toRescue)
            } finally {
                journalReady.countDown()
            }
        }
        // Null only if the rescue had to open the file again and couldn't; the owner was told.
        val p = pages ?: return
        // The replayed changes, and any the pen has added since the journal opened.
        applyPending()
        checkRepair(p)
        scheduler.postDelayed(REDRAW_INK, REDRAW_INK_DELAY_MS) { redrawFaultyInkFrom(0) }
        if (replay.isNotEmpty() && !applyFailed) {
            events(SessionEvent.Replayed(replay.size))
            scheduleAutosave()
        }
    }

    /**
     * Worker thread only, while opening. The journal holds [earlier] writing that never reached
     * the PDF, and the file has changed since (spec §7.4). The version it was written on is
     * gone, so it's put onto the file as it is now, in memory only, and saved beside it as a
     * "(my edits …)" copy (owner's decision, 2026-11-13). The file itself is never written
     * into. The session then continues in the copy.
     *
     * A change on a page the file no longer has can't be placed: the journal is kept aside
     * for it, and the owner is told how many. If anything fails, the journal is set aside
     * whole and the file is opened plain, as before this existed.
     */
    private fun rescueIntoCopy(opened: PdfPages, earlier: List<Change>) {
        val placeable = earlier.filter { change -> change.pages.all { it in 0 until opened.pageCount } }
        val notPlaced = earlier.size - placeable.size
        // Nothing to put in a copy: no copy is made, and the open file is left as it is.
        if (placeable.isEmpty()) {
            synchronized(lock) {
                setAside()
                journal.start(known!!)
            }
            return
        }
        val inTheFile = placeable.all { it is AddItems } && run {
            val held = mutableMapOf<Int, Set<String>>()
            placeable.all { change ->
                change as AddItems
                val ids = held.getOrPut(change.page) { opened.items(change.page).map { it.id }.toSet() }
                change.items.all { it.id in ids }
            }
        }
        if (inTheFile) {
            synchronized(lock) {
                // Changes on pages the file lost can't be placed: they stay in a kept journal.
                if (notPlaced > 0) setAside()
                journal.start(known!!)
            }
            return
        }
        var keptAside = false
        try {
            placeable.forEach(opened::apply)
            // Kept aside before the copy is made: moving into the copy clears this journal.
            if (notPlaced > 0) synchronized(lock) { setAside(copy = true) }
            keptAside = notPlaced > 0
            continueInCopy(opened, CopyReason.RESCUED, notPlaced)
        } catch (e: Throwable) { // out of memory too
            System.err.println("DocumentSession: earlier writing couldn't be put into a copy: $e")
            synchronized(lock) {
                // Already kept aside whole, and the journal hasn't changed since: once is enough.
                if (!keptAside) setAside()
                journal.start(known!!)
            }
            // The open PDF may hold part of the writing: start again from the file.
            openAgainFromTheFile()
        }
    }

    /**
     * Strokes an earlier version saved with a faulty drawing (mirrored, or with holes at their
     * ends) are drawn again, and saved like an edit. It
     * starts a moment after opening, so the first page shows at once (owner's choice; about
     * 21 ms a stroke, 1.4 s for the test file), and goes at most [REDRAW_CHUNK] strokes per
     * task, each posting the next, so the screen's requests for pages, an erase, a lasso, an
     * autosave or [close] get in between (a full page could hold the worker for seconds). A
     * page on show is drawn again once all its strokes are fixed ([SessionEvent.InkRedrawn]).
     * A save in the middle keeps what's fixed so far; if the app stops before the last save,
     * the next opening simply finds the rest and does it. Worker thread only.
     *
     * Changes recorded meanwhile need no special care, whichever comes first on the worker. A
     * redraw changes only how a stroke is drawn (its /AP, /Rect and drawing version): never its id, its
     * recorded points, or its place in the page's list. Applying a change reads and writes
     * only those, so it does the same before or after a redraw: an erased or moved stroke
     * goes either way (redrawn first, it just cost a moment), and anything a change adds or
     * puts back is drawn the new way already. And a save in between holds whole strokes,
     * since each is redrawn within one task. So `applied`, and the ordering rule, are untouched.
     *
     * [redrawnHere] counts this page's strokes fixed by earlier chunks, for the one InkRedrawn.
     * [pass]: the [inkPass] this walk belongs to. A [reload] starts a new one on the new
     * version, and the old walk's next task then stops here, rather than going on alongside it.
     */
    private fun redrawFaultyInkFrom(page: Int, redrawnHere: Int = 0, pass: Int = inkPass) {
        if (pass != inkPass) return
        val p = pages ?: return // closed
        if (page >= p.pageCount) {
            System.err.println("DocumentSession: ink check ms=${inkCheckNs / 1_000_000} pages=${p.pageCount} redrawn=$inkCheckRedrawn")
            if (inkRedrawn) scheduleAutosave()
            return
        }
        var redrawn = redrawnHere
        var more = false
        val started = System.nanoTime()
        try {
            val chunk = p.redrawFaultyInk(page, REDRAW_CHUNK)
            inkCheckRedrawn += chunk.redrawn
            if (chunk.redrawn > 0) inkRedrawn = true
            redrawn += chunk.redrawn
            more = chunk.more
        } catch (e: Exception) {
            // Never a reason to stop: the strokes just stay as they were.
            System.err.println("DocumentSession: redrawing ink on page $page failed: $e")
        }
        inkCheckNs += System.nanoTime() - started
        if (more) return scheduler.post { redrawFaultyInkFrom(page, redrawn, pass) }
        if (redrawn > 0) events(SessionEvent.InkRedrawn(page))
        scheduler.post { redrawFaultyInkFrom(page + 1, pass = pass) }
    }

    /**
     * A finished stroke, from the pen's thread. Safe in the journal when this returns (see
     * [commit] for a full tablet). [numbering] is the [pageNumbering] the stroke's [page] was
     * read under, when the pen landed: a stroke handed over after a page change is moved to its
     * page's new number (see [record]). Returns the change as recorded, with that number, or
     * null if its page was removed.
     */
    fun addStroke(page: Int, stroke: Stroke, numbering: Int = pageNumbering): Change? =
        record(AddItems(page, listOf(InkItem(ItemIds.next(), stroke))), numbering)

    /**
     * A new change, from any thread. Safe in the journal when this returns; it reaches the PDF
     * when the worker gets to it. It can be undone, and it clears what could be redone. (The
     * one exception is a journal that can't be written: see [commit].)
     *
     * The ordering rule: a change is journaled, appended to `unsaved`, and posted to the worker
     * inside one `synchronized(lock)` block (see [commit]). So the order in the journal, in
     * `unsaved` and on the worker is always the same, even with the pen, the screen and the
     * worker recording at once, and `applied` says exactly which changes a save contains.
     * [erase] and [rotate] work out their change on the worker and then come through here like
     * anyone else: their change is applied after everything recorded before it.
     *
     * Returns the change as journaled, or null if it was empty and ignored. It can differ from
     * [change]: a plan-2 item gets a new id when moved (see [withFreshIds]), and a caller that
     * keeps hold of the moved items, like the selection, must use these.
     *
     * [numbering]: the [pageNumbering] the change's page numbers were read under. If pages have
     * been added or removed since, the change is moved to the new numbers here, under the lock,
     * so nothing can renumber in between; one on a page removed since has nowhere to go and
     * isn't recorded (null).
     */
    fun record(change: Change, numbering: Int = pageNumbering): Change? {
        journalReady.await()
        val fresh = withFreshIds(change)
        if (isEmpty(fresh)) return null
        val placed = synchronized(lock) {
            val placed = movedSince(fresh, numbering)
            if (placed == null) {
                System.err.println("DocumentSession: a change on page ${fresh.pages} came after its page was removed: not recorded")
                return null
            }
            commit(placed) {
                undoable.addLast(placed)
                if (undoable.size > HISTORY_LIMIT) undoable.removeFirst()
                redoable.clear()
            }
            placed
        }
        scheduleAutosave()
        return placed
    }

    /** Called with [lock] held. [change], made under [numbering], with the page numbers of now; null if one of its pages is gone. */
    private fun movedSince(change: Change, numbering: Int): Change? {
        var moved: Change? = change
        for (k in numbering.coerceIn(0, pageNumbering) until pageNumbering) moved = moved?.let { renumbered(it, pageChanges[k]) }
        if (moved !== change) System.err.println("DocumentSession: a change from before a page change moved from page ${change.pages} to ${moved?.pages}")
        return moved
    }

    /**
     * Takes back this session's last change, by recording its inverse. Returns that inverse,
     * or null if there was nothing to undo: the screen needs its pages, like [record]'s callers.
     */
    fun undo(): Change? {
        // No wait for the journal: a change to undo means it was ready long ago.
        val inverse = synchronized(lock) {
            val last = undoable.lastOrNull() ?: return null
            last.inverse().also {
                commit(it) {
                    undoable.removeLast()
                    redoable.addLast(last)
                }
            }
        }
        scheduleAutosave()
        return inverse
    }

    /** Puts back the last change [undo] took back, and returns it (null if there was none). */
    fun redo(): Change? {
        val next = synchronized(lock) {
            val next = redoable.lastOrNull() ?: return null
            next.also {
                commit(it) {
                    redoable.removeLast()
                    undoable.addLast(it)
                }
            }
        }
        scheduleAutosave()
        return next
    }

    /**
     * Called with [lock] held: the ordering rule. The journal comes first, then `unsaved` and
     * the history, then the worker.
     *
     * A journal that can't be written (the tablet's own storage is full) doesn't stop the
     * change. The rule used to be "journal first, or nothing", which lost the stroke the owner
     * had just written and crashed the app, at every stroke. Now the change is kept in memory
     * like any other and the PDF is saved at once, rather than in a few seconds: until that
     * save lands, memory is the only place the change is. The owner is told once.
     */
    private fun commit(change: Change, updateHistory: () -> Unit) {
        val journaled = try {
            journal.append(change)
            true
        } catch (e: IOException) {
            System.err.println("DocumentSession: the journal couldn't be written: $e")
            false
        }
        val tellOwner = !journaled && !journalBroken
        if (!journaled) journalBroken = true
        unsaved.add(change)
        updateHistory()
        val history = SessionEvent.HistoryChanged(canUndo = undoable.isNotEmpty(), canRedo = redoable.isNotEmpty())
        scheduler.post {
            if (tellOwner) events(SessionEvent.JournalFailed) // posted, so it arrives on the worker like every event
            applyPending()
            events(history)
        }
        if (!journaled) scheduler.post { save() }
    }

    /**
     * Removes the items an eraser [trail] (PDF space) touches on [page]. [answer] gets how many,
     * on the worker, before the [SessionEvent.PageChanged] the removal brings: the screen needs
     * to know whether to expect one.
     *
     * [numbering]: the [pageNumbering] [page] was read under. If a page was added or removed
     * since, nothing is erased (0 hits): a trail drawn on the old page isn't worth redoing on a
     * renumbered one. Checked on the worker, where page changes run, so it's exact.
     */
    fun erase(page: Int, trail: List<Pt>, numbering: Int = pageNumbering, answer: (hits: Int) -> Unit = {}) {
        var count = 0
        onWorker("erase on page $page", then = { answer(count) }) { p ->
            if (stale(numbering, "erase on page $page")) return@onWorker
            val hits = Eraser.hits(p.items(page), trail) // items() once: it's costly for snips
            if (hits.isNotEmpty()) record(RemoveItems(page, hits))
            count = hits.size
        }
    }

    /**
     * The items a lasso [loop] (PDF space) picks up on [page], given to [answer] on the worker.
     * Nothing, if a page was added or removed since [numbering], as for [erase].
     */
    fun select(page: Int, loop: List<Pt>, tapReachPt: Float? = null, numbering: Int = pageNumbering, answer: (List<Item>) -> Unit) {
        var found = emptyList<Item>()
        onWorker("select on page $page", then = { answer(found) }) { p ->
            if (stale(numbering, "select on page $page")) return@onWorker
            // A tap with the lasso (the screen says so by giving its reach) picks the one item
            // under it; a loop, everything inside.
            found = if (tapReachPt != null) TapSelect.pick(p.items(page), loop, tapReachPt) else Lasso.selects(p.items(page), loop)
        }
    }

    /** All of the app's items on [page], given to [answer] on the worker. */
    fun items(page: Int, answer: (List<Item>) -> Unit) {
        var found = emptyList<Item>()
        onWorker("items on page $page", then = { answer(found) }) { p -> found = p.items(page) }
    }

    /**
     * Puts a page of dotted paper after [page] and saves at once. Then
     * [SessionEvent.PagesRenumbered] shows the new page, or [SessionEvent.PageNotChanged] says
     * why it wasn't added. See [changePages].
     */
    fun addPageAfter(page: Int) = changePages(
        "added",
        page,
        pageCountChange = +1,
        newNumber = { if (it > page) it + 1 else it },
        change = { it.addDottedPage(page) },
        showPage = { page + 1 },
    )

    /**
     * Removes [page], with its ink, if it's one the app added, and saves at once. Then
     * [SessionEvent.PagesRenumbered] shows the page now in its place (the one before, if it was
     * the last), or [SessionEvent.PageNotChanged] says why it wasn't removed.
     */
    fun removeAddedPage(page: Int) = changePages(
        "removed",
        page,
        pageCountChange = -1,
        newNumber = { if (it < page) it else if (it > page) it - 1 else null },
        refuse = { p ->
            when {
                !p.isAddedPage(page) -> "Only pages you added can be removed."
                p.pageCount == 1 -> "The only page can't be removed."
                else -> null
            }
        },
        change = { it.removeAddedPage(page) },
        showPage = { p -> minOf(page, p.pageCount - 1) },
    )

    /** Whether [page] is one the app added (so it can be removed), given to [answer] on the worker. */
    fun isAddedPage(page: Int, answer: (Boolean) -> Unit) {
        var added = false
        onWorker("is page $page added", then = { answer(added) }) { p -> added = page in 0 until p.pageCount && p.isAddedPage(page) }
    }

    /**
     * Adds or removes a page, on the worker. This isn't a change in the journal like a stroke:
     * every page after it gets a new number, and the journal, the undo list and the screen all
     * go by page number. So it's saved into the file at once instead, and only then does the
     * rest of the app hear of the new numbers ([SessionEvent.PagesRenumbered]). Until then,
     * every change recorded has the old numbers.
     *
     * In order, in one task: everything recorded so far goes into the PDF under the old numbers
     * ([applyPending]); nothing is done if the file can't be saved into now; the page is added
     * or removed ([change]); the file is saved; and the undo list is emptied, its changes having
     * the old numbers. If the save fails, the open PDF holds a page the file doesn't: it's
     * opened again from the file, and the journal's changes applied again.
     *
     * The screen pauses the pen until it hears back, so nothing should be recorded meanwhile.
     * If something is (a stroke during the save), it has the old numbers, and [restartJournal]
     * gives it the new ones ([newNumber] of each old number; null for the page removed) at the
     * moment the journal moves to the new file. A save like this one also leaves a renumbering
     * note in the journal before the swap, not the usual saving note (see [save]), so a kill in
     * between can't replay old numbers onto the new file: the journal is kept aside instead.
     *
     * The caller always gets an answer, whatever goes wrong: the screen waits for one.
     */
    private fun changePages(
        what: String,
        page: Int,
        pageCountChange: Int,
        newNumber: (Int) -> Int?,
        refuse: (PdfPages) -> String? = { null },
        change: (PdfPages) -> Unit,
        showPage: (PdfPages) -> Int,
    ) {
        scheduler.post {
            // Throwable, not Exception, here and below: running out of memory must still send
            // an answer, or the screen holds the pen until the owner leaves the file.
            try {
                val p = pages ?: return@post events(SessionEvent.PageNotChanged("The file isn't open, so the page wasn't $what."))
                if (page !in 0 until p.pageCount) return@post events(SessionEvent.PageNotChanged("There's no such page."))
                // Timed like a save, so the owner's wait can be read from the log: bringing the
                // PDF up to date and checking the file, changing the page, and saving.
                val started = System.nanoTime()
                applyPending()
                val why = refuse(p) ?: whyPagesCantChange(p, what) // refuse first: it doesn't read the whole file
                if (why != null) return@post events(SessionEvent.PageNotChanged(why))
                val checkNs = System.nanoTime() - started
                var changeNs = 0L
                val saved = try {
                    change(p)
                    changeNs = System.nanoTime() - started - checkNs
                    // The file was found unchanged just above, in this same task, so the save
                    // needn't read the whole file again to check.
                    save(newPageNumber = newNumber, fileJustChecked = true)
                } catch (e: Throwable) {
                    // MuPDF failed midway: the PDF may hold part of it, and it isn't saved.
                    System.err.println("DocumentSession: a page couldn't be $what: $e")
                    false
                }
                val totalNs = System.nanoTime() - started
                System.err.println(
                    "DocumentSession: page change ms=${totalNs / 1_000_000} check=${checkNs / 1_000_000} change=${changeNs / 1_000_000} " +
                        "save=${(totalNs - checkNs - changeNs) / 1_000_000} $what=$saved",
                )
                if (saved) {
                    knownPageCount += pageCountChange
                    events(SessionEvent.HistoryChanged(canUndo = false, canRedo = false))
                    events(SessionEvent.PagesRenumbered(showPage(p)))
                } else {
                    openAgainFromTheFile()
                    events(SessionEvent.PageNotChanged("The file couldn't be saved, so the page wasn't $what."))
                }
            } catch (e: Throwable) {
                System.err.println("DocumentSession: changing pages failed: $e")
                events(SessionEvent.PageNotChanged("Something went wrong, so the page wasn't $what."))
            }
        }
    }

    /** Worker thread only. Why the file can't be saved into right now, for the owner, or null if it can. */
    private fun whyPagesCantChange(p: PdfPages, what: String): String? = when {
        applyFailed -> "A change couldn't be written into this file, so the page wasn't $what."
        checkRepair(p) -> "Answer the question about the damaged file first."
!fileUnchanged() -> "This file was changed elsewhere, so the page wasn't $what. Try again."
        else -> null
    }

    /**
     * Worker thread only, after a page was added or removed in the open PDF but not saved: the
     * PDF is opened again from the file, which never had that change. None of `unsaved` is in
     * it then, so they're all applied again, in order. If it can't be opened, the owner is
     * told, and the writing waits in the journal for the next opening.
     */
    private fun openAgainFromTheFile() {
        runCatching { pages?.close() }
        pages = null
        val reopened = try {
            open(file)
        } catch (e: Throwable) { // out of memory too: the owner must hear of it
            System.err.println("DocumentSession: opening again failed: $e")
            events(SessionEvent.OpenFailed("${file.name} couldn't be opened again. Your writing is kept safe and comes back when you open it."))
            return
        }
        pages = reopened
        knownPageCount = reopened.pageCount
        synchronized(lock) { applied = 0 }
        applyPending()
        checkRepair(reopened)
    }

    /** Turns each of [pageNumbers] by 90° clockwise, as one undo step. */
    fun rotate(pageNumbers: List<Int>) {
        onWorker("rotate $pageNumbers") { p ->
            val before = pageNumbers.associateWith { p.rotation(it) }
            record(RotatePages(before, before.mapValues { (_, degrees) -> (degrees + 90) % 360 }))
        }
    }

    /**
     * Runs [task] on the worker with the PDF brought up to date, then [then] whatever happened
     * (so a caller waiting for an answer always gets one). A failure, say a page that doesn't
     * exist, is logged and goes no further: it must never take the worker down. An Error (out
     * of memory) still gets its answer sent first, then goes on to the scheduler's report.
     */
    private fun onWorker(what: String, then: () -> Unit = {}, task: (PdfPages) -> Unit) {
        scheduler.post {
            try {
                pages?.let { p ->
                    applyPending()
                    task(p)
                }
            } catch (e: Exception) {
                // System.err reaches logcat on the tablet, and keeps this file free of Android code.
                System.err.println("DocumentSession: $what failed: $e")
            } finally {
                try {
                    then()
                } catch (e: Exception) {
                    System.err.println("DocumentSession: answering $what failed: $e")
                }
            }
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
        val started = System.nanoTime()
        var changes = 0
        while (true) {
            val next = synchronized(lock) { unsaved.getOrNull(applied) } ?: break
            try {
                p.apply(next)
            } catch (e: Exception) {
                // The PDF may now hold part of this change, so saving it could lose or double
                // something. Stop saving; the journal keeps everything for the next open.
                applyFailed = true
                System.err.println("DocumentSession: applying a change failed: $e")
                events(SessionEvent.WritingStopped)
                break
            }
            applied++
            changes++
            touched += next.pages
            rotated = rotated || next is RotatePages
        }
        // Only the slow ones: a line for every stroke would itself slow the pen's worker down.
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ms > SLOW_APPLY_MS) System.err.println("DocumentSession: apply ms=$ms changes=$changes")
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
            if (applyFailed) events(SessionEvent.WritingStopped) else save()
        }
    }

    /** The owner's other answer: leave the damaged original alone, and keep writing in a copy. */
    fun saveRepairedToCopy() {
        scheduler.post {
            val p = pages ?: return@post
            // A partly written change mustn't go into the copy either.
            if (applyFailed) return@post events(SessionEvent.WritingStopped)
            try {
                continueInCopy(p, CopyReason.DAMAGED)
            } catch (e: Throwable) { // out of memory too: the owner must hear the copy wasn't made
                System.err.println("DocumentSession: the damaged file's copy couldn't be saved: $e")
                events(SessionEvent.SaveFailed(e.message ?: "The copy couldn't be saved."))
            }
        }
    }

    /** Saves anything unsaved, then closes the PDF and stops the worker. */
    fun close() {
        scheduler.cancel(AUTOSAVE)
        scheduler.cancel(REDRAW_INK) // what's done so far is saved; the rest waits for the next opening
        scheduler.post {
            try {
                save()
                pages?.close()
                pages = null
            } finally {
                closed.complete(Unit)
                openSessions.remove(gateKey, closed)
                copyGateKeys.forEach { openSessions.remove(it, closed) }
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

    /**
     * Worker thread only. Any failure is reported: a failed save must never be silent (spec
     * §7.3). Returns whether the new version is in place.
     *
     * [newPageNumber] is given when the open PDF has just had a page added or removed (see
     * [changePages]): it's saved even with nothing journaled, and the journal moves to the new
     * page numbers with the file. That save leaves no [markSaving] note before the swap: the
     * note would let the next opening replay the changes after it onto the new file, and they
     * have the old numbers. It leaves a "renumbering" note instead, so after a kill between the
     * swap and [restartJournal] the next opening keeps the journal aside, and neither replays
     * it nor rescues it into a copy: nothing is lost or misplaced. If that note can't be
     * written, the save fails and the page isn't changed.
     *
     * [fileJustChecked]: the caller has just found the file unchanged ([fileUnchanged]) in this
     * same worker task, so the save doesn't read the whole file again to check.
     */
    private fun save(newPageNumber: ((Int) -> Int?)? = null, fileJustChecked: Boolean = false): Boolean {
        val p = pages ?: return false
        val inThisSave = applied
        if (inThisSave == 0 && !inkRedrawn && newPageNumber == null) return false
        if (applyFailed || checkRepair(p)) return false

        var swapped = false
        try {
            // Timed in three parts, to see where a slow save spends its time: checking the
            // file is still ours, writing the new PDF, and the rest (reading the new file,
            // the journal, moving it into place).
            val started = System.nanoTime()
            if (!fileJustChecked && !fileUnchanged()) return false

            // The new version is read once, while it's still the temporary file: moving it
            // into place changes nothing about it, so the file needn't be read again after.
            val checkNs = System.nanoTime() - started
            var writeNs = 0L
            var next: FileIdentity? = null
            SafeSave.replace(file, beforeSwap = { temp ->
                val result = identityOf(temp)
                next = result
                // A page change leaves a different note: see [save]'s comment.
                if (newPageNumber == null) markSaving(result, inThisSave) else synchronized(lock) { journal.markRenumbering() }
            }) {
                val writeStarted = System.nanoTime()
                p.saveTo(it)
                writeNs = System.nanoTime() - writeStarted
            }
            swapped = true
            val saved = next!!
            known = saved
            inkRedrawn = false
            restartJournal(saved, inThisSave, newPageNumber)
            val totalNs = System.nanoTime() - started
            System.err.println(
                "DocumentSession: save ms=${totalNs / 1_000_000} check=${checkNs / 1_000_000} write=${writeNs / 1_000_000} " +
                    "swap=${(totalNs - checkNs - writeNs) / 1_000_000} bytes=${file.length()}",
            )
            events(SessionEvent.Saved)
            return true
        } catch (e: Throwable) {
            // Throwable, not Exception: running out of memory while saving must be told and
            // tried again like any other failure. Nothing lost: the changes are still in
            // memory and in the journal. Try again later.
            if (newPageNumber != null && !swapped) dropRenumberingNote()
            events(SessionEvent.SaveFailed(e.message ?: "The file couldn't be saved."))
            scheduler.postDelayed(AUTOSAVE, RETRY_DELAY_MS) { save() }
            return swapped
        }
    }

    /**
     * Worker thread only. Whether the file is still as it was when opened or last saved.
     * Contents only: a sync app that just reset the file's date changed nothing. If a laptop or
     * sync app changed, moved or deleted it, writing over it would lose their change, so it's
     * never saved into: [fileChanged] sorts it out, and this returns false.
     */
    private fun fileUnchanged(): Boolean {
        val now = try { identityOf(file) } catch (e: Exception) { null }
        if (now?.sameContentAs(known) == true) {
            known = now // its date may have changed: the next quick look ([checkFile]) goes by it
            return true
        }
        fileChanged(now)
        return false
    }

    /**
     * The writing screen is back in front: a sync app may have changed the file meanwhile.
     * Usually nothing has, so the file's size and date are looked at first, and the whole file
     * is read only if they differ.
     */
    fun checkFile() {
        scheduler.post {
            val was = known ?: return@post
            if (pages == null || applyFailed || waitingForRepairDecision) return@post
            if (file.length() == was.size && file.lastModified() == was.modifiedMs) return@post
            fileUnchanged()
        }
    }

    /**
     * The screen's answer to [SessionEvent.ChangedElsewhere], with the pen held: opens the new
     * version in the old one's place, if that's still right. From any thread; the work runs on
     * the worker. It always answers with exactly one of [SessionEvent.Reloaded] or
     * [SessionEvent.NotReloaded], whatever goes wrong: the screen holds the pen until it hears.
     *
     * Not opened: a file that's as it was after all, or can't be read or opened (the old
     * version stays on screen), or writing that got in before the pen was held. That writing
     * is on the old version's pages, so it goes into a copy, as any unsaved writing does when
     * the file changes ([fileChanged]); it's never moved onto the new version's pages.
     *
     * Opened: page numbers go up by one ([pageNumbering]), as after a page change, so a stroke
     * the pen hands over late, read under the old numbers, keeps its page if the new version
     * has it, and isn't recorded if not. The screen asks only once a lifted stroke has been
     * handed over, so this is the second line of defence, for one that comes later still.
     */
    fun reload() {
        scheduler.post {
            val before = pages
            var answered = false
            fun answer(event: SessionEvent) {
                answered = true // before sending: a second answer must not follow a failed one
                events(event)
            }
            try {
                reloadOnWorker(::answer)
            } catch (t: Throwable) { // out of memory too: the screen must still hear
                System.err.println("DocumentSession: reloading failed: $t")
                // Once `pages` is the new version, it's the one open: the screen must lay it out.
                if (!answered) answer(if (pages !== before) SessionEvent.Reloaded else SessionEvent.NotReloaded)
            }
        }
    }

    /** Worker thread only: [reload]'s work, answering through [answer]. */
    private fun reloadOnWorker(answer: (SessionEvent) -> Unit) {
        val old = pages
        val now = try { identityOf(file) } catch (e: Exception) { null }
        if (old == null || now == null || now.sameContentAs(known)) {
            answer(SessionEvent.NotReloaded)
            return
        }
        val opened = try {
            open(file)
        } catch (e: Throwable) { // out of memory too: the old version stays, and the screen hears
            System.err.println("DocumentSession: the new version couldn't be opened: $e")
            answer(SessionEvent.NotReloaded)
            return
        }
        var journalFailed = false
        val swap = synchronized(lock) {
            if (unsaved.isNotEmpty()) return@synchronized false
            try {
                journal.rewrite(now, emptyList())
                journalBroken = false
            } catch (e: Throwable) { // anything: the swap goes on, and the journal counts as broken
                System.err.println("DocumentSession: the journal couldn't be rewritten: $e")
                journalFailed = !journalBroken
                journalBroken = true
            }
            undoable.clear()
            redoable.clear()
            // The new version's pages are other pages: a change read under the old numbers keeps
            // its page if the new version has one by that number, and goes nowhere if not.
            val newCount = opened.pageCount
            pageChanges += { if (it < newCount) it else null }
            pageNumbering++
            true
        }
        if (!swap) {
            runCatching { opened.close() }
            answer(SessionEvent.NotReloaded)
            // Writing got in before the pen was held: it's on the old pages, so it goes into a
            // copy of those, the way of any unsaved writing on a changed file. (Only the worker
            // empties `unsaved`, so it's still there.)
            fileChanged(now)
            return
        }
        // At once: the journal and the page numbers are the new version's now, and so must the PDF be.
        pages = opened
        knownPageCount = opened.pageCount
        applied = 0
        known = now
        inkRedrawn = false
        runCatching { old.close() }
        if (journalFailed) events(SessionEvent.JournalFailed)
        // The old version's ink pass stops; the new version gets its own, from page 0.
        scheduler.cancel(REDRAW_INK)
        inkPass++
        inkCheckNs = 0L
        inkCheckRedrawn = 0
        scheduler.postDelayed(REDRAW_INK, REDRAW_INK_DELAY_MS) { redrawFaultyInkFrom(0) }
        checkRepair(opened)
        events(SessionEvent.HistoryChanged(canUndo = false, canRedo = false))
        answer(SessionEvent.Reloaded)
    }

    /**
     * Worker thread only. Something else changed the file while it was open (spec §7.4); [now]
     * is what it looks like, or null if it can't be read (moved or deleted).
     *
     * With no writing the new version lacks, the screen is asked to hold the pen
     * ([SessionEvent.ChangedElsewhere]), and only then is the new version opened ([reload]):
     * opened now, a stroke on its way from the pen would land on the new version's pages. Ink
     * redrawn from an earlier version ([redrawFaultyInkFrom]) isn't writing: it's done again on
     * the new version.
     *
     * With unsaved writing, both are kept: the file stays as it is, and the open PDF, which is
     * the old version with the writing, is saved beside it as a "(my edits …)" copy. The
     * session continues in the copy, undo list and all. If the copy can't be saved, the owner
     * is told and it's tried again, like any save.
     */
    private fun fileChanged(now: FileIdentity?) {
        if (applyFailed) return // told already; the journal has everything for the next opening
        if (synchronized(lock) { unsaved.isEmpty() }) {
            // The screen holds the pen, then asks for the new version ([reload]). A file that's
            // gone has no new version, and nothing needs saving: nothing to do until there's writing.
            if (now != null) events(SessionEvent.ChangedElsewhere)
            return
        }
        applyPending()
        val p = pages ?: return
        if (applyFailed) return
        try {
            continueInCopy(p, CopyReason.CHANGED_WHILE_OPEN)
        } catch (e: Throwable) {
            System.err.println("DocumentSession: the conflict copy couldn't be saved: $e")
            events(SessionEvent.SaveFailed("This file was changed elsewhere, and a copy with your writing couldn't be saved"))
            scheduler.postDelayed(AUTOSAVE, RETRY_DELAY_MS) { save() }
        }
    }

    /**
     * Worker thread only, just before a save's new PDF is swapped in: the journal notes what
     * that PDF looks like and that it holds the first [count] changes. If the app is killed
     * after the swap but before [restartJournal], the next opening can tell from this that
     * those changes are in the file (see [openOnWorker]).
     *
     * Skipped while the journal is broken: its lines may then not be the same as `unsaved`,
     * so "the first [count]" could point at the wrong ones. Without the note, a kill at that
     * moment leaves a journal on the old version of the file, which the next opening rescues
     * into a "(my edits …)" copy ([rescueIntoCopy]): nothing is lost either way. And a note
     * that can't be written must not stop the save; it's a journal failure like any other.
     */
    private fun markSaving(result: FileIdentity, count: Int) {
        var tellOwner = false
        synchronized(lock) {
            if (journalBroken) return
            try {
                journal.markSaving(result, count)
            } catch (e: IOException) {
                System.err.println("DocumentSession: the journal couldn't be written: $e")
                journalBroken = true
                tellOwner = true
            }
        }
        if (tellOwner) events(SessionEvent.JournalFailed)
    }

    /**
     * Worker thread only. A page change's save failed, so the file is still the old one and the
     * changes still have its page numbers: the journal's `renumbering` note, written just
     * before the swap, would make the next opening set them aside instead of replaying them.
     * A rewrite drops notes, so the journal is rewritten with what's unsaved.
     */
    private fun dropRenumberingNote() {
        var tellOwner = false
        synchronized(lock) {
            try {
                journal.rewrite(known!!, unsaved)
            } catch (e: IOException) {
                System.err.println("DocumentSession: the journal couldn't be rewritten: $e")
                tellOwner = !journalBroken
                journalBroken = true
            }
        }
        if (tellOwner) events(SessionEvent.JournalFailed)
    }

    /**
     * After a successful save: a fresh journal holding only the changes the save didn't include.
     *
     * The PDF holds the saved changes whether or not the journal can be rewritten, so they are
     * dropped from `unsaved` either way: counting the save as failed would save the same
     * changes again and again. A journal that couldn't be rewritten still holds the old
     * version of the file and its changes; the next opening rescues those into a "(my edits …)"
     * copy ([rescueIntoCopy]), losing nothing.
     *
     * After a page was added or removed ([changePages]), the changes the save didn't include
     * were recorded with the old page numbers: they get their [newPageNumber] here, under the
     * same lock as the journal's move to the new file, and the undo list (old numbers too) is
     * emptied with it. A change on the page removed has nowhere to go: the old journal, which
     * holds it, is kept aside first, so it's never lost.
     */
    private fun restartJournal(base: FileIdentity, saved: Int, newPageNumber: ((Int) -> Int?)? = null) {
        var tellOwner = false
        synchronized(lock) {
            var remaining = unsaved.drop(saved)
            var keepOldJournal = false
            if (newPageNumber != null) {
                val moved = remaining.map { renumbered(it, newPageNumber) }
                keepOldJournal = null in moved
                remaining = moved.filterNotNull()
                undoable.clear()
                redoable.clear()
                // From here on, a change made under the old numbers is moved as it's recorded.
                pageChanges += newPageNumber
                pageNumbering++
            }
            // Before the journal is touched: the file is the new one now, so what's in memory
            // must match it even if the rewrite throws something unexpected. Otherwise changes
            // left on old page numbers would be applied to the renumbered pages.
            unsaved.clear()
            unsaved.addAll(remaining)
            applied -= saved
            try {
                // A copy: if the rewrite below fails, later strokes must still go into a whole
                // journal, with its header, not into a new file the next opening can't read.
                if (keepOldJournal) setAside(copy = true)
                journal.rewrite(base, remaining)
                journalBroken = false // whole again, with everything unsaved in it
            } catch (e: IOException) {
                System.err.println("DocumentSession: the journal couldn't be rewritten: $e")
                tellOwner = !journalBroken
                journalBroken = true
            }
        }
        if (tellOwner) events(SessionEvent.JournalFailed)
    }

    /**
     * Worker thread only. The one way into a "(my edits …)" copy: [p], with everything applied,
     * is saved as a new file beside [file], and the session continues in it. Returns the copy.
     *
     * All or nothing. [SafeSave.createNew] never writes over a file that is already there, or
     * that arrives meanwhile. If the copy is on disk but the session can't move into it (its
     * journal can't be started on a full tablet, say), the copy is deleted: it's this call's
     * own file, made a moment ago, and nothing refers to it yet. Left there, every retry would
     * add another full copy under a new name. The failure is then thrown on, the session just
     * as it was, and each caller handles it its own way.
     */
    private fun continueInCopy(p: PdfPages, why: CopyReason, notPlaced: Int = 0): File {
        val copy = CopyNames.myEdits(file, clock())
        SafeSave.createNew(copy) { p.saveTo(it) }
        try {
            checkWhole(copy)
            switchTo(copy)
        } catch (e: Throwable) {
            copy.delete()
            throw e
        }
        // From here the copy is the file, holding writing nothing else has: never deleted.
        inkRedrawn = false // the redrawn strokes are in the copy
        // The copy is the app's own file, so a repaired original needs no answer.
        repairDecided = true
        waitingForRepairDecision = false
        events(SessionEvent.SavedToCopy(copy, why, notPlaced))
        return copy
    }

    /**
     * The copy is opened once before anything relies on it: the journal that holds its
     * writing is cleared only when the copy is known to open without repair and to have the
     * pages the session knows the document has ([knownPageCount]). Not the open PDF's own
     * count: for a PDF opened by path, a file overwritten in place under it changes that count
     * too, and a copy with the other file's pages would match it. What this catches: a repaired
     * copy, and a copy whose page count differs from the one the session knew. What it can't:
     * a file replaced after opening by one with the same page count. (The writing screen reads
     * files up to 64 MB into memory, so nothing can change those under the PDF; this is the
     * check for bigger ones.) Costs one more open.
     */
    private fun checkWhole(copy: File) {
        val reopened = open(copy)
        try {
            if (reopened.wasRepaired || reopened.pageCount != knownPageCount) {
                throw IOException("the copy of ${file.name} didn't come out whole")
            }
        } finally {
            reopened.close()
        }
    }

    /**
     * Worker thread only. Continue in [copy], which has just been saved with everything applied.
     *
     * The copy's journal is started first, and only once it holds every change the copy doesn't
     * does the session move to it, in one locked step. If it can't be started, its file is
     * removed and the session is exactly as it was, still writing into the old journal: a
     * stroke after the failure goes there, with its header, and survives a kill.
     */
    private fun switchTo(copy: File) {
        val base = identityOf(copy)
        val next = ChangeJournal.forDocument(journalDir, copy)
        synchronized(lock) {
            val remaining = unsaved.drop(applied)
            try {
                next.rewrite(base, remaining)
            } catch (e: Throwable) {
                next.clear()
                throw e
            }
            val oldJournal = journal
            journal = next
            journalBroken = false // whole again, with everything unsaved in it
            file = copy
            known = base
            unsaved.clear()
            unsaved.addAll(remaining)
            applied = 0
            // From now on this session saves into the copy, so the library must wait for it
            // before renaming, moving or deleting the copy, as it does for the original.
            copyGateKeys += copy.absolutePath.also { openSessions[it] = closed }
            // Last: the old journal goes only once the new one holds what it did.
            oldJournal.clear()
        }
    }

    /** Worker thread only. Whether a request made under [numbering] has been overtaken by a page change. */
    private fun stale(numbering: Int, what: String): Boolean {
        if (numbering == pageNumbering) return false
        System.err.println("DocumentSession: $what was asked for before a page change: not done")
        return true
    }

    /**
     * Called with [lock] held. Keeps the current journal file aside, untouched. If moving it
     * fails, it's copied instead: the fresh journal that follows must never be what's left.
     * With [copy], it's always copied, so the journal stays where it is too.
     */
    private fun setAside(copy: Boolean = false) {
        val kept = File(journalDir, journal.file.name + ".kept-" + System.currentTimeMillis())
        if (copy || !journal.file.renameTo(kept)) journal.file.copyTo(kept, overwrite = false)
        events(SessionEvent.EarlierWritingKept(kept))
    }

    /** [change] with each page's [newNumber], or null if it touches a page that's gone. */
    private fun renumbered(change: Change, newNumber: (Int) -> Int?): Change? {
        if (change.pages.any { newNumber(it) == null }) return null
        fun moved(page: Int) = newNumber(page)!!
        return when (change) {
            is AddItems -> change.copy(page = moved(change.page))
            is RemoveItems -> change.copy(page = moved(change.page))
            is ReplaceItems -> change.copy(page = moved(change.page))
            is RotatePages -> RotatePages(change.before.mapKeys { moved(it.key) }, change.after.mapKeys { moved(it.key) })
        }
    }

    /** A change with nothing in it isn't worth a journal line or an undo step. */
    private fun isEmpty(change: Change) = when (change) {
        is AddItems -> change.items.isEmpty()
        is RemoveItems -> change.items.isEmpty()
        is ReplaceItems -> change.before == change.after // nothing, or a move by zero
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
        /**
         * How long after the last change the PDF is saved. Ten seconds, not three (owner,
         * 2026-11-12): every save writes the whole file again, 14 MB for a lecture deck, and
         * at three seconds each pause to think was a save. Nothing waits on it for safety: a
         * change is in the journal from the moment it's recorded, and leaving the document or
         * the app saves at once ([saveNow]).
         */
        const val AUTOSAVE_DELAY_MS = 10_000L
        const val HISTORY_LIMIT = 200
        const val RETRY_DELAY_MS = 30_000L

        /** Applying changes is only logged when it takes longer than this. */
        private const val SLOW_APPLY_MS = 5L
        private const val AUTOSAVE = "autosave"
        private const val REDRAW_INK = "redraw-ink"

        /** Long enough for the first page to be drawn before old strokes are fixed. */
        const val REDRAW_INK_DELAY_MS = 1500L

        /** Strokes redrawn per worker task: about half a second of work at most. */
        const val REDRAW_CHUNK = 25

        /** How long a new session waits for the previous one on the same file to close. */
        private const val PREVIOUS_SESSION_WAIT_MS = 15_000L

        /** Open sessions by file, so a new one can wait for the previous one to close. */
        private val openSessions = ConcurrentHashMap<String, CompletableFuture<Unit>>()

        /**
         * Waits for the session on [pdf], if there is one, to save and close: the library
         * mustn't rename, move or delete a file whose last changes are still being saved.
         * True when no session is open on it, or it closed within [timeoutMs].
         */
        /**
         * True while any session, open or still closing, is on a file directly in [folder]
         * (its copy too, once it continues in one): one of them may be saving there, so the
         * library leaves the folder's temporary files alone.
         */
        fun anyOpenIn(folder: File): Boolean {
            val dir = folder.absoluteFile
            return openSessions.keys.any { File(it).parentFile == dir }
        }

        fun awaitClosed(pdf: File, timeoutMs: Long): Boolean {
            val closing = openSessions[pdf.absolutePath] ?: return true
            return try {
                closing.get(timeoutMs, TimeUnit.MILLISECONDS)
                true
            } catch (e: TimeoutException) {
                false
            }
        }
    }
}
