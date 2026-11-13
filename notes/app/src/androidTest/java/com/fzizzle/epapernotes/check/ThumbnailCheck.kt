package com.fzizzle.epapernotes.check

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.pdf.PdfFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measuring only: where a library thumbnail's time goes. Reads the owner's workbooks, writes nothing to them. */
@RunWith(AndroidJUnit4::class)
class ThumbnailCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) = Log.i("ThumbCheck", s)
    private fun <T> t(block: () -> T): Pair<T, Double> { val s = System.nanoTime(); val v = block(); return v to (System.nanoTime() - s) / 1e6 }

    @Test fun measure() {
        val root = File("/storage/3331-3031/Workbooks")
        assumeTrue(root.isDirectory)
        val pdfs = root.walkTopDown().filter { it.isFile && it.name.endsWith(".pdf") }.sortedBy { it.path }.toList().filterIndexed { i, _ -> i % 8 == 0 }.take(16)
        // Copies in the cache: the owner's files are only read once, by the copy.
        val dir = File(ctx.cacheDir, "thumbcheck").apply { deleteRecursively(); mkdirs() }
        val copies = pdfs.mapIndexed { i, f -> File(dir, "$i.pdf").also { f.copyTo(it) } }
        val rows = mutableListOf<DoubleArray>()
        for (f in copies) {
            val (doc, open) = t { PdfFile.open(f) }
            doc.use {
                it.render(0, 50, smoothInk = true).recycle() // warm: fonts and the page's resources
                val (b300, r300) = t { it.render(0, 300, smoothInk = true) }
                val (b240, r240) = t { it.render(0, 240, smoothInk = true) }
                val (b200, r200) = t { it.render(0, 200, smoothInk = true) }
                b300.setHasAlpha(false); b240.setHasAlpha(false)
                val (png, cPng) = t { ByteArrayOutputStream().also { o -> b300.compress(Bitmap.CompressFormat.PNG, 100, o) }.toByteArray() }
                val (png240, cPng240) = t { ByteArrayOutputStream().also { o -> b240.compress(Bitmap.CompressFormat.PNG, 100, o) }.toByteArray() }
                val (jpg, cJpg) = t { ByteArrayOutputStream().also { o -> b240.compress(Bitmap.CompressFormat.JPEG, 90, o) }.toByteArray() }
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val (_, dPng) = t { BitmapFactory.decodeByteArray(png, 0, png.size, opts) }
                val (_, dPng240) = t { BitmapFactory.decodeByteArray(png240, 0, png240.size, opts) }
                val (_, dJpg) = t { BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts) }
                val (_, copy) = t { b300.copy(Bitmap.Config.RGB_565, false) }
                rows += doubleArrayOf(open, r300, r240, r200, cPng, png.size / 1024.0, cPng240, png240.size / 1024.0, cJpg, jpg.size / 1024.0, dPng, dPng240, dJpg, copy)
                listOf(b300, b240, b200).forEach(Bitmap::recycle)
            }
        }
        val names = listOf("open", "render300", "render240", "render200", "png300ms", "png300KB", "png240ms", "png240KB", "jpg240ms", "jpg240KB", "decodePng300", "decodePng240", "decodeJpg240", "copy565")
        log("files=${rows.size}")
        names.forEachIndexed { i, n ->
            val v = rows.map { it[i] }.sorted()
            log("%-13s median=%.1f max=%.1f".format(n, v[v.size / 2], v.last()))
        }
        // The whole job as today (open + 300 px + PNG), one thread against two.
        fun job(f: File) = PdfFile.open(f).use { d -> d.render(0, 300, smoothInk = true).also { b -> b.setHasAlpha(false); ByteArrayOutputStream().also { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) }; b.recycle() } }
        val (_, serial) = t { copies.forEach { job(it) } }
        for (threads in listOf(2, 3)) {
            val pool = Executors.newFixedThreadPool(threads)
            val (_, par) = t { copies.map { f -> pool.submit { job(f) } }.forEach { it.get() } }
            pool.shutdown()
            log("all ${copies.size}: one thread %.0f ms, $threads threads %.0f ms".format(serial, par))
        }
        dir.deleteRecursively()
    }

    /** Cold, as the app meets a file for the first time; and the cache path the library uses on every later visit. */
    @Test fun coldAndCached() {
        val root = File("/storage/3331-3031/Workbooks")
        assumeTrue(root.isDirectory)
        val pdfs = root.walkTopDown().filter { it.isFile && it.name.endsWith(".pdf") }.sortedBy { it.path }.toList().filterIndexed { i, _ -> i % 8 == 3 }.take(16)
        val dir = File(ctx.cacheDir, "thumbcheck2").apply { deleteRecursively(); mkdirs() }
        val copies = pdfs.mapIndexed { i, f -> File(dir, "$i.pdf").also { f.copyTo(it) } }
        val cold = mutableListOf<DoubleArray>()
        for (f in copies) {
            val (d, open) = t { PdfFile.open(f) }
            d.use {
                val (b, first) = t { it.render(0, 300, smoothInk = true) }
                val (b2, second) = t { it.render(0, 300, smoothInk = true) }
                val (_, facts) = t { (0 until minOf(it.pageCount, 300)).any { p -> it.hasOwnItems(p) } }
                val (_, small) = t { it.render(0, 120, smoothInk = true) }
                cold += doubleArrayOf(open, first, second, facts)
                b.recycle(); b2.recycle()
            }
        }
        listOf("open", "firstRender300", "secondRender300", "factsScan").forEachIndexed { i, n ->
            val v = cold.map { it[i] }.sorted(); log("cold %-16s median=%.1f max=%.1f".format(n, v[v.size / 2], v.last()))
        }
        // The app's own cache, read as the loader does: facts file, then the PNG at 16-bit.
        val thumbs = File(ctx.cacheDir, "thumbnails").listFiles { f -> f.name.endsWith(".png") }.orEmpty().take(40)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val (_, all) = t { thumbs.forEach { png -> File(png.path.removeSuffix(".png") + ".facts").takeIf { it.isFile }?.readText(); BitmapFactory.decodeFile(png.path, opts) } }
        log("cache: ${thumbs.size} thumbnails read and decoded in %.0f ms (%.1f each)".format(all, all / thumbs.size.coerceAtLeast(1)))
        dir.deleteRecursively()
    }
}
