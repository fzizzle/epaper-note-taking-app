package com.fzizzle.epapernotes

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder start screen. The library screen replaces it in plan 4. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "E-paper Notes"; textSize = 32f })
    }
}
