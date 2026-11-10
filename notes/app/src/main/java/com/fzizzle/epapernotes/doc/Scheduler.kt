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
 *
 * Anything a task throws goes to [onError]. Left alone, Java's scheduled executor keeps a
 * delayed task's exception to itself and reports nothing, and a failure nobody hears about
 * is the worst kind here. The default prints it, which reaches logcat on the tablet.
 */
class WorkerScheduler(
    name: String = "document",
    private val onError: (Throwable) -> Unit = { System.err.println("worker task failed: $it") },
) : Scheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, name) }
    private val pending = mutableMapOf<String, ScheduledFuture<*>>()

    // Work posted after the document has closed (a late pen callback, say) is dropped rather
    // than crashing the app. Anything that matters is already in the journal by then.
    override fun post(task: () -> Unit) {
        try {
            executor.execute(reporting(task))
        } catch (e: RejectedExecutionException) {
            // closed
        }
    }

    @Synchronized
    override fun postDelayed(key: String, delayMs: Long, task: () -> Unit) {
        pending.remove(key)?.cancel(false)
        try {
            pending[key] = executor.schedule(reporting(task), delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // closed
        }
    }

    /** [task], with anything it throws (out of memory too) handed to [onError] instead of lost. */
    private fun reporting(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (t: Throwable) {
            onError(t)
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
