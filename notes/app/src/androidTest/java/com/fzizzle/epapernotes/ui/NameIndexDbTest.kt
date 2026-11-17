package com.fzizzle.epapernotes.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.library.DiskFolder
import com.fzizzle.epapernotes.library.Hit
import com.fzizzle.epapernotes.library.IndexedPdf
import com.fzizzle.epapernotes.library.NameMatch
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** The real SQLite store, in a database named for each test in the cache folder. */
@RunWith(AndroidJUnit4::class)
class NameIndexDbTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val testName = TestName()
    private val dbName get() = "test-${testName.methodName}.db"
    private val root = File("/x")
    private val opened = mutableListOf<NameIndexDb>()

    private fun open(r: File = root) = NameIndexDb.open(context, r, dbName).also { opened += it }

    @After fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    private fun pdfs(vararg names: String) = DiskFolder(1000, emptyList(), names.map { IndexedPdf(it, 10, 20) })

    private fun NameIndexDb.found(query: String) = search(NameMatch.words(query), 200)

    @Test fun putThenSearchFindsByEveryWord() {
        val db = open()
        db.put("", pdfs("Thermo Week 2 answers.pdf"), 5)
        db.put("A", pdfs("Résumé.pdf"), 5)
        assertEquals(listOf(Hit("", "Thermo Week 2 answers.pdf")), db.found("week ther").hits)
        assertEquals(listOf(Hit("A", "Résumé.pdf")), db.found("resume").hits)
        assertEquals(0, db.found("").total)
    }

    @Test fun putReplacesAFoldersPdfs() {
        val db = open()
        db.put("", pdfs(), 5)
        db.put("A", pdfs("x.pdf", "y.pdf"), 5)
        db.put("A", pdfs("y.pdf", "z.pdf"), 6)
        assertEquals(0, db.found("x").total)
        assertEquals(1, db.found("y").total)
        assertEquals(1, db.found("z").total)
    }

    @Test fun dropRemovesEverythingUnder() {
        val db = open()
        db.put("", pdfs(), 5)
        for (p in listOf("A", "A/B", "A/B/C", "AB")) db.put(p, pdfs("$p file.pdf"), 5)
        db.drop("A")
        assertEquals(listOf(Hit("AB", "AB file.pdf")), db.found("file").hits)
        assertNull(db.folder("A/B"))
    }

    @Test fun knownFolderListsItsSubfolders() {
        val db = open()
        db.put("", pdfs(), 5)
        db.put("A", DiskFolder(111, listOf("B", "C"), emptyList()), 222)
        db.put("A/B", pdfs(), 5)
        db.put("A/C", pdfs(), 5)
        val known = db.folder("A")!!
        assertEquals(setOf("B", "C"), known.folders.toSet())
        assertEquals(111L, known.time)
        assertEquals(222L, known.readAt)
        assertNull(db.folder("Z"))
    }

    @Test fun percentAndUnderscoreAreLiteral() {
        val db = open()
        db.put("", pdfs("50% done.pdf", "500 done.pdf", "my_notes.pdf", "myXnotes.pdf"), 5)
        assertEquals(listOf("50% done.pdf"), db.found("50%").hits.map { it.fileName })
        assertEquals(listOf("my_notes.pdf"), db.found("my_notes").hits.map { it.fileName })
    }

    @Test fun aDifferentRootStartsAfresh() {
        open(File("/x")).also {
            it.put("", pdfs("a.pdf"), 5)
            it.complete = true
            it.close()
        }
        val db = open(File("/y"))
        assertEquals(0, db.found("a").total)
        assertFalse(db.complete)
        assertNull(db.folder(""))
    }

    @Test fun theSameRootKeepsItsRows() {
        open().also {
            it.put("", pdfs("a.pdf"), 5)
            it.complete = true
            it.close()
        }
        val db = open()
        assertEquals(1, db.found("a").total)
        assertTrue(db.complete)
    }

    @Test fun aFolderWithoutItsParentIsRefused() {
        val db = open()
        db.put("", pdfs(), 5)
        try {
            db.put("A/B", pdfs("x.pdf"), 5)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertNull(db.folder("A/B"))
    }

    @Test fun aFailedBatchChangesNothing() {
        val db = open()
        db.put("", pdfs(), 5)
        try {
            db.inBatch {
                db.put("A", pdfs("x.pdf"), 5)
                throw IllegalArgumentException("stop")
            }
        } catch (_: IllegalArgumentException) {
        }
        assertNull(db.folder("A"))
        assertNotNull(db.folder(""))
    }

    @Test fun capCountsAllButReturnsCap() {
        val db = open()
        db.put("", DiskFolder(1, emptyList(), (1..300).map { IndexedPdf("week $it.pdf", 1, 1) }), 5)
        val m = db.search(NameMatch.words("week"), 50)
        assertEquals(300, m.total)
        assertEquals(50, m.hits.size)
    }

    @Test fun twentyThousandNamesAnswerInUnder100Ms() {
        val db = open()
        db.inBatch {
            db.put("", pdfs(), 5)
            for (f in 0 until 4000) {
                db.put("Folder $f", DiskFolder(1, emptyList(), (0 until 5).map { IndexedPdf("Thermo Week ${f % 12} sheet $it.pdf", 1, 1) }), 5)
            }
        }
        val times = (1..5).map {
            val start = System.nanoTime()
            val m = db.search(NameMatch.words("week 3"), NameMatch.SEARCH_LIMIT)
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i("NameIndexDbTest", "index search ms=$ms found=${m.total}")
            ms
        }
        val median = times.sorted()[2]
        Log.i("NameIndexDbTest", "index search median ms=$median")
        assertTrue("median $median ms", median < 100)
    }
}
