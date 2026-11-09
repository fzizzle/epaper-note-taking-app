package com.fzizzle.epapernotes.pdf

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MuPDF 1.28.5's Java binding drops a reference it never took in `PDFObject.push(PDFObject)`
 * and `PDFDocument.addObject`, so the object is freed while the document still uses it, and the
 * app crashes later, far from the cause (.superpowers/sdd/2026-11-09-tools/mupdf-crash-report.md,
 * MuPdfStressTest). A cheap guard: the app's code must never call either. Numbers go in with
 * push(Float), which is safe. The scan is simple on purpose: any `addObject(`, and any `push(`
 * whose argument starts with `doc.` or `new`, which is how a PDFObject would be made.
 */
class NoRefcountTrapsTest {
    private val trap = Regex("""\baddObject\s*\(|\bpush\s*\(\s*(doc\.|new)""")

    @Test fun noRefcountTraps() {
        // Gradle runs unit tests in the module folder; allow for the project folder too.
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val sources = main.walk().filter { it.extension == "kt" || it.extension == "java" }.toList()
        assertTrue("no sources found under $main", sources.isNotEmpty())
        val found = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .filter { (_, line) -> trap.containsMatchIn(line) }
                .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals(
            "MuPDF refcount trap: never push a PDFObject or call addObject; see " +
                ".superpowers/sdd/2026-11-09-tools/mupdf-crash-report.md\n" + found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
    }

    /** The scan itself catches what it should. */
    @Test fun scanSpotsTheTraps() {
        assertTrue(trap.containsMatchIn("arr.push(doc.newDictionary())"))
        assertTrue(trap.containsMatchIn("arr.push( newName(\"X\"))"))
        assertTrue(trap.containsMatchIn("val ref = doc.addObject(dict)"))
        assertTrue(!trap.containsMatchIn("listOf(b.x0, b.y0).forEach { push(it) }"))
    }
}
