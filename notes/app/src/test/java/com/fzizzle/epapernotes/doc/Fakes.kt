package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Item
import java.io.File
import java.io.IOException

/** Stands in for a real PDF. Saving writes how many items it holds, so tests can read files back. */
class FakePdfPages(override var wasRepaired: Boolean = false, private val saveDelayMs: Long = 0) : PdfPages {
    /** Each page's items, in drawing order. */
    val pageItems = mutableMapOf<Int, MutableList<Item>>()
    val rotations = mutableMapOf<Int, Int>()
    var failNextSave = false
    var saves = 0
    /** Runs in the middle of a save, to imitate the pen writing while the worker saves. */
    var duringSave: (() -> Unit)? = null
    var closed = false

    override val pageCount = 5

    val itemCount get() = pageItems.values.sumOf { it.size }

    override fun items(page: Int): List<Item> = pageItems[page].orEmpty().toList()

    /** As PdfFile does it: ids that aren't there are skipped. */
    override fun apply(change: Change) {
        when (change) {
            is AddItems -> on(change.page).addAll(change.items)
            is RemoveItems -> remove(change.page, change.items)
            is ReplaceItems -> {
                remove(change.page, change.before)
                on(change.page).addAll(change.after)
            }
            is RotatePages -> rotations.putAll(change.after)
        }
    }

    override fun rotation(page: Int) = rotations[page] ?: 0

    private fun on(page: Int) = pageItems.getOrPut(page) { mutableListOf() }

    private fun remove(page: Int, items: List<Item>) {
        val ids = items.map { it.id }.toSet()
        on(page).removeAll { it.id in ids }
    }

    override fun saveTo(target: File) {
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
