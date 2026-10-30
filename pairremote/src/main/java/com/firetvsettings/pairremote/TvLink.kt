package com.firetvsettings.pairremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps one ADB connection to the TV open and runs volume commands on it, one at a time.
 *
 * `cmd media_session volume` talks to the TV's audio service directly (~0.2 s), while
 * `input keyevent` starts a new Java process on every call (~1.6 s on a TCL Google TV).
 */
object TvLink {

    enum class Command(val shell: String) {
        VOLUME_UP("cmd media_session volume --show --stream 3 --adj raise"),
        VOLUME_DOWN("cmd media_session volume --show --stream 3 --adj lower"),
        // media_session has no mute toggle; this one is slow but rarely used.
        MUTE("input keyevent KEYCODE_VOLUME_MUTE"),
    }

    private const val TAG = "VolumeBridge"
    private const val CONNECT_TIMEOUT_MS = 3_000
    // Also bounds how long the first connection waits for "Allow debugging?" on the TV.
    private const val SOCKET_TIMEOUT_MS = 30_000
    // Holding a key repeats it faster than the TV can apply it; drop the excess instead of overshooting.
    private const val MAX_PENDING = 3

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = AtomicInteger()

    // Only touched on the executor thread.
    private var dadb: Dadb? = null
    private var dadbAddress: TvAddress? = null
    private var keyPair: AdbKeyPair? = null

    fun send(context: Context, command: Command) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                val start = System.currentTimeMillis()
                runOnTv(appContext, command.shell)
                Log.i(TAG, "$command took ${System.currentTimeMillis() - start} ms")
            } catch (e: Exception) {
                Log.w(TAG, "$command failed", e)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** Opens the connection ahead of the first key press. Failures are only logged. */
    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                connection(appContext)
            } catch (e: Exception) {
                Log.i(TAG, "TV not reachable yet: $e")
            }
        }
    }

    /** Connects (reconnecting if needed) and reports the TV model, or the error, on the main thread. */
    fun test(context: Context, onResult: (Result<String>) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching { runOnTv(appContext, "getprop ro.product.model").trim() }
            result.exceptionOrNull()?.let { Log.w(TAG, "Test failed", it) }
            mainHandler.post { onResult(result) }
        }
    }

    private fun runOnTv(context: Context, shell: String): String {
        for (attempt in 1..2) {
            try {
                return connection(context).shell(shell).allOutput
            } catch (e: Exception) {
                // A TV that slept or changed address leaves a dead socket behind: reconnect once.
                disconnect()
                if (attempt == 2) throw e
            }
        }
        error("unreachable")
    }

    private fun connection(context: Context): Dadb {
        val address = TvAddress.load(context) ?: throw IllegalStateException("No TV address set")
        dadb?.let { if (address == dadbAddress) return it }
        disconnect()
        return Dadb.create(address.host, address.port, keyPair(context), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
            .also {
                // Dadb connects lazily; connect now so authentication errors surface here.
                it.supportsFeature("shell_v2")
                dadb = it
                dadbAddress = address
                Log.i(TAG, "Connected to $address")
            }
    }

    private fun disconnect() {
        runCatching { dadb?.close() }
        dadb = null
        dadbAddress = null
    }

    /** The app's own ADB key. The TV asks once to allow it ("Allow debugging?"). */
    private fun keyPair(context: Context): AdbKeyPair {
        keyPair?.let { return it }
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
            // Shown next to the key fingerprint in some ADB authorization prompts.
            publicKey.writeText(publicKey.readText().replace(" unknown@unknown", " VolumeBridge@FireTV"))
        }
        return AdbKeyPair.read(privateKey, publicKey).also { keyPair = it }
    }
}

/** The TV's ADB endpoint, entered as `192.168.1.28` or `192.168.1.28:5555`. */
data class TvAddress(val host: String, val port: Int) {

    override fun toString() = if (port == DEFAULT_PORT) host else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 5555
        private const val PREFS = "volume_bridge"
        private const val KEY_ADDRESS = "tv_address"

        fun parse(text: String): TvAddress? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val host = trimmed.substringBefore(':')
            val port = if (':' in trimmed) trimmed.substringAfter(':').toIntOrNull() else DEFAULT_PORT
            if (host.isEmpty() || port == null || port !in 1..65535) return null
            return TvAddress(host, port)
        }

        fun load(context: Context): TvAddress? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDRESS, null)?.let(::parse)

        fun save(context: Context, address: TvAddress) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ADDRESS, address.toString())
                .apply()
        }
    }
}
