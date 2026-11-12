package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Go to page" as a pad of big plain buttons, 0 to 9, ⌫ and Go, in place of a text field and
 * the system keyboard: the keyboard slides in and redraws itself key by key, a run of
 * flashes on e-ink, and it's slow to come. [current] is the page on show (from 0), shown in
 * grey until a digit is typed. [onGo] gets the page typed (from 0); [onDismiss] runs however
 * the pad closes.
 */
fun showPagePad(activity: Activity, current: Int, pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val entry = PageEntry(pageCount)
    val dp = { v: Int -> LibraryLook.dp(activity, v) }
    val typed = TextView(activity).apply {
        textSize = 30f
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(16))
        fontFeatureSettings = "tnum" // even-width digits, so the line doesn't shift as they're typed
    }
    fun showTyped() {
        // The page on show, in grey, until a digit is typed.
        typed.setTextColor(if (entry.text.isEmpty()) LibraryLook.GRAPHITE else Color.BLACK)
        typed.text = "${entry.text.ifEmpty { (current + 1).toString() }} of $pageCount"
    }
    showTyped()

    lateinit var dialog: AlertDialog
    fun key(label: String, description: String = label, onTap: () -> Unit) = TextView(activity).apply {
        text = label
        contentDescription = description
        textSize = 26f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        minHeight = dp(64)
        minWidth = dp(96)
        // The menus' black outline; grey while pressed, with no ripple (an animation).
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(LibraryLook.RULE); setStroke(3, Color.BLACK) })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.WHITE); setStroke(3, Color.BLACK) })
        }
        isClickable = true
        setOnClickListener { onTap() }
    }
    val pad = GridLayout(activity).apply {
        columnCount = 3
        val gap = dp(6)
        fun add(view: TextView) = addView(view, GridLayout.LayoutParams().apply { setMargins(gap, gap, gap, gap) })
        for (digit in 1..9) add(key(digit.toString()) { entry.press(digit); showTyped() })
        add(key("⌫", "Delete the last digit") { entry.back(); showTyped() })
        add(key("0") { entry.press(0); showTyped() })
        add(key("Go") {
            // With nothing typed, Go just closes: the page on show is where the owner is already.
            entry.page()?.let(onGo)
            dialog.dismiss()
        })
    }
    dialog = AlertDialog.Builder(activity)
        .setTitle("Go to page")
        .setView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            addView(typed)
            addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        .create()
    dialog.setOnDismissListener { onDismiss() }
    dialog.show()
}
