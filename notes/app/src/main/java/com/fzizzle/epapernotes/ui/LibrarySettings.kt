package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.fzizzle.epapernotes.library.Volumes
import java.io.File
import java.util.concurrent.Executor

/**
 * What the library remembers, in the app's private storage: the library folder (decision 1),
 * the folder on show, the file opened last (for "Continue", decision 3) and the thumbnail
 * switch (decision 10). Paths are kept exactly as given (Ruling L1).
 */
class LibraryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    init {
        // The temporary start list kept the file opened last under its own name: carry it over once.
        val old = context.getSharedPreferences("dev-start", Context.MODE_PRIVATE)
        old.getString("last-opened", null)?.let { last ->
            if (!prefs.contains(LAST_OPENED)) prefs.edit().putString(LAST_OPENED, last).apply()
            old.edit().clear().apply()
        }
    }

    var root: File?
        get() = file(ROOT)
        set(value) = put(ROOT, value)

    var folderOnShow: File?
        get() = file(FOLDER)
        set(value) = put(FOLDER, value)

    var lastOpened: File?
        get() = file(LAST_OPENED)
        set(value) = put(LAST_OPENED, value)

    /** The document the Continue picture shows (ContinuePicture): the one left last, if its picture was made. */
    var continuePictureOf: File?
        get() = file(CONTINUE_PICTURE)
        set(value) = put(CONTINUE_PICTURE, value)

    var showThumbnails: Boolean
        get() = prefs.getBoolean(THUMBNAILS, true)
        set(value) = prefs.edit().putBoolean(THUMBNAILS, value).apply()

    /** A folder of up to three PDFs is shown as its PDFs (FolderListing). On unless turned off. */
    var openOutSmallFolders: Boolean
        get() = prefs.getBoolean(OPEN_OUT, true)
        set(value) = prefs.edit().putBoolean(OPEN_OUT, value).apply()

    private fun file(key: String) = prefs.getString(key, null)?.let(::File)

    private fun put(key: String, value: File?) = prefs.edit().putString(key, value?.path).apply()

    private companion object {
        const val ROOT = "root"
        const val FOLDER = "folder-on-show"
        const val LAST_OPENED = "last-opened"
        const val THUMBNAILS = "show-thumbnails"
        const val OPEN_OUT = "open-out-small-folders"
        const val CONTINUE_PICTURE = "continue-picture-of"
    }
}

/**
 * The chooser for the library folder (decision 1), with Documents suggested on the tablet's
 * storage. With a memory card in, it can be there too: the chooser then starts from the list
 * of both, unless [start] is in one.
 */
fun chooseLibraryFolder(activity: Activity, disk: Executor, start: File?, onCancel: () -> Unit = {}, onChosen: (File) -> Unit) {
    val volumes = StorageVolumes.mounted(activity)
    // Alone, the tablet's storage keeps the name it always had here.
    val tops = volumes.map { FolderChooser.Top(if (volumes.size == 1) "Storage" else it.label, it.directory) }
    FolderChooser(
        activity, disk, tops = tops,
        title = "Choose the folder that holds your notes", use = "Use this folder",
        suggested = File(STORAGE, Environment.DIRECTORY_DOCUMENTS),
        onCancel = onCancel, onChosen = onChosen,
    ).show(start)
}

/**
 * Settings, from ⋯ (decision 10): **Library folder** opens the chooser; **Show thumbnails**
 * turns the first-page pictures on and off. Both take effect at once.
 */
fun showLibrarySettings(
    activity: Activity,
    prefs: LibraryPrefs,
    disk: Executor,
    onRootChosen: (File) -> Unit,
    onThumbnailsChanged: (Boolean) -> Unit,
    /** A setting changed what the folder on show lists: read it again. */
    onListingChanged: () -> Unit = {},
) {
    val pad = LibraryLook.dp(activity, 20)
    fun row(title: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, LibraryLook.dp(activity, 14), pad, LibraryLook.dp(activity, 14))
        background = LibraryLook.pressedFeedback()
        isClickable = true
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply { text = title; textSize = 20f; setTextColor(Color.BLACK) })
            addView(TextView(activity).apply { textSize = 16f; setTextColor(LibraryLook.GRAPHITE) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    fun LinearLayout.detail() = (getChildAt(0) as LinearLayout).getChildAt(1) as TextView

    val folderRow = row("Library folder")
    fun showRoot() {
        val root = prefs.root ?: return
        folderRow.detail().text = Volumes.whereIs(StorageVolumes.mounted(activity), root)
    }
    showRoot()
    folderRow.setOnClickListener {
        chooseLibraryFolder(activity, disk, start = prefs.root ?: STORAGE) { chosen ->
            onRootChosen(chosen)
            showRoot()
        }
    }

    // A plain On/Off box rather than a switch, whose sliding knob would flash on e-ink.
    fun onOffRow(title: String, detail: String, isOn: () -> Boolean, set: (Boolean) -> Unit): LinearLayout {
        val row = row(title)
        row.detail().text = detail
        val state = TextView(activity).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = LibraryLook.dp(activity, 64)
            setPadding(LibraryLook.dp(activity, 12), LibraryLook.dp(activity, 6), LibraryLook.dp(activity, 12), LibraryLook.dp(activity, 6))
        }
        fun showState() {
            val on = isOn()
            state.text = if (on) "On" else "Off"
            state.setTextColor(if (on) Color.WHITE else Color.BLACK)
            state.background = GradientDrawable().apply {
                setColor(if (on) Color.BLACK else Color.WHITE)
                setStroke(3, Color.BLACK)
            }
        }
        showState()
        row.addView(state)
        row.setOnClickListener {
            set(!isOn())
            showState()
        }
        return row
    }

    val thumbnailsRow = onOffRow("Show thumbnails", "The first page of each PDF", { prefs.showThumbnails }) {
        prefs.showThumbnails = it
        onThumbnailsChanged(it)
    }
    val openOutRow = onOffRow("Open out small folders", "A folder of up to three PDFs is shown as its PDFs", { prefs.openOutSmallFolders }) {
        prefs.openOutSmallFolders = it
        onListingChanged()
    }

    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(folderRow)
        addView(View(activity).apply { setBackgroundColor(LibraryLook.RULE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2))
        addView(thumbnailsRow)
        addView(View(activity).apply { setBackgroundColor(LibraryLook.RULE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2))
        addView(openOutRow)
    }
    AlertDialog.Builder(activity)
        .setTitle("Settings")
        .setView(content)
        .setPositiveButton("Done", null)
        .show()
}

/** Shown until the app has "All files access": it edits the owner's PDFs where they are (spec §6.1). */
fun fileAccessPrompt(activity: Activity): View = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    val pad = LibraryLook.dp(activity, 32)
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
        setPadding(LibraryLook.dp(activity, 24), LibraryLook.dp(activity, 16), LibraryLook.dp(activity, 24), LibraryLook.dp(activity, 16))
        background = GradientDrawable().apply { setStroke(3, Color.BLACK) }
        foreground = LibraryLook.pressedFeedback()
        isClickable = true
        setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")))
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = LibraryLook.dp(activity, 24) })
}
