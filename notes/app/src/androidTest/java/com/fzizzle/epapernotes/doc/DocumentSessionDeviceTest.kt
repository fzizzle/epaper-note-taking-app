package com.fzizzle.epapernotes.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.pdf.PdfOpenException
import com.fzizzle.epapernotes.pdf.TestPdfs
import java.io.File
import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The session with the real PDF code, the real worker thread and real disk syncs. */
@RunWith(AndroidJUnit4::class)
class DocumentSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "session-test").apply { deleteRecursively(); mkdirs() }
    private val journals = File(dir, "journals")
    private val line = Stroke(StrokeKind.PEN, (0..40).map { StrokePoint(100f + it * 5, 300f, 0.6f) })

    private class Run(pdf: File, journals: File) {
        val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
        val scheduler = WorkerScheduler()
        var opened: PdfFile? = null
        val session = DocumentSession(pdf, journals, { PdfFile.open(it).also { f -> opened = f } }, scheduler, { events += it })

        fun waitFor(timeoutMs: Long = 6000, check: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (!check()) {
                if (System.currentTimeMillis() > end) throw AssertionError("timed out; events: $events")
                Thread.sleep(50)
            }
        }
    }

    private fun strokesIn(pdf: File) = PdfFile.open(pdf).use { p -> (0 until p.pageCount).sumOf { p.strokes(it).size } }

    @Test fun strokeSurvivesKillBeforeSave() {
        val pdf = TestPdfs.blank(dir)
        val first = Run(pdf, journals).apply { session.start() }
        first.session.addStroke(0, line)
        // The app is killed: no save, no close. Queued work is dropped.
        first.scheduler.killNow()
        Thread.sleep(200)
        first.opened?.close()
        assertEquals(0, strokesIn(pdf))

        val second = Run(pdf, journals).apply { session.start() }
        // The killed session never closed, so the new one first waits out its turn (15 s).
        second.waitFor(timeoutMs = 25_000) { second.events.contains(SessionEvent.Replayed(1)) }
        second.session.saveNow()
        second.waitFor { second.events.contains(SessionEvent.Saved) }
        second.session.close()
        Thread.sleep(200)
        assertEquals(1, strokesIn(pdf))
    }

    @Test fun autosaveWritesTheFile() {
        val pdf = TestPdfs.blank(dir)
        val run = Run(pdf, journals).apply { session.start() }
        run.session.addStroke(0, line)
        run.waitFor { run.events.contains(SessionEvent.Saved) }
        run.session.close()
        Thread.sleep(200)
        assertEquals(1, strokesIn(pdf))
        assertEquals(0, ChangeJournal.forDocument(journals, pdf).read()!!.changes.size)
    }

    @Test fun repairedFileIsNotWrittenWithoutDecision() {
        val pdf = TestPdfs.brokenXref(dir)
        val repaired = try { PdfFile.open(pdf).use { it.wasRepaired } } catch (e: PdfOpenException) { false }
        assertTrue("the test file should open as repaired", repaired)
        val before = pdf.readBytes()

        val run = Run(pdf, journals).apply { session.start() }
        run.waitFor { run.events.contains(SessionEvent.NeedsRepairDecision) }
        run.session.addStroke(0, line)
        run.session.saveNow()
        Thread.sleep(1000)
        assertArrayEquals(before, pdf.readBytes())
        assertTrue(run.events.none { it == SessionEvent.Saved })
        run.scheduler.killNow()
        run.opened?.close()
    }
}
