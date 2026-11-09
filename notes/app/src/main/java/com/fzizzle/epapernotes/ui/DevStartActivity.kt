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
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, pdfs.map { it.relativeTo(root).path })
        list.setOnItemClickListener { _, _, position, _ -> open(pdfs[position]) }
        setContentView(list)
    }

    private fun open(pdf: File) {
        startActivity(Intent(this, WritingActivity::class.java).putExtra(WritingActivity.EXTRA_PATH, pdf.path))
    }
}
