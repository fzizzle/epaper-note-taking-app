package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import com.fzizzle.epapernotes.library.Entry
import com.fzizzle.epapernotes.library.FileOps
import com.fzizzle.epapernotes.library.FolderEntry
import com.fzizzle.epapernotes.library.PdfEntry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * The library's changes to the owner's real files: New folder, and the long-press menu's
 * Rename, Move and Delete (decision 5). Each asks first, then runs FileOps on [disk], since it
 * can wait for a document to finish saving (decision 6). A refusal is shown as a toast, and
 * either way [afterwards] re-reads the folder.
 */
class LibraryChanges(
    private val activity: Activity,
    private val disk: Executor,
    private val prefs: LibraryPrefs,
    /** Re-reads the folder, then calls its argument once the new listing is on screen. */
    private val afterwards: (done: () -> Unit) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** Changes running, each until the folder has been re-read after it. Main thread. */
    private var running = 0

    /**
     * A change is running: documents don't open and no other change starts until it's done
     * and the folder shows it, so none opens mid-rename or acts on a tile that's gone.
     */
    val busy: Boolean get() = running > 0

    fun askForNewFolder(root: File, here: File) {
        if (busy) return
        askForText("New folder", "", "Make") { name -> change(root, { it.newFolder(here, name) }) }
    }

    /** The long-press menu on [tile]. [here] is the folder on show, where Move's chooser starts. */
    fun showMenu(root: File, here: File, tile: View, entry: Entry) {
        if (busy) return
        PopupMenu(activity, tile).apply {
            menu.add("Rename").setOnMenuItemClickListener { askForName(root, entry); true }
            menu.add("Move").setOnMenuItemClickListener { askWhereTo(root, here, entry); true }
            menu.add("Delete").setOnMenuItemClickListener { askToDelete(root, entry); true }
        }.show()
    }

    private fun askForName(root: File, entry: Entry) {
        askForText("Rename", entry.name, "Rename") { typed ->
            // As FileOps names it: a PDF keeps its ".pdf".
            val name = typed.trim().let { if (entry is PdfEntry && !it.endsWith(".pdf", ignoreCase = true)) "$it.${entry.file.extension}" else it }
            change(root, { it.rename(entry.file, typed) }) { lastOpenedMoved(entry.file, File(entry.file.parentFile, name)) }
        }
    }

    private fun askWhereTo(root: File, here: File, entry: Entry) {
        FolderChooser(
            activity, disk, tops = listOf(FolderChooser.Top(root.name, root)), title = "Move '${entry.file.name}' to…", use = "Move here",
            leaveOut = entry.file.takeIf { entry is FolderEntry },
        ) { into -> change(root, { it.move(entry.file, into) }) { lastOpenedMoved(entry.file, File(into, entry.file.name)) } }
            .show(here)
    }

    /** It deletes the real files (spec §6.1), so it says what goes, including files the library doesn't show. */
    private fun askToDelete(root: File, entry: Entry) {
        val target = entry.file
        if (entry is PdfEntry) return confirmDelete(root, target, "Delete '${target.name}'?")
        val ops = ops(root)
        disk.execute {
            val pdfs = ops.pdfCountInside(target)
            val files = target.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.count { it.isFile }
            val others = (files - pdfs).coerceAtLeast(0)
            val question = buildString {
                append("Delete folder '${target.name}'")
                append(when (pdfs) { 0 -> ""; 1 -> " and the PDF inside"; else -> " and the $pdfs PDFs inside" })
                append("?")
                if (others > 0) append("\n\n${if (others == 1) "1 other file" else "$others other files"} in it will go too.")
            }
            main.post { if (!activity.isDestroyed) confirmDelete(root, target, question) }
        }
    }

    private fun confirmDelete(root: File, target: File, question: String) {
        AlertDialog.Builder(activity)
            .setMessage(question)
            .setPositiveButton("Delete") { _, _ ->
                change(root, { it.delete(target) }) {
                    val last = prefs.lastOpened
                    if (last != null && last.startsWith(target)) prefs.lastOpened = null
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Continue" follows its file through a rename or move made here. */
    private fun lastOpenedMoved(from: File, to: File) {
        val last = prefs.lastOpened ?: return
        if (last == from) prefs.lastOpened = to
        else if (last.startsWith(from)) prefs.lastOpened = File(to, last.relativeTo(from).path)
    }

    private fun ops(root: File) = FileOps(root, File(activity.filesDir, "journals"), LastPageStore(activity))

    private fun change(root: File, operation: (FileOps) -> FileOps.Result, onDone: () -> Unit = {}) {
        val ops = ops(root)
        running++
        disk.execute {
            val result = try {
                operation(ops)
            } catch (e: Exception) {
                Log.w("Notes", "library change failed", e)
                FileOps.Result.Refused("That didn't work: ${e.message}")
            }
            main.post {
                if (activity.isDestroyed) {
                    running--
                    return@post
                }
                when (result) {
                    FileOps.Result.Done -> onDone()
                    is FileOps.Result.Refused -> Toast.makeText(activity, result.reason, Toast.LENGTH_LONG).show()
                }
                afterwards { running-- }
            }
        }
    }

    private fun askForText(title: String, start: String, action: String, onEntered: (String) -> Unit) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 22f
            setSingleLine()
            setText(start)
            setSelectAllOnFocus(true)
        }
        val pad = LibraryLook.dp(activity, 20)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(field) })
            .setPositiveButton(action) { _, _ -> onEntered(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }
}
