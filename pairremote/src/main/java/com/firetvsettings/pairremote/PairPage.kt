package com.firetvsettings.pairremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.firetvsettings.pairremote.Ui.Tone
import com.firetvsettings.pairremote.Ui.button
import com.firetvsettings.pairremote.Ui.card
import com.firetvsettings.pairremote.Ui.column
import com.firetvsettings.pairremote.Ui.columns
import com.firetvsettings.pairremote.Ui.dp
import com.firetvsettings.pairremote.Ui.horizontal
import com.firetvsettings.pairremote.Ui.listGap
import com.firetvsettings.pairremote.Ui.sectionTitle
import com.firetvsettings.pairremote.Ui.text

/**
 * "Pair remote" tab: finds Bluetooth remotes and bonds with them directly, bypassing the Fire OS pairing UI.
 *
 * Runs classic discovery and a BLE scan side by side: BLE remotes usually advertise their
 * name, which classic discovery often reports late or not at all. Devices whose name
 * contains one of [REMOTE_NAME_KEYWORDS] are paired automatically; any other device can
 * be paired by selecting it in the list.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is preceded by hasPermissions().
class PairPage(
    private val activity: Activity,
    private val requestPermissions: (Array<String>) -> Unit,
    private val requestEnableBluetooth: () -> Unit,
) : Page {

    private class NearbyDevice(val device: BluetoothDevice, val row: Ui.Row) {
        var name: String? = null
        var rssi: Int? = null
    }

    private val adapter: BluetoothAdapter? =
        activity.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable { stopScan(finished = true) }

    private val status = Ui.StatusCard(activity)
    private val nearbyList = activity.column()
    private val nearbyEmpty = activity.text(17f, Ui.TEXT_DIM)
    private val pairedList = activity.card()
    private val nearby = LinkedHashMap<String, NearbyDevice>()
    private var target: BluetoothDevice? = null
    private var scanning = false
    private var bleScanning = false
    private var scannedOnce = false
    private var receiverRegistered = false

    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            // Fire OS 7/8 (API 28/30): BLUETOOTH and BLUETOOTH_ADMIN are install-time permissions.
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    private val scanButton = activity.button(R.string.scan_again, primary = true) { startScan() }

    override val view: View = with(activity) {
        val left = column().apply {
            addView(status)
            addView(horizontal(scanButton), listGap().apply { topMargin = dp(16) })
            addView(sectionTitle(R.string.paired_devices))
            addView(pairedList)
        }
        val right = column().apply {
            addView(sectionTitle(R.string.nearby_devices).apply { setPadding(0, 0, 0, dp(10)) })
            addView(nearbyEmpty)
            addView(nearbyList)
        }
        columns(left, right)
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onDeviceSeen(result.device, result.scanRecord?.deviceName, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed with error $errorCode")
            bleScanning = false
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.bluetoothDevice() ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    onDeviceSeen(
                        device,
                        intent.getStringExtra(BluetoothDevice.EXTRA_NAME),
                        rssi.takeIf { it != Short.MIN_VALUE }?.toInt(),
                    )
                }
                // Classic discovery often resolves a device's name only after reporting it.
                BluetoothDevice.ACTION_NAME_CHANGED -> nearby[device.address]?.let { entry ->
                    updateName(entry, intent.getStringExtra(BluetoothDevice.EXTRA_NAME))
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondStateChanged(
                    device,
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR),
                )
            }
        }
    }

    init {
        if (adapter == null) {
            setStatus(activity.getString(R.string.no_adapter), Tone.WARN)
        } else {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            // These are protected broadcasts sent by the Bluetooth stack (not the system UID),
            // so the receiver must be exported to receive them on API 33+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                activity.registerReceiver(bluetoothReceiver, filter)
            }
            receiverRegistered = true
            setStatus(activity.getString(R.string.pair_ready), Tone.INFO)
        }
        showNearbyEmpty()
    }

    /** Scans automatically the first time the tab is shown. */
    override fun onShown() {
        if (hasPermissions()) refreshPairedDevices()
        if (!scannedOnce) startScan()
    }

    /** Scanning is power-hungry and slows down pairing; never leave it running in the background. */
    override fun onHidden() {
        if (scanning) stopScan(finished = true)
    }

    fun onDestroy() {
        stopScan(finished = false)
        if (receiverRegistered) activity.unregisterReceiver(bluetoothReceiver)
    }

    override val focusTarget: View get() = scanButton

    fun onPermissionsResult(denied: Collection<String>) {
        if (denied.isEmpty()) {
            ensureBluetoothOnAndScan()
        } else {
            setStatus(activity.getString(R.string.permissions_denied, denied.joinToString { it.substringAfterLast('.') }), Tone.WARN)
        }
    }

    fun onEnableBluetoothResult() {
        if (adapter?.isEnabled == true) startScan() else setStatus(activity.getString(R.string.bluetooth_off), Tone.WARN)
    }

    private fun startScan() {
        if (adapter == null) return
        scannedOnce = true
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions)
            return
        }
        ensureBluetoothOnAndScan()
    }

    private fun ensureBluetoothOnAndScan() {
        val adapter = adapter ?: return
        if (!adapter.isEnabled) {
            requestEnableBluetooth()
            return
        }

        stopScan(finished = false)
        target = null
        nearby.clear()
        nearbyList.removeAllViews()
        refreshPairedDevices()

        val discoveryStarted = adapter.startDiscovery()
        adapter.bluetoothLeScanner?.let { scanner ->
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, bleScanCallback)
            bleScanning = true
        }
        if (!discoveryStarted && !bleScanning) {
            setStatus(activity.getString(R.string.scan_failed), Tone.WARN)
            return
        }

        scanning = true
        handler.postDelayed(scanTimeout, SCAN_DURATION_MS)
        if (isLocationOff()) {
            setStatus(activity.getString(R.string.scanning) + "\n" + activity.getString(R.string.location_off), Tone.WARN)
        } else {
            setStatus(activity.getString(R.string.scanning), Tone.BUSY)
        }
        showNearbyEmpty()
    }

    private fun stopScan(finished: Boolean) {
        handler.removeCallbacks(scanTimeout)
        val wasScanning = scanning
        scanning = false
        val adapter = adapter
        if (adapter == null || !hasPermissions() || !adapter.isEnabled) return

        adapter.cancelDiscovery()
        if (bleScanning) {
            adapter.bluetoothLeScanner?.stopScan(bleScanCallback)
            bleScanning = false
        }
        if (finished && wasScanning && target == null) setStatus(activity.getString(R.string.scan_finished), Tone.INFO)
        showNearbyEmpty()
    }

    private fun onDeviceSeen(device: BluetoothDevice, advertisedName: String?, rssi: Int?) {
        val entry = nearby.getOrPut(device.address) {
            val row = Ui.Row(activity) { pair(device) }
            nearbyList.addView(row, activity.listGap())
            NearbyDevice(device, row).also { Log.i(TAG, "Found ${device.address}") }
        }
        if (rssi != null) entry.rssi = rssi
        updateName(entry, advertisedName ?: device.name)
        showNearbyEmpty()
    }

    private fun updateName(entry: NearbyDevice, name: String?) {
        // Classic discovery and BLE advertisements can report different names for one device
        // (BLE often carries a shortened one, e.g. "beac" for "gDevice-beacon"); keep the longest.
        val current = entry.name
        if (!name.isNullOrBlank() && (current == null || name.length > current.length)) {
            entry.name = name
            Log.i(TAG, "Name of ${entry.device.address}: $name")
        }
        describe(entry)

        val knownName = entry.name
        if (target == null && knownName != null && isRemote(knownName)) pair(entry.device)
    }

    private fun pair(device: BluetoothDevice) {
        if (!hasPermissions()) return
        target = device
        stopScan(finished = false)

        val label = labelOf(device)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            setStatus(activity.getString(R.string.already_paired, label), Tone.OK)
            return
        }
        setStatus(activity.getString(R.string.pairing, label), Tone.BUSY)
        if (!device.createBond()) {
            setStatus(activity.getString(R.string.bond_rejected, label), Tone.WARN)
        }
    }

    private fun onBondStateChanged(device: BluetoothDevice, state: Int) {
        refreshPairedDevices()
        nearby[device.address]?.let { describe(it) }
        if (device.address != target?.address) return

        val label = labelOf(device)
        when (state) {
            BluetoothDevice.BOND_BONDING -> setStatus(activity.getString(R.string.pairing, label), Tone.BUSY)
            BluetoothDevice.BOND_BONDED -> setStatus(activity.getString(R.string.paired, label), Tone.OK)
            BluetoothDevice.BOND_NONE -> setStatus(activity.getString(R.string.pair_failed, label), Tone.WARN)
        }
    }

    private fun refreshPairedDevices() {
        pairedList.removeAllViews()
        val devices = adapter?.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
        if (devices.isEmpty()) {
            pairedList.addView(activity.text(17f, Ui.TEXT_DIM).apply { setText(R.string.none) })
        }
        for (device in devices) {
            pairedList.addView(activity.text(17f).apply { text = device.name ?: activity.getString(R.string.no_name) })
            pairedList.addView(activity.text(14f, Ui.TEXT_DIM).apply {
                text = device.address
                setPadding(0, 0, 0, activity.dp(8))
            })
        }
    }

    private fun showNearbyEmpty() {
        nearbyEmpty.visibility = if (nearby.isEmpty()) View.VISIBLE else View.GONE
        nearbyEmpty.setText(if (scanning) R.string.nearby_searching else R.string.nearby_none)
    }

    private fun describe(entry: NearbyDevice) {
        val details = buildList {
            add(entry.device.address)
            entry.rssi?.let { add(activity.getString(R.string.signal, it)) }
            kindOf(entry.device)?.let { add(activity.getString(it)) }
            if (entry.device.bondState == BluetoothDevice.BOND_BONDED) add(activity.getString(R.string.tag_paired))
        }
        entry.row.set(entry.name ?: activity.getString(R.string.no_name), details.joinToString("   ·   "))
    }

    private fun kindOf(device: BluetoothDevice): Int? =
        when (device.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.PERIPHERAL -> R.string.kind_input
            BluetoothClass.Device.Major.AUDIO_VIDEO -> R.string.kind_audio_video
            BluetoothClass.Device.Major.PHONE -> R.string.kind_phone
            BluetoothClass.Device.Major.COMPUTER -> R.string.kind_computer
            else -> null
        }

    private fun labelOf(device: BluetoothDevice): String =
        nearby[device.address]?.name ?: device.name ?: device.address

    private fun isRemote(name: String): Boolean =
        REMOTE_NAME_KEYWORDS.any { keyword -> name.contains(keyword, ignoreCase = true) }

    private fun hasPermissions(): Boolean = requiredPermissions.all { permission ->
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 10/11 (Fire OS 8) only delivers scan results while Location is enabled. */
    private fun isLocationOff(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            return false
        }
        return activity.getSystemService(LocationManager::class.java)?.isLocationEnabled == false
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun setStatus(text: String, tone: Tone) {
        Log.i(TAG, text)
        status.set(text, tone)
    }

    private companion object {
        const val TAG = "RemotePairing"
        const val SCAN_DURATION_MS = 30_000L

        /**
         * Case-insensitive substrings of the Bluetooth names of remotes to pair automatically.
         * Add a remote's exact name here once it shows up in the on-screen list.
         */
        val REMOTE_NAME_KEYWORDS = listOf(
            "notus", "eon",   // SBB EON
            "iris", "mts",    // MTS IRIS
            "r11b",           // MTS IRIS remote, advertised as "R11b"
        )
    }
}
