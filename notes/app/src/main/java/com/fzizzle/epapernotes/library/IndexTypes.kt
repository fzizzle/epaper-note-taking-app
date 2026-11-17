package com.fzizzle.epapernotes.library

import java.io.File

/**
 * One PDF found by a search. [folder] is the path from the library folder with `/` between
 * names ("" for the library folder itself); [fileName] includes ".pdf".
 */
data class Hit(val folder: String, val fileName: String)

/** What a search found: how many PDFs matched in all, and the first few of them in display order. */
data class Matches(val total: Int, val hits: List<Hit>)

/** A PDF as the index holds it: its name (with ".pdf"), size in bytes and file time in ms. */
data class IndexedPdf(val fileName: String, val size: Long, val modified: Long)

/** One listing of a folder: its folder time, the names of its subfolders, and its PDFs. */
data class DiskFolder(val time: Long, val folders: List<String>, val pdfs: List<IndexedPdf>)

/**
 * A folder as the index last saw it: its folder time then, when it was read (both ms), and the
 * names of the subfolders the index holds under it.
 */
data class KnownFolder(val time: Long, val readAt: Long, val folders: List<String>)

/**
 * What the check reads from the card. Null only when [folder] is not there or is not a folder;
 * any other error is thrown, so an unreadable folder is never taken for a deleted one.
 */
interface IndexDisk {
    fun timeOf(folder: File): Long?

    fun read(folder: File): DiskFolder?
}

/**
 * The saved index. Paths are from the library folder with `/` between names ("" is the library
 * folder itself).
 */
interface IndexStore {
    fun folder(path: String): KnownFolder?

    /** Creates or updates the folder's row (under its parent's) and replaces its PDFs; its subfolders' rows are left alone. */
    fun put(path: String, read: DiskFolder, readAt: Long)

    /** Removes the folder and everything under it. */
    fun drop(path: String)

    /** Runs [block] as one write, so a search waits at most for one batch. */
    fun <T> inBatch(block: () -> T): T

    fun search(words: List<String>, cap: Int): Matches

    /** True once a full re-read has finished: until then the index may be missing folders. */
    var complete: Boolean
}

sealed interface CheckResult {
    data class Done(val foldersRead: Int) : CheckResult

    /** The library folder wasn't there (a card taken out, say), so nothing was changed. */
    data object RootMissing : CheckResult
}
