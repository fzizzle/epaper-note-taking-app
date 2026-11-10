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
 * where a tile moves to (decision 5). It never goes above its [tops], and paths stay under
 * them as given, never canonicalized (Ruling L1).
 *
 * With more than one top (the tablet's storage and a memory card), it starts from a list of
 * them; "Up" from the top of one goes back to that list. With one, it's the top itself.
 *
 * Folders are listed on [disk], the library's background thread: the screen's thread never
 * reads the disk. Make a new chooser for each choice.
 */
class FolderChooser(
    private val activity: Activity,
    private val disk: Executor,
    /** Where it can go no higher, each with what the breadcrumb calls it. */
    private val tops: List<Top>,
    private val title: String,
    private val use: String,
    /** A folder marked "(suggested)" and listed first. */
    private val suggested: File? = null,
    /** A folder moving can't go inside itself, so it isn't offered. */
    private val leaveOut: File? = null,
    private val onCancel: () -> Unit = {},
    private val onChosen: (File) -> Unit,
) {
    class Top(val name: String, val folder: File)

    /** [folder] null is the list of [tops]. */
    private class Row(val label: String, val folder: File?) {
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
    /** The folder on show; null while the list of [tops] is. */
    private var folder: File? = null
    /** Only the newest listing is shown, if taps come quicker than the disk. */
    private var asked = 0
    private var dialog: AlertDialog? = null

    /** Opens at [start], or at the top it's under; null (or under none) starts at the list of tops. */
    fun show(start: File?) {
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
            .setPositiveButton(use) { _, _ -> folder?.let(onChosen) ?: onCancel() }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
            .apply { setCanceledOnTouchOutside(false) } // a stray tap beside it mustn't throw the choice away
        // Set now, as "use" may be tapped before the first listing is in.
        folder = if (start != null && topOf(start) != null) start else tops.singleOrNull()?.folder
        goTo(folder)
    }

    /** The innermost top [file] is under. */
    private fun topOf(file: File): Top? = tops.filter { file.startsWith(it.folder) }.maxByOrNull { it.folder.path.length }

    private fun goTo(target: File?) {
        val ask = ++asked
        if (target == null) return showTops()
        disk.execute {
            val inside = target.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") && it != leaveOut }
                .sortedWith(compareBy<File> { it != suggested }.thenBy(NaturalOrder) { it.name })
            main.post { if (ask == asked && dialog?.isShowing == true) shown(target, inside) }
        }
    }

    private fun shown(target: File, inside: List<File>) {
        val top = topOf(target) ?: return showTops()
        folder = target
        where.text = Breadcrumb.parts(top.folder, target).mapIndexed { i, (name, _) -> if (i == 0) top.name else name }.joinToString(" › ")
        showRows {
            when {
                target != top.folder -> add(Row("‹  Up", target.parentFile!!))
                tops.size > 1 -> add(Row("‹  Up", null))
            }
            for (f in inside) add(Row(if (f == suggested) "${f.name}  (suggested)" else "${f.name}  ›", f))
        }
    }

    /** The tablet's storage and the memory card, to pick one; nothing to "use" here. */
    private fun showTops() {
        folder = null
        where.text = "Storage"
        showRows { for (t in tops) add(Row("${t.name}  ›", t.folder)) }
    }

    private fun showRows(fill: ArrayAdapter<Row>.() -> Unit) {
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = folder != null
        rows.setNotifyOnChange(false)
        rows.clear()
        rows.fill()
        rows.notifyDataSetChanged()
    }
}
