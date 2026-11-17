package com.fzizzle.epapernotes.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.fzizzle.epapernotes.doc.DocumentSession
import com.fzizzle.epapernotes.library.CheckResult
import com.fzizzle.epapernotes.library.FileIndexDisk
import com.fzizzle.epapernotes.library.IndexCheck
import com.fzizzle.epapernotes.library.IndexDisk
import com.fzizzle.epapernotes.library.Matches
import com.fzizzle.epapernotes.library.NameMatch
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The library's name index and its two threads: the index thread checks the card against it
 * (at background priority, never the library's disk thread, so folder visits don't wait), and
 * the reader thread answers searches from it without touching the card. One per process
 * ([forRoot]); a new library folder closes the old one.
 */
class NameIndex internal constructor(
    context: Context,
    val root: File,
    private val disk: IndexDisk = FileIndexDisk(),
    private val dbName: String = DB_NAME,
) {
    /** One answer to [search]. [note] says whether a later answer is coming; [rootMissing]: the library folder isn't there (a card that's out). */
    data class SearchAnswer(val query: String, val matches: Matches, val note: Note, val rootMissing: Boolean)

    /** The line above the results: nothing, or why a second answer will follow. */
    enum class Note { NONE, CHECKING, STILL_READING }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Set by [forRoot] to the previous index's closing, so its database is shut before this one opens the same file. */
    @Volatile private var openAfter: Future<*>? = null
    private val closedDown = CompletableFuture<Unit>()

    // Opened on first use, on one of the index's own threads, never the screen's.
    private val storeOpening = lazy {
        try {
            openAfter?.get(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w("Notes", "the previous name index didn't close in time", e)
        }
        NameIndexDb.open(appContext, root, dbName)
    }
    private val store by storeOpening

    /**
     * True while a document is open, when the check waits. Tests set their own: a tablet test
     * run keeps the sessions of its simulated kills open for good.
     */
    @Volatile internal var documentOpen: () -> Boolean = DocumentSession::anyOpen

    private val checker by lazy {
        IndexCheck(
            root, store, disk, System::currentTimeMillis,
            // Between batches: stop for good once closed, and wait while a document is open.
            pause = { if (closed) throw Closed(); documentOpen() },
            onError = { folder, e -> Log.w("Notes", "index can't read $folder; kept as it was", e) },
        )
    }

    private val indexThread = Executors.newSingleThreadExecutor(threads("$THREAD_PREFIX-check", background = true))
    private val readerThread = ScheduledThreadPoolExecutor(1, threads("$THREAD_PREFIX-search", background = false)).apply {
        // A search's wait that hasn't ended by the close is dropped with it.
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    private val lock = Any()
    @Volatile private var closed = false
    private var running: CompletableFuture<CheckResult?>? = null // under lock
    private var runningFull = false // under lock
    private var queuedFull: CompletableFuture<CheckResult?>? = null // under lock

    /** Index thread only: the last check or reread ended early, so a new subfolder may be left unread under a parent that looks current. */
    private var nextMustBeFull = false

    private val started = AtomicInteger()

    /** How many checks have been started; for tests. */
    internal val checksStarted: Int get() = started.get()

    /** Thrown from the check's pause once the index is closed, to stop it within a batch. */
    private class Closed : RuntimeException()

    /** Starts a check unless one is running; a full one asked for while a normal one runs is run after it. */
    fun check(full: Boolean) {
        startCheck(full)
    }

    /** The check that answers this request: the running one, a full one queued after it, or a new one. Null result: it failed. */
    internal fun startCheck(full: Boolean): CompletableFuture<CheckResult?> = synchronized(lock) {
        if (closed) return CompletableFuture.completedFuture(null)
        val now = running ?: return begin(full, CompletableFuture())
        if (!full || runningFull) return now
        queuedFull ?: CompletableFuture<CheckResult?>().also { queuedFull = it }
    }

    /** Under [lock]. */
    private fun begin(full: Boolean, done: CompletableFuture<CheckResult?>): CompletableFuture<CheckResult?> {
        running = done
        runningFull = full
        started.incrementAndGet()
        indexThread.execute { runCheck(full, done) }
        return done
    }

    private fun runCheck(full: Boolean, done: CompletableFuture<CheckResult?>) {
        var result: CheckResult? = null
        try {
            if (closed) return
            val start = SystemClock.elapsedRealtime()
            var asFull = full
            try {
                // An index that has never read the library folder reads everything anyway, so
                // that first check counts as a full one and the index is then complete.
                asFull = full || nextMustBeFull || store.folder("") == null
                result = checker.run(asFull)
            } catch (e: Closed) {
                Log.i("Notes", "index closed: its check stopped")
            } catch (e: Exception) {
                Log.w("Notes", "index check failed", e)
            }
            nextMustBeFull = result !is CheckResult.Done
            val read = (result as? CheckResult.Done)?.foldersRead ?: 0
            val outcome = when (result) {
                is CheckResult.Done -> "Done"
                CheckResult.RootMissing -> "RootMissing"
                null -> "error"
            }
            Log.i("Notes", "index check full=$asFull foldersRead=$read result=$outcome ms=${SystemClock.elapsedRealtime() - start}")
        } finally {
            val next = synchronized(lock) {
                running = null
                val queued = queuedFull
                queuedFull = null
                if (queued != null && !closed) {
                    begin(true, queued)
                    null
                } else {
                    queued
                }
            }
            next?.complete(null)
            done.complete(result)
        }
    }

    /** Re-reads [folders] (made from [root]) on the index thread, after whatever is queued there. */
    fun reread(folders: List<File>) {
        synchronized(lock) {
            if (closed) return
            indexThread.execute {
                if (closed) return@execute
                try {
                    // Before the first check has read the library folder there is nothing to
                    // bring up to date: that check reads everything, and a reread now would read
                    // the whole library twice.
                    if (store.folder("") == null) return@execute
                    checker.reread(folders)
                    // A reread cut short by a card taken out may have left a new subfolder unread.
                    if (disk.timeOf(root) == null) nextMustBeFull = true
                } catch (e: Closed) {
                    // Closing: nothing more to do.
                } catch (e: Exception) {
                    nextMustBeFull = true
                    Log.w("Notes", "index reread failed", e)
                }
            }
        }
    }

    /**
     * Finds [query] in the index after checking the card, waiting up to [CHECK_WAIT_MS] for that
     * check. In time: one answer. Not in time: an answer at once with a [Note] and a second one,
     * with [Note.NONE], when the check ends. Answers come on the main thread; the caller drops
     * those for an older query. A closed index answers nothing more: its root is no longer the
     * library's.
     */
    fun search(query: String, onAnswer: (SearchAnswer) -> Unit) {
        onReader {
            if (!root.isDirectory) {
                deliver(onAnswer, SearchAnswer(query, NOTHING, Note.NONE, rootMissing = true))
                return@onReader
            }
            val check = startCheck(false)
            val wait = readerThread.schedule({
                // A check that ended meanwhile is answered by the task it queued here.
                if (!closed && !check.isDone) deliver(onAnswer, found(query, stillRunningNote()))
            }, CHECK_WAIT_MS, TimeUnit.MILLISECONDS)
            check.whenComplete { result, _ ->
                onReader {
                    wait.cancel(false)
                    val answer = if (result == CheckResult.RootMissing) {
                        SearchAnswer(query, NOTHING, Note.NONE, rootMissing = true)
                    } else {
                        found(query, Note.NONE)
                    }
                    deliver(onAnswer, answer)
                }
            }
        }
    }

    /** Stops both threads; the check stops within one batch. The database is closed after the last search. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // Runs after the check and anything queued on the index thread, then after the
            // search under way, so the database closes only once nothing uses it.
            indexThread.execute {
                try {
                    readerThread.execute {
                        try {
                            if (storeOpening.isInitialized()) store.close()
                        } catch (e: Exception) {
                            Log.w("Notes", "name index close failed", e)
                        } finally {
                            closedDown.complete(Unit)
                        }
                    }
                } finally {
                    readerThread.shutdown()
                }
            }
            indexThread.shutdown()
        }
    }

    private fun stillRunningNote(): Note =
        try {
            if (store.complete) Note.CHECKING else Note.STILL_READING
        } catch (e: Exception) {
            Log.w("Notes", "name index unreadable", e)
            Note.CHECKING
        }

    /** The store's matches for [query], the first [NameMatch.SEARCH_LIMIT] in display order. */
    private fun found(query: String, note: Note): SearchAnswer {
        val matches = try {
            val m = store.search(NameMatch.words(query), SEARCH_CAP)
            Matches(m.total, NameMatch.arrange(m.hits, NameMatch.SEARCH_LIMIT))
        } catch (e: Exception) {
            // Still answered, so the screen isn't left waiting.
            Log.w("Notes", "index search failed", e)
            NOTHING
        }
        return SearchAnswer(query, matches, note, rootMissing = false)
    }

    private fun deliver(onAnswer: (SearchAnswer) -> Unit, answer: SearchAnswer) {
        main.post { if (!closed) onAnswer(answer) }
    }

    private fun onReader(task: () -> Unit) {
        try {
            readerThread.execute { if (!closed) task() }
        } catch (_: RejectedExecutionException) {
            // Closed: nothing more is answered.
        }
    }

    companion object {
        /** How long a search waits for its check before answering from the index as it is. */
        const val CHECK_WAIT_MS = 1000L

        /** The most hits asked of the store; they are put in order before the first 200 are shown. */
        const val SEARCH_CAP = 5000

        internal const val THREAD_PREFIX = "name-index"
        private const val DB_NAME = "library-index.db"
        private const val CLOSE_WAIT_MS = 30_000L
        private val NOTHING = Matches(0, emptyList())

        private var shared: NameIndex? = null

        /** The index for [root]: the same one while the library folder stays, a new one (the old closed) when it changes. */
        @Synchronized fun forRoot(context: Context, root: File): NameIndex {
            val old = shared
            if (old != null) {
                if (old.root == root) return old
                old.close()
            }
            return NameIndex(context, root).also {
                it.openAfter = old?.closedDown
                shared = it
            }
        }

        private fun threads(name: String, background: Boolean) = ThreadFactory { task ->
            Thread({
                if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, name)
        }
    }
}
