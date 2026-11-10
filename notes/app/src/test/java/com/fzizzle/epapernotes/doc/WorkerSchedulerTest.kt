package com.fzizzle.epapernotes.doc

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 1.4: Java's scheduled executor keeps a task's exception to itself. On the document's
 * worker that would be a save or an opening that fails without a word.
 */
class WorkerSchedulerTest {
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val worker = WorkerScheduler("test", onError = { errors += it })

    @After fun stop() = worker.killNow()

    @Test fun aTaskThatThrowsIsReportedAndTheNextStillRuns() {
        val next = CountDownLatch(1)
        worker.post { throw IllegalStateException("x") }
        worker.post { next.countDown() }
        assertTrue(next.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("x"), errors.map { it.message })
        assertTrue(errors.single() is IllegalStateException)
    }

    @Test fun aDelayedTaskThatThrowsIsReportedAndTheNextStillRuns() {
        val next = CountDownLatch(1)
        worker.postDelayed("k", 10) { throw IllegalStateException("x") }
        worker.postDelayed("other", 20) { next.countDown() }
        assertTrue(next.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("x"), errors.map { it.message })
        assertTrue(errors.single() is IllegalStateException)
    }
}
