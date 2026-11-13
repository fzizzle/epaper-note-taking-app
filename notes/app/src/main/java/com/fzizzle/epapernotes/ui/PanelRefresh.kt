package com.fzizzle.epapernotes.ui

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * How the library's grid goes onto the e-ink panel when a folder or its thumbnails come on:
 * REGAL, the update the owner chose for page turns (little flash, no ghost left), in place of
 * the panel's default for an app, which flashes the whole screen (lighter thumbnails plan,
 * decision 4, a trial: kept only if the owner prefers it).
 *
 * With [PenInput], the only place in the app that speaks to the Onyx SDK: the library has no
 * pen and no fast-ink, so it needs nothing else from it.
 */
object PanelRefresh {
    fun gentle(view: View) {
        EpdController.invalidate(view, UpdateMode.REGAL)
    }
}
