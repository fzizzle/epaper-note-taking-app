package com.fzizzle.epapernotes

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Onyx's pen SDK reaches Android APIs that are normally hidden from apps. Its own demo
        // apps lift the restriction like this, and fast-ink doesn't work without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }
}
