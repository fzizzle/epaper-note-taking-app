package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Item
import java.io.File
import java.io.IOException

/** Stands in for a real PDF. Saving writes how many items it holds, so tests can read files back. */
class FakePdfPages(override var wasRepaired: Boolean = false, private val saveDelayMs: Long = 0, pages: Int = 5) : PdfPages {
    /** Each page's items, in drawing order. */
    val pageItems = mutableMapOf<Int, MutableList<Item>>()
    val rotations = mutableMapOf<Int, Int>()
    var failNextSave = false
    /** The next apply writes half of the change, then throws, as a MuPDF error midway might. */
    var failNextApply = false
    /** Every save throws this until it's cleared: an OutOfMemoryError, say, which isn't an Exception. */
    var failSaveWith: Throwable? = null
    var saves = 0
    /** The next page added or removed throws this, before changing anything. */
    var failPageChangeWith: Throwable? = null
    /** Runs in the middle of a save, to imitate the pen writing while the worker saves. */
    var duringSave: (() -> Unit)? = null
    /** Runs while items are read, to imitate the pen writing while the worker works out an erase. */
    var duringItems: (() -> Unit)? = null
    var closed = false
    /** How many strokes on each page open with faulty ink drawings; redrawing one fixes it for good. */
    val faultyStrokes = mutableMapOf<Int, Int>()
    /** Runs at each redraw call (a page, or a part of one), to imitate the screen or the pen meanwhile. */
    var duringRedraw: ((page: Int) -> Unit)? = null

    /** Five pages to start with, unless a test asks for other; adding and removing pages changes it. */
    override var pageCount = pages
        private set

    /** The pages the app added ([addDottedPage]). A test may mark a page here, as if added in an earlier session. */
    val addedPages = mutableSetOf<Int>()

    val itemCount get() = pageItems.values.sumOf { it.size }

    override fun hasOwnItems(page: Int): Boolean = pageItems[page].orEmpty().isNotEmpty()

    override fun items(page: Int): List<Item> {
        checkPage(page)
        val now = pageItems[page].orEmpty().toList()
        duringItems?.invoke()
        return now
    }

    /**
     * As PdfFile does it: ids that aren't there are skipped, an add skips ids already there, and
     * a move only moves items still on the page.
     */
    override fun apply(change: Change) {
        if (failNextApply && change is ReplaceItems) {
            failNextApply = false
            remove(change.page, change.before)
            throw IllegalStateException("mupdf error")
        }
        if (failNextApply) {
            failNextApply = false
            throw IllegalStateException("mupdf error")
        }
        when (change) {
            is AddItems -> {
                val there = ids(change.page)
                on(change.page).addAll(change.items.filter { it.id !in there })
            }
            is RemoveItems -> remove(change.page, change.items)
            is ReplaceItems -> {
                val move = change.stillThere(ids(change.page))
                remove(change.page, move.before)
                on(change.page).addAll(move.after)
            }
            is RotatePages -> rotations.putAll(change.after)
        }
    }

    override fun rotation(page: Int): Int {
        checkPage(page)
        return rotations[page] ?: 0
    }

    /** As PdfFile does: later pages move up by one, with their items, rotation and faulty strokes. */
    override fun addDottedPage(after: Int) {
        failPageChangeWith?.let { failPageChangeWith = null; throw it }
        checkPage(after)
        renumber { if (it > after) it + 1 else it }
        addedPages += after + 1
        pageCount++
    }

    override fun isAddedPage(page: Int): Boolean {
        checkPage(page)
        return page in addedPages
    }

    /** As PdfFile does: only an added page, never the only page, and later pages move down by one. */
    override fun removeAddedPage(page: Int) {
        failPageChangeWith?.let { failPageChangeWith = null; throw it }
        require(pageCount > 1) { "page $page is the only page" }
        require(page in addedPages) { "page $page wasn't added by the app" }
        pageItems.remove(page)
        rotations.remove(page)
        faultyStrokes.remove(page)
        addedPages.remove(page)
        renumber { if (it > page) it - 1 else it }
        pageCount--
    }

    /** Gives every page kept about a new number, [to] of its old one. */
    private fun renumber(to: (Int) -> Int) {
        renumber(pageItems, to)
        renumber(rotations, to)
        renumber(faultyStrokes, to)
        val added = addedPages.map(to)
        addedPages.clear()
        addedPages.addAll(added)
    }

    private fun <V> renumber(map: MutableMap<Int, V>, to: (Int) -> Int) {
        val moved = map.mapKeys { to(it.key) }
        map.clear()
        map.putAll(moved)
    }

    /** As PdfFile does: a page that isn't there is an error. */
    private fun checkPage(page: Int) {
        if (page !in 0 until pageCount) throw IndexOutOfBoundsException("no page $page")
    }

    private fun on(page: Int) = pageItems.getOrPut(page) { mutableListOf() }

    private fun ids(page: Int) = on(page).map { it.id }.toSet()

    private fun remove(page: Int, items: List<Item>) {
        val ids = items.map { it.id }.toSet()
        on(page).removeAll { it.id in ids }
    }

    override fun redrawFaultyInk(page: Int, limit: Int): InkRedraw {
        checkPage(page)
        duringRedraw?.invoke(page)
        val left = faultyStrokes.remove(page) ?: 0
        val now = minOf(left, limit)
        if (left > now) faultyStrokes[page] = left - now
        return InkRedraw(now, more = left > now)
    }

    override fun saveTo(target: File) {
        failSaveWith?.let { throw it }
        if (failNextSave) {
            failNextSave = false
            target.writeText("half")
            throw IOException("storage full")
        }
        saves++
        val count = itemCount
        if (saveDelayMs > 0) Thread.sleep(saveDelayMs)
        duringSave?.invoke()
        target.writeText("items=$count")
    }

    override fun close() {
        closed = true
    }
}

/**
 * Runs posted work straight away, on the test's thread, like a worker with nothing else to do.
 * Work posted while a task is running waits until that task has finished, as it would on the
 * real single worker thread. Delayed work waits for [runDelayed].
 */
class FakeScheduler : Scheduler {
    val delayed = linkedMapOf<String, () -> Unit>()
    private val queue = ArrayDeque<() -> Unit>()
    private var running = false

    override fun post(task: () -> Unit) {
        queue.addLast(task)
        if (running) return
        running = true
        try {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        } finally {
            running = false
        }
    }

    override fun postDelayed(key: String, delayMs: Long, task: () -> Unit) {
        delayed[key] = task
    }

    override fun cancel(key: String) {
        delayed.remove(key)
    }

    override fun shutdown() = Unit

    fun runDelayed() {
        val tasks = delayed.values.toList()
        delayed.clear()
        tasks.forEach { post(it) }
    }
}
