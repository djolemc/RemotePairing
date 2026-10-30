package com.firetvsettings.pairremote

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps Volume Bridge running in the background: Fire OS binds enabled accessibility services
 * at boot and keeps them alive.
 *
 * It doesn't filter keys or read screen content (Fire OS consumes volume keys before
 * accessibility services see them anyway); [KeyLogWatcher] reads the keys instead.
 */
class VolumeKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        TvLink.prewarm(this)
        KeyLogWatcher.start(this)
    }

    override fun onDestroy() {
        KeyLogWatcher.stop()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
