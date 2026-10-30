package com.firetvsettings.shortcut

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Opens the native Amazon Fire OS Settings screen and finishes immediately.
 *
 * All work happens once in onCreate(), so returning from Settings never relaunches it.
 * Settings is started without FLAG_ACTIVITY_NEW_TASK, so it joins this app's task;
 * pressing Back from Settings then returns to whichever launcher opened this app
 * (e.g. Projectivy) instead of to the Amazon home screen's task.
 */
class LaunchSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fireOsSettings = Intent(Intent.ACTION_MAIN).setComponent(FIRE_OS_SETTINGS)
        val genericSettings = Intent(Settings.ACTION_SETTINGS)

        if (!tryStart(fireOsSettings) && !tryStart(genericSettings)) {
            Toast.makeText(applicationContext, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /** Starts [intent], returning false instead of crashing if it can't be launched. */
    private fun tryStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (e: RuntimeException) {
            // ActivityNotFoundException if the activity is missing, SecurityException if it's
            // not exported or needs a permission; either way, fall through to the next option.
            Log.w(TAG, "Could not start $intent", e)
            false
        }

    private companion object {
        const val TAG = "FireTvSettings"

        // Amazon-specific; may need updating if a future Fire OS version moves this activity.
        val FIRE_OS_SETTINGS = ComponentName(
            "com.amazon.tv.launcher",
            "com.amazon.tv.launcher.ui.SettingsActivity",
        )
    }
}
