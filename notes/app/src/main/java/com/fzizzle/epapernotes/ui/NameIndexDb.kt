package com.fzizzle.epapernotes.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.fzizzle.epapernotes.library.DiskFolder
import com.fzizzle.epapernotes.library.Hit
import com.fzizzle.epapernotes.library.IndexStore
import com.fzizzle.epapernotes.library.KnownFolder
import com.fzizzle.epapernotes.library.Matches
import com.fzizzle.epapernotes.library.NameMatch
import java.io.Closeable
import java.io.File

/**
 * The name index in SQLite, in Jot's private storage. It is only a cache of the library folder:
 * when it can't be opened, its layout is old or it was made for another library folder, it is
 * deleted and made again empty. Write-ahead logging lets a search read while a check writes.
 */
class NameIndexDb private constructor(private val db: SQLiteDatabase) : IndexStore, Closeable {
    override var complete: Boolean
        get() = meta(KEY_COMPLETE) == "1"
        set(value) = setMeta(KEY_COMPLETE, if (value) "1" else "0")

    override fun folder(path: String): KnownFolder? {
        val row = db.rawQuery("SELECT id, time, read_at FROM folders WHERE path = ?", arrayOf(path)).use {
            if (it.moveToFirst()) Triple(it.getLong(0), it.getLong(1), it.getLong(2)) else null
        } ?: return null
        val names = db.rawQuery("SELECT path FROM folders WHERE parent = ?", arrayOf(row.first.toString())).use {
            buildList { while (it.moveToNext()) add(it.getString(0).substringAfterLast('/')) }
        }
        return KnownFolder(row.second, row.third, names)
    }

    override fun put(path: String, read: DiskFolder, readAt: Long) {
        inBatch { putRows(path, read, readAt) }
    }

    private fun putRows(path: String, read: DiskFolder, readAt: Long) {
        // A folder is only put once its parent is known (the check reads parents first), so a
        // missing parent is a bug here, not something to paper over.
        val parent: Long? = if (path.isEmpty()) null else {
            val parentPath = if ('/' in path) path.substringBeforeLast('/') else ""
            idOf(parentPath) ?: throw IllegalStateException("No row for the parent of \"$path\"")
        }
        val values = ContentValues().apply {
            put("path", path)
            put("parent", parent)
            put("time", read.time)
            put("read_at", readAt)
        }
        // Update in place so the folder keeps its id (and its subfolders their parent).
        val id = idOf(path)?.also { db.update("folders", values, "id = ?", arrayOf(it.toString())) }
            ?: db.insertOrThrow("folders", null, values)
        db.delete("pdfs", "folder = ?", arrayOf(id.toString()))
        val insert = db.compileStatement("INSERT INTO pdfs (folder, file_name, key, size, modified) VALUES (?, ?, ?, ?, ?)")
        insert.use { st ->
            for (pdf in read.pdfs) {
                st.clearBindings()
                st.bindLong(1, id)
                st.bindString(2, pdf.fileName)
                st.bindString(3, NameMatch.key(pdf.fileName.dropLast(PDF_SUFFIX_LENGTH)))
                st.bindLong(4, pdf.size)
                st.bindLong(5, pdf.modified)
                st.executeInsert()
            }
        }
    }

    override fun drop(path: String) {
        inBatch {
            if (path.isEmpty()) {
                db.delete("pdfs", null, null)
                db.delete("folders", null, null)
            } else {
                val under = "${escapeLike(path)}/%"
                val where = "path = ? OR path LIKE ? ESCAPE '\\'"
                db.execSQL("DELETE FROM pdfs WHERE folder IN (SELECT id FROM folders WHERE $where)", arrayOf(path, under))
                db.delete("folders", where, arrayOf(path, under))
            }
        }
    }

    override fun <T> inBatch(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun search(words: List<String>, cap: Int): Matches {
        if (words.isEmpty()) return Matches(0, emptyList())
        val where = words.joinToString(" AND ") { "p.key LIKE ? ESCAPE '\\'" }
        val args = words.map { NameMatch.likePattern(it) }.toTypedArray()
        val total = db.rawQuery("SELECT COUNT(*) FROM pdfs p WHERE $where", args).use { it.moveToFirst(); it.getInt(0) }
        val hits = db.rawQuery(
            "SELECT f.path, p.file_name FROM pdfs p JOIN folders f ON f.id = p.folder WHERE $where ORDER BY f.path, p.file_name LIMIT $cap",
            args,
        ).use { buildList { while (it.moveToNext()) add(Hit(it.getString(0), it.getString(1))) } }
        return Matches(total, NameMatch.arrange(hits, cap))
    }

    override fun close() = db.close()

    private fun idOf(path: String): Long? =
        db.rawQuery("SELECT id FROM folders WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMeta(key: String, value: String) = setMeta(db, key, value)

    companion object {
        const val LAYOUT = 1
        private const val KEY_LAYOUT = "layout"
        private const val KEY_ROOT = "root"
        private const val KEY_COMPLETE = "complete"
        private const val PDF_SUFFIX_LENGTH = 4 // ".pdf" in any case: only PDFs are indexed

        fun open(context: Context, root: File, name: String = "library-index.db"): NameIndexDb {
            val file = context.getDatabasePath(name)
            file.parentFile?.mkdirs()
            val db = tryOpen(file, root) ?: run {
                context.deleteDatabase(name)
                tryOpen(file, root) ?: throw IllegalStateException("Can't make the name index at $file")
            }
            return NameIndexDb(db)
        }

        /** The database ready for [root], or null when it can't be opened or was made for something else. */
        private fun tryOpen(file: File, root: File): SQLiteDatabase? {
            val db = try {
                SQLiteDatabase.openOrCreateDatabase(file, null)
            } catch (_: Exception) {
                return null
            }
            // Everything after the opening in here, so a failure closes the connection before the file is deleted.
            try {
                db.enableWriteAheadLogging()
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'meta'", null).use { it.count }
                if (tables == 0) {
                    create(db, root)
                } else {
                    val layout = metaOf(db, KEY_LAYOUT)
                    val madeFor = metaOf(db, KEY_ROOT)
                    if (layout != LAYOUT.toString() || madeFor != root.path) throw IllegalStateException("other index")
                }
                return db
            } catch (_: Exception) {
                db.close()
                return null
            }
        }

        private fun create(db: SQLiteDatabase, root: File) {
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, parent INTEGER, time INTEGER NOT NULL, read_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX folders_parent ON folders (parent)")
                db.execSQL("CREATE TABLE pdfs (id INTEGER PRIMARY KEY, folder INTEGER NOT NULL, file_name TEXT NOT NULL, key TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX pdfs_folder ON pdfs (folder)")
                db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                setMeta(db, KEY_LAYOUT, LAYOUT.toString())
                setMeta(db, KEY_ROOT, root.path)
                setMeta(db, KEY_COMPLETE, "0")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private fun metaOf(db: SQLiteDatabase, key: String): String? =
            db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

        private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
            db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value))
        }

        private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
