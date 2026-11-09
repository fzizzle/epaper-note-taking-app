package com.fzizzle.epapernotes.doc

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Runs a document's work, one task at a time, away from the screen and the pen. */
interface Scheduler {
    fun post(task: () -> Unit)

    /** Runs [task] after [delayMs], replacing anything still waiting under the same [key]. */
    fun postDelayed(key: String, delayMs: Long, task: () -> Unit)

    fun cancel(key: String)

    /** Finishes the work already posted, then stops. */
    fun shutdown()
}

/**
 * One worker thread per open document. It's the only thread that touches the PDF, because
 * MuPDF's objects mustn't be used from two threads at once.
 */
class WorkerScheduler(name: String = "document") : Scheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, name) }
    private val pending = mutableMapOf<String, ScheduledFuture<*>>()

    // Work posted after the document has closed (a late pen callback, say) is dropped rather
    // than crashing the app. Anything that matters is already in the journal by then.
    override fun post(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // closed
        }
    }

    @Synchronized
    override fun postDelayed(key: String, delayMs: Long, task: () -> Unit) {
        pending.remove(key)?.cancel(false)
        try {
            pending[key] = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // closed
        }
    }

    @Synchronized
    override fun cancel(key: String) {
        pending.remove(key)?.cancel(false)
    }

    override fun shutdown() {
        executor.shutdown()
    }

    /** Waits for the worker to finish after [shutdown]. Returns false if it didn't within [timeoutMs]. */
    fun awaitTermination(timeoutMs: Long): Boolean = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

    /** For tests that imitate the app being killed: stop at once, dropping queued work. */
    fun killNow() {
        executor.shutdownNow()
    }
}
