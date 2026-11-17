package com.fzizzle.epapernotes.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.library.CheckResult
import com.fzizzle.epapernotes.library.DiskFolder
import com.fzizzle.epapernotes.library.FileIndexDisk
import com.fzizzle.epapernotes.library.Hit
import com.fzizzle.epapernotes.library.IndexDisk
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** The index's threads on a scratch folder in Jot's own storage, with a database per test. */
@RunWith(AndroidJUnit4::class)
class NameIndexTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val testName = TestName()
    private val dbName get() = "test-NameIndexTest-${testName.methodName}.db"
    private lateinit var root: File
    private val made = mutableListOf<NameIndex>()

    /** The card, with a pause before each folder time while [delayMs] is set. */
    private class SlowDisk : IndexDisk {
        private val real = FileIndexDisk()
        @Volatile var delayMs = 0L

        override fun timeOf(folder: File): Long? {
            if (delayMs > 0) Thread.sleep(delayMs)
            return real.timeOf(folder)
        }

        override fun read(folder: File): DiskFolder? = real.read(folder)
    }

    @Before fun setUp() {
        root = File(context.getExternalFilesDir(null), "name-index-test/${testName.methodName}")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
    }

    @After fun tearDown() {
        made.forEach { it.close() }
        context.deleteDatabase(dbName)
        root.deleteRecursively()
    }

    private fun index(disk: IndexDisk = FileIndexDisk(), r: File = root) =
        NameIndex(context, r, disk, dbName).also {
            // Other tests in the run leave sessions open (their simulated kills), so no document is open here unless a test says so.
            it.documentOpen = { false }
            made += it
        }

    private fun pdf(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("%PDF") }

    /** Starts a search and gives its answers as they come. */
    private fun NameIndex.answers(query: String): LinkedBlockingQueue<NameIndex.SearchAnswer> {
        val queue = LinkedBlockingQueue<NameIndex.SearchAnswer>()
        search(query) { queue.put(it) }
        return queue
    }

    private fun LinkedBlockingQueue<NameIndex.SearchAnswer>.next(ms: Long = 10_000) =
        poll(ms, TimeUnit.MILLISECONDS).also { assertNotNull("no answer within $ms ms", it) }!!

    /** True when no further answer comes for longer than the search's wait. */
    private fun LinkedBlockingQueue<NameIndex.SearchAnswer>.noMore() =
        poll(NameIndex.CHECK_WAIT_MS + 500, TimeUnit.MILLISECONDS) == null

    private fun threePdfs() {
        pdf("Thermo Week 2 answers.pdf")
        pdf("A/Week 10.pdf")
        pdf("A/Other.pdf")
    }

    @Test fun aSearchAfterACheckAnswersOnce() {
        threePdfs()
        val index = index()
        index.startCheck(true).get(10, TimeUnit.SECONDS)
        val answers = index.answers("week")
        val answer = answers.next()
        assertEquals(NameIndex.Note.NONE, answer.note)
        assertFalse(answer.rootMissing)
        assertEquals("week", answer.query)
        assertEquals(2, answer.matches.total)
        assertEquals(listOf(Hit("", "Thermo Week 2 answers.pdf"), Hit("A", "Week 10.pdf")), answer.matches.hits)
        assertTrue(answers.noMore())
    }

    @Test fun aFileAddedSinceIsFoundBecauseTheSearchChecksFirst() {
        threePdfs()
        val index = index()
        index.startCheck(true).get(10, TimeUnit.SECONDS)
        val added = pdf("A/B/Week 3 notes.pdf")
        val folder = added.parentFile!!
        folder.setLastModified(folder.lastModified() + 5000)
        val answers = index.answers("week notes")
        val answer = answers.next()
        assertEquals(NameIndex.Note.NONE, answer.note)
        assertEquals(listOf(Hit("A/B", "Week 3 notes.pdf")), answer.matches.hits)
        assertTrue(answers.noMore())
    }

    private fun sixtyFolders() {
        for (i in 1..60) pdf("F$i/Week $i.pdf")
    }

    @Test fun aSlowCheckGivesTwoAnswers() {
        sixtyFolders()
        val disk = SlowDisk()
        val index = index(disk)
        index.startCheck(true).get(10, TimeUnit.SECONDS)
        disk.delayMs = 30
        val answers = index.answers("week")
        val first = answers.next()
        assertEquals(NameIndex.Note.CHECKING, first.note)
        assertEquals(60, first.matches.total)
        val second = answers.next()
        assertEquals(NameIndex.Note.NONE, second.note)
        assertEquals(60, second.matches.total)
    }

    @Test fun aNeverCompletedIndexSaysStillReading() {
        sixtyFolders()
        val disk = SlowDisk().apply { delayMs = 30 }
        val index = index(disk)
        val answers = index.answers("week")
        assertEquals(NameIndex.Note.STILL_READING, answers.next().note)
        val second = answers.next()
        assertEquals(NameIndex.Note.NONE, second.note)
        assertEquals(60, second.matches.total)
    }

    @Test fun aSearchDuringACheckWaitsForThatCheck() {
        sixtyFolders()
        val disk = SlowDisk()
        val index = index(disk)
        index.startCheck(true).get(10, TimeUnit.SECONDS)
        val before = index.checksStarted
        disk.delayMs = 30
        val a = index.answers("week 1")
        Thread.sleep(10)
        val b = index.answers("week 2")
        for (answers in listOf(a, b)) {
            assertEquals(NameIndex.Note.CHECKING, answers.next().note)
            assertEquals(NameIndex.Note.NONE, answers.next().note)
        }
        assertEquals(1, index.checksStarted - before)
    }

    @Test fun aMissingRootAnswersRootMissing() {
        val missing = File(root, "not here")
        val index = index(r = missing)
        val answers = index.answers("week")
        val answer = answers.next()
        assertTrue(answer.rootMissing)
        assertEquals(0, answer.matches.total)
        assertTrue(answers.noMore())
    }

    @Test fun aFullAskedDuringANormalCheckRunsAfterIt() {
        sixtyFolders()
        val disk = SlowDisk()
        val index = index(disk)
        index.startCheck(true).get(10, TimeUnit.SECONDS)
        val before = index.checksStarted
        disk.delayMs = 10
        val normal = index.startCheck(false)
        val full = index.startCheck(true)
        assertTrue(normal !== full)
        assertTrue(normal.get(10, TimeUnit.SECONDS) is CheckResult.Done)
        assertTrue(full.get(10, TimeUnit.SECONDS) is CheckResult.Done)
        assertEquals(2, index.checksStarted - before)
    }

    @Test fun aCheckThatFailsStillAnswersTheSearch() {
        threePdfs()
        val disk = object : IndexDisk {
            private val real = FileIndexDisk()
            @Volatile var fail = true

            override fun timeOf(folder: File): Long? {
                if (fail) throw IllegalStateException("a disk that breaks")
                return real.timeOf(folder)
            }

            override fun read(folder: File): DiskFolder? = real.read(folder)
        }
        val index = index(disk)
        val answer = index.answers("week").next()
        assertEquals(NameIndex.Note.NONE, answer.note)
        assertEquals(0, answer.matches.total)
        disk.fail = false
        assertEquals(2, index.answers("week").next().matches.total)
    }

    @Test fun aCheckWaitsWhileADocumentIsOpen() {
        sixtyFolders()
        val index = index()
        val open = AtomicBoolean(true)
        index.documentOpen = { open.get() }
        val check = index.startCheck(true)
        Thread.sleep(1500)
        assertFalse(check.isDone)
        open.set(false)
        assertTrue(check.get(10, TimeUnit.SECONDS) is CheckResult.Done)
    }

    @Test fun closeEndsBothThreads() {
        threePdfs()
        val index = index()
        index.startCheck(true).get(10, TimeUnit.SECONDS)
        index.answers("week").next()
        index.close()
        val deadline = System.currentTimeMillis() + 5000
        while (liveIndexThreads(index) > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(0, liveIndexThreads(index))
    }

    private fun liveIndexThreads(index: NameIndex) =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(index.threadPrefix + "-") }
}
