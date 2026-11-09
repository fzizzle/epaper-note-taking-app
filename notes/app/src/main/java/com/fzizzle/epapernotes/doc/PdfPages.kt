package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Stroke
import java.io.Closeable
import java.io.File

/**
 * The parts of an open PDF the document session needs. PdfFile is the real one. Tests use a
 * fake, so the saving rules can be checked on the laptop without MuPDF.
 */
interface PdfPages : Closeable {
    val pageCount: Int
    val wasRepaired: Boolean
    fun addStroke(page: Int, stroke: Stroke): Int
    fun saveTo(target: File)
}
