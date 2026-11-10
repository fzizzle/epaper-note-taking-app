package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.fzizzle.epapernotes.library.Breadcrumb
import com.fzizzle.epapernotes.library.NaturalOrder
import java.io.File
import java.util.concurrent.Executor

/**
 * A simple folder chooser: the folder it's in, a row to go up, and the folders inside to tap
 * into; then [use] picks the folder on show. It picks the library folder (decision 1) and
 * where a tile moves to (decision 5). It never goes above [top], and paths stay under it as
 * given, never canonicalized (Ruling L1).
 *
 * Folders are listed on [disk], the library's background thread: the screen's thread never
 * reads the disk. Make a new chooser for each choice.
 */
class FolderChooser(
    private val activity: Activity,
    private val disk: Executor,
    private val top: File,
    /** What the breadcrumb calls [top]. */
    private val topName: String,
    private val title: String,
    private val use: String,
    /** A folder marked "(suggested)" and listed first. */
    private val suggested: File? = null,
    /** A folder moving can't go inside itself, so it isn't offered. */
    private val leaveOut: File? = null,
    private val onCancel: () -> Unit = {},
    private val onChosen: (File) -> Unit,
) {
    private class Row(val label: String, val folder: File) {
        override fun toString() = label
    }

    private val main = Handler(Looper.getMainLooper())
    private val where = TextView(activity).apply {
        textSize = 18f
        setTextColor(LibraryLook.GRAPHITE)
        val pad = LibraryLook.dp(activity, 20)
        setPadding(pad, LibraryLook.dp(activity, 8), pad, LibraryLook.dp(activity, 8))
    }
    private val rows = object : ArrayAdapter<Row>(activity, android.R.layout.simple_list_item_1) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                textSize = 20f
                setTextColor(Color.BLACK)
                background = LibraryLook.pressedFeedback()
            }
    }
    private var folder = top
    /** Only the newest listing is shown, if taps come quicker than the disk. */
    private var asked = 0
    private var dialog: AlertDialog? = null

    fun show(start: File) {
        val list = ListView(activity).apply {
            adapter = rows
            divider = ColorDrawable(LibraryLook.RULE)
            dividerHeight = 2
            selector = ColorDrawable(Color.TRANSPARENT) // rows show their own pressed state, without a fade
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnItemClickListener { _, _, position, _ -> goTo(rows.getItem(position)!!.folder) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(where)
            // A fixed height, so the window doesn't change size (and flash) from folder to folder.
            val height = (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(use) { _, _ -> onChosen(folder) }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
            .apply { setCanceledOnTouchOutside(false) } // a stray tap beside it mustn't throw the choice away
        goTo(if (start.startsWith(top)) start else top)
    }

    private fun goTo(target: File) {
        val ask = ++asked
        disk.execute {
            val inside = target.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") && it != leaveOut }
                .sortedWith(compareBy<File> { it != suggested }.thenBy(NaturalOrder) { it.name })
            main.post { if (ask == asked && dialog?.isShowing == true) shown(target, inside) }
        }
    }

    private fun shown(target: File, inside: List<File>) {
        folder = target
        where.text = Breadcrumb.parts(top, target).mapIndexed { i, (name, _) -> if (i == 0) topName else name }.joinToString(" › ")
        rows.setNotifyOnChange(false)
        rows.clear()
        if (target != top) rows.add(Row("‹  Up", target.parentFile!!))
        for (f in inside) rows.add(Row(if (f == suggested) "${f.name}  (suggested)" else "${f.name}  ›", f))
        rows.notifyDataSetChanged()
    }
}
