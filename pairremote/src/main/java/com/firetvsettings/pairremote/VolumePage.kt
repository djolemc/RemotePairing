package com.firetvsettings.pairremote

import android.app.Activity
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import com.firetvsettings.pairremote.Ui.Tone
import com.firetvsettings.pairremote.Ui.button
import com.firetvsettings.pairremote.Ui.column
import com.firetvsettings.pairremote.Ui.columns
import com.firetvsettings.pairremote.Ui.dp
import com.firetvsettings.pairremote.Ui.horizontal
import com.firetvsettings.pairremote.Ui.listGap
import com.firetvsettings.pairremote.Ui.sectionTitle
import com.firetvsettings.pairremote.Ui.text
import com.firetvsettings.pairremote.Ui.textField

/**
 * "Fix volume" tab: which TV receives the remote's volume buttons (typed in, or found on the
 * network), a connection test, and the state of the background service that forwards them.
 */
class VolumePage(private val activity: Activity) : Page {

    private val handler = Handler(Looper.getMainLooper())
    private val connection = Ui.StatusCard(activity)
    private val service = Ui.StatusCard(activity)
    private val foundHint = activity.text(17f, Ui.TEXT_DIM)
    private val foundList = activity.column()
    private val setupSteps = SetupSteps(activity)
    private var searching = false
    private var shownOnce = false
    private var tvConnected = false

    private val addressField = activity.textField(R.string.address_hint).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) saveAndTest()
            false
        }
    }
    private val saveButton = activity.button(R.string.save_and_test, primary = true) { saveAndTest() }

    override val view: View = with(activity) {
        val left = column().apply {
            addView(connection)
            addView(sectionTitle(R.string.tv_address))
            addView(addressField, listGap())
            addView(horizontal(saveButton, button(R.string.find_tv) { findTv() }))
            addView(sectionTitle(R.string.service_heading))
            addView(service)
        }
        val right = column().apply {
            addView(sectionTitle(R.string.tv_list_heading).apply { setPadding(0, 0, 0, dp(10)) })
            addView(foundHint, listGap())
            addView(foundList)
            addView(sectionTitle(R.string.setup_heading))
            addView(setupSteps)
        }
        columns(left, right)
    }

    // The service starts in the background and restarts after READ_LOGS is granted, so poll while visible.
    private val refreshServiceState = object : Runnable {
        override fun run() {
            when {
                !isServiceEnabled() -> service.set(activity.getString(R.string.service_off), Tone.WARN)
                KeyLogWatcher.state == KeyLogWatcher.State.WATCHING -> service.set(activity.getString(R.string.service_on), Tone.OK)
                KeyLogWatcher.state == KeyLogWatcher.State.NO_PERMISSION -> service.set(activity.getString(R.string.service_no_permission), Tone.WARN)
                else -> service.set(activity.getString(R.string.service_starting), Tone.BUSY)
            }
            refreshSetupSteps()
            handler.postDelayed(this, 2_000)
        }
    }

    /**
     * The setup with this device's real values filled in, so the commands can be typed as shown.
     * The accessibility command lists the services that are already on, so it can't switch any of them off.
     */
    private fun refreshSetupSteps() {
        val serviceOn = isServiceEnabled()
        val logsGranted = KeyLogWatcher.hasPermission(activity)
        val adbOn = Settings.Global.getInt(activity.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val fireTvAddress = TvFinder.localIpv4() ?: activity.getString(R.string.step_fire_tv_ip_unknown)
        val ours = ComponentName(activity, VolumeKeyService::class.java).flattenToShortString()
        val enabled = enabledServices()
        val services = if (serviceOn) enabled.joinToString(":") else (enabled + ours).joinToString(":")

        setupSteps.show(listOf(
            SetupSteps.Item.Group(activity.getString(R.string.step_group_tv)),
            SetupSteps.Item.Step(activity.getString(R.string.step_tv_debugging), null, tvConnected),
            SetupSteps.Item.Step(activity.getString(R.string.step_tv_allow), null, tvConnected),
            SetupSteps.Item.Group(activity.getString(R.string.step_group_fire_tv)),
            SetupSteps.Item.Step(activity.getString(R.string.step_fire_tv_adb), null, adbOn),
            SetupSteps.Item.Step(
                activity.getString(R.string.step_connect),
                "adb connect $fireTvAddress:5555",
                serviceOn && logsGranted,
            ),
            SetupSteps.Item.Step(
                activity.getString(R.string.step_service),
                "adb shell settings put secure enabled_accessibility_services \"$services\"",
                serviceOn,
            ),
            SetupSteps.Item.Step(
                activity.getString(R.string.step_logs),
                "adb shell pm grant ${activity.packageName} android.permission.READ_LOGS",
                logsGranted,
            ),
            SetupSteps.Item.Note(activity.getString(R.string.step_note)),
        ))
    }

    init {
        foundHint.setText(R.string.tv_list_hint)
    }

    /** Tests the saved TV the first time the tab is shown, or searches for one if none is saved. */
    override fun onShown() {
        handler.removeCallbacks(refreshServiceState)
        refreshServiceState.run()
        if (shownOnce) return
        shownOnce = true
        val saved = TvAddress.load(activity)
        if (saved == null) {
            connection.set(activity.getString(R.string.no_address), Tone.INFO)
            findTv()
        } else {
            addressField.setText(saved.toString())
            testConnection()
        }
    }

    override fun onHidden() {
        handler.removeCallbacks(refreshServiceState)
    }

    override val focusTarget: View get() = saveButton

    private fun saveAndTest() {
        val address = TvAddress.parse(addressField.text.toString())
        if (address == null) {
            connection.set(activity.getString(R.string.invalid_address), Tone.WARN)
            return
        }
        TvAddress.save(activity, address)
        addressField.setText(address.toString())
        testConnection()
    }

    private fun testConnection() {
        val address = TvAddress.load(activity) ?: return
        connection.set(activity.getString(R.string.connecting, address.toString()), Tone.BUSY)
        TvLink.test(activity) { result ->
            if (activity.isDestroyed) return@test
            tvConnected = result.isSuccess
            refreshSetupSteps()
            result.onSuccess { model ->
                connection.set(activity.getString(R.string.connected, model, address.toString()), Tone.OK)
            }
            result.onFailure { e ->
                val reason = e.message ?: e.javaClass.simpleName
                connection.set(activity.getString(R.string.connect_failed, address.toString(), reason), Tone.WARN)
            }
        }
    }

    private fun findTv() {
        if (searching) return
        searching = true
        foundList.removeAllViews()
        foundHint.setText(R.string.searching)
        Thread {
            val found = TvFinder.scan()
            activity.runOnUiThread {
                searching = false
                if (!activity.isDestroyed) showFound(found)
            }
        }.start()
    }

    private fun showFound(found: List<TvFinder.Found>) {
        if (found.isEmpty()) {
            foundHint.setText(R.string.nothing_found)
            return
        }
        foundHint.text = activity.getString(R.string.found_count, found.size)
        val saved = TvAddress.load(activity)?.host
        for (device in found) {
            val row = Ui.Row(activity) {
                addressField.setText(device.address)
                saveAndTest()
            }
            val detail = if (device.address == saved) {
                "${device.address}   ·   ${activity.getString(R.string.tag_selected)}"
            } else {
                device.address
            }
            row.set(device.name ?: activity.getString(R.string.unknown_device), detail)
            foundList.addView(row, activity.listGap())
        }
        // Never saved automatically: other Android TV boxes on the network have ADB on 5555 too.
        foundList.getChildAt(0).requestFocus()
    }

    /** Accessibility services that are on, as stored by the system (for example Projectivy and Firestick Fixer). */
    private fun enabledServices(): List<String> =
        Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }

    private fun isServiceEnabled(): Boolean {
        val ours = ComponentName(activity, VolumeKeyService::class.java)
        return enabledServices().any { ComponentName.unflattenFromString(it) == ours }
    }
}
