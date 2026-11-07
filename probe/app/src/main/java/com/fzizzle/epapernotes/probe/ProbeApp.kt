package com.fzizzle.epapernotes.probe

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The Onyx pen SDK calls hidden Android APIs. From Android 11 on those are blocked
        // unless exempted; Onyx's own demo apps do exactly this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }
}
