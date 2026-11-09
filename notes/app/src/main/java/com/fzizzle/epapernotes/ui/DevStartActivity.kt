package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File

/**
 * A stand-in start screen until the real library (plan 4): a plain list of every PDF under
 * Documents, the last one opened first. Tap one to write on it.
 */
class DevStartActivity : Activity() {

    /** A double tap (easy on e-ink) must not open the same file twice. Reset when this screen returns. */
    private var opening = false

    override fun onResume() {
        super.onResume()
        opening = false
        if (!Environment.isExternalStorageManager()) {
            setContentViewInsideBars(askForFiles())
            return
        }

        // For checks over adb: `am start … --es path <pdf>` opens a file straight away.
        intent.getStringExtra(WritingActivity.EXTRA_PATH)?.let { path ->
            intent.removeExtra(WritingActivity.EXTRA_PATH)
            open(File(path))
        }

        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val pdfs = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) && !SafeSave.isTempFile(it.name) }
            .sortedBy { it.path }
            .toList()
        // The file opened last goes first, so coming back to the app is one tap.
        val last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_OPENED, null)?.let { File(it) }?.takeIf { it.exists() }
        // Real paths on both sides: "/sdcard/…" is a shortcut to "/storage/emulated/0/…".
        val realRoot = root.canonicalFile
        fun folderOf(f: File) = f.canonicalFile.parentFile?.relativeTo(realRoot)?.path?.ifEmpty { null } ?: "Documents"
        val rows = listOfNotNull(last?.let { Row(it, "Continue: ${it.nameWithoutExtension}", folderOf(it)) }) +
            pdfs.map { Row(it, it.nameWithoutExtension, folderOf(it)) }

        val list = ListView(this).apply {
            adapter = RowAdapter(rows)
            divider = ColorDrawable(RULE)
            dividerHeight = 2
            selector = ColorDrawable(Color.TRANSPARENT) // rows show their own pressed state, without a fade
            setOnItemClickListener { _, _, position, _ -> open(rows[position].file) }
        }
        setContentViewInsideBars(list)
    }

    /** One row: a title (the file's name, or "Continue: …" for the last one opened) and its folder underneath. */
    private class Row(val file: File, val title: String, val folder: String)

    /** Plain rows, reused as the list scrolls, so even a long course tree scrolls without lag. */
    private inner class RowAdapter(private val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView as? LinearLayout ?: newRow()
            val row = rows[position]
            (view.getChildAt(0) as TextView).text = row.title
            (view.getChildAt(1) as TextView).text = row.folder
            return view
        }

        private fun newRow() = LinearLayout(this@DevStartActivity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(14), pad, dp(14))
            background = pressedFeedback()
            addView(TextView(context).apply { textSize = 20f; setTextColor(Color.BLACK) })
            addView(TextView(context).apply { textSize = 15f; setTextColor(GRAPHITE) })
        }
    }

    private fun askForFiles(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(32)
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = "To open your PDFs and write on them where they are, the app needs access to your files."
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        addView(TextView(context).apply {
            text = "Allow file access"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply { setStroke(3, Color.BLACK) }
            foreground = pressedFeedback()
            isClickable = true
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
    }

    /** A plain grey while pressed, with no fade: animations only cause flashing on e-ink. */
    private fun pressedFeedback() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(RULE))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        setExitFadeDuration(0)
        setEnterFadeDuration(0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Android 15+ draws apps under the status bar. Keep everything below it, so the top row can be tapped. */
    private fun setContentViewInsideBars(view: View) {
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(view)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    private fun open(pdf: File) {
        if (opening) return
        opening = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_OPENED, pdf.path).apply()
        startActivity(Intent(this, WritingActivity::class.java).putExtra(WritingActivity.EXTRA_PATH, pdf.path))
    }

    private companion object {
        const val PREFS = "dev-start"
        const val LAST_OPENED = "last-opened"
        val GRAPHITE = Color.rgb(77, 77, 77)
        val RULE = Color.rgb(200, 200, 200)
    }
}
