package com.fzizzle.epapernotes.check

import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.library.CheckResult
import com.fzizzle.epapernotes.library.FileIndexDisk
import com.fzizzle.epapernotes.library.FolderListing
import com.fzizzle.epapernotes.library.IndexCheck
import com.fzizzle.epapernotes.library.NameMatch
import com.fzizzle.epapernotes.ui.NameIndexDb
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measuring only: what the name index costs on a library of 20,000 PDFs (40 courses x 100 topics x 5),
 * made fresh in a scratch folder on the card and removed at the end. The owner's library is not touched.
 */
@RunWith(AndroidJUnit4::class)
class SearchCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) = Log.i("SearchCheck", s)
    private fun <T> t(block: () -> T): Pair<T, Double> { val s = System.nanoTime(); val v = block(); return v to (System.nanoTime() - s) / 1e6 }
    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    @Test fun measure() {
        val card = File("/storage/3331-3031")
        assumeTrue(card.isDirectory)
        val root = File(card, "jot-search-scratch-big")
        val dbName = "search-check-index.db"
        var db: NameIndexDb? = null
        try {
            root.deleteRecursively()
            // Make the library.
            val (_, makeMs) = t {
                var made = 0
                for (c in 1..COURSES) {
                    for (n in 1..TOPICS) {
                        val dir = File(root, "C%02d Course %d/Topic %d".format(c, c, n)).apply { mkdirs() }
                        for (k in 1..PDFS) {
                            File(dir, "C%02d Topic %d — workbook %d.pdf".format(c, n, k)).writeBytes(byteArrayOf(0))
                            if (++made % 1000 == 0) log("made $made files")
                        }
                    }
                }
            }
            log("make: ${COURSES * TOPICS * PDFS} PDFs in ${COURSES * TOPICS} topic folders, %.1f s".format(makeMs / 1000))
            Thread.sleep(3000) // folder times must be older than the read by the check's trust margin

            ctx.deleteDatabase(dbName)
            db = NameIndexDb.open(ctx, root, dbName)
            val index = db
            fun checker(clockSkew: Long) = IndexCheck(root, index, FileIndexDisk(), { System.currentTimeMillis() + clockSkew })

            val (first, firstMs) = t { checker(0).run(full = true) }
            log("first full read: %.1f s, %s, complete=${index.complete}".format(firstMs / 1000, first.describe()))

            val (none, noneMs) = t { checker(10_000).run(full = false) }
            log("check, nothing changed: %.0f ms, %s".format(noneMs, none.describe()))

            // One change deep down: a PDF added, the folder's time moved ahead as a card written elsewhere would.
            val deep = File(root, "C%02d Course %d/Topic %d".format(COURSES, COURSES, TOPICS))
            File(deep, "C%02d Topic %d — added.pdf".format(COURSES, TOPICS)).writeBytes(byteArrayOf(0))
            deep.setLastModified(System.currentTimeMillis() + 5_000)
            val (one, oneMs) = t { checker(10_000).run(full = false) }
            log("check after one change: %.0f ms, %s".format(oneMs, one.describe()))

            // Searches: two words, answered from the index.
            val words = NameMatch.words("topic 57")
            val times = (1..5).map { t { index.search(words, 200) } }
            log("search \"topic 57\": median %.1f ms of %s, total hits ${times[0].first.total}".format(median(times.map { it.second }), times.map { "%.1f".format(it.second) }))

            // Folder listing with nothing running, then during a full re-read on a thread of its own.
            val topic = File(root, "C20 Course 20/Topic 50")
            val course = File(root, "C20 Course 20")
            fun listTimes(folder: File) = (1..20).map { t { FolderListing.list(folder, FolderListing.OPEN_OUT_UP_TO) }.second }
            listTimes(topic) // warm
            val idle = listTimes(topic)
            val idleCourse = listTimes(course)
            log("listing topic folder, idle: median %.2f ms, max %.2f".format(median(idle), idle.max()))
            log("listing course folder (100 topics), idle: median %.2f ms, max %.2f".format(median(idleCourse), idleCourse.max()))
            var during: List<Double> = emptyList()
            var duringCourse: List<Double> = emptyList()
            var stillRunning = false
            val runner = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                checker(0).run(full = true)
            }
            val (_, reMs) = t {
                runner.start()
                Thread.sleep(1000)
                during = listTimes(topic)
                duringCourse = listTimes(course)
                stillRunning = runner.isAlive
                runner.join()
            }
            log("listing topic folder, during full re-read: median %.2f ms, max %.2f (check still running at the end: $stillRunning; whole re-read incl. listings %.1f s)".format(median(during), during.max(), reMs / 1000))
            log("listing course folder (100 topics), during full re-read: median %.2f ms, max %.2f".format(median(duringCourse), duringCourse.max()))
            log("listing ratio during/idle: topic %.2f, course %.2f".format(median(during) / median(idle), median(duringCourse) / median(idleCourse)))
        } finally {
            db?.close()
            ctx.deleteDatabase(dbName)
            root.deleteRecursively()
            log("scratch folder removed: ${!root.exists()}")
        }
    }

    private fun CheckResult.describe() = when (this) {
        is CheckResult.Done -> "$foldersRead folders listed"
        else -> toString()
    }

    private companion object {
        const val COURSES = 40
        const val TOPICS = 100
        const val PDFS = 5
    }
}
