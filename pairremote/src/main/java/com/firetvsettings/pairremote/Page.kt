package com.firetvsettings.pairremote

import android.view.View

/** One tab of [MainActivity]. */
interface Page {
    val view: View

    /** The view that gets focus when the app opens on this tab. */
    val focusTarget: View

    fun onShown()

    fun onHidden()
}
