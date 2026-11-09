package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File

/**
 * A stand-in start screen until the real library (plan 4): a plain list of every PDF under
 * Documents. Tap one to write on it.
 */
class DevStartActivity : Activity() {

    override fun onResume() {
        super.onResume()
        if (!Environment.isExternalStorageManager()) {
            setContentView(Button(this).apply {
                text = "Allow access to your files"
                textSize = 24f
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                    )
                }
            })
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
        val rows = listOfNotNull(last) + pdfs
        val labels = listOfNotNull(last?.let { "▶  Continue: ${it.name}" }) + pdfs.map { it.relativeTo(root).path }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, position, _ -> open(rows[position]) }
        setContentView(list)
    }

    private fun open(pdf: File) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_OPENED, pdf.path).apply()
        startActivity(Intent(this, WritingActivity::class.java).putExtra(WritingActivity.EXTRA_PATH, pdf.path))
    }

    private companion object {
        const val PREFS = "dev-start"
        const val LAST_OPENED = "last-opened"
    }
}
