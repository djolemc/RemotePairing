package com.firetvsettings.pairremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process as AndroidProcess
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Picks up volume key presses from the Fire OS system log and forwards them to the TV.
 *
 * Fire OS consumes volume keys before apps and accessibility services see them, and it drops
 * ADB connections an app opens to its own device, so `getevent` is out of reach too. What stays
 * visible is the log line Amazon's device-control service writes for every key:
 * `Received Key name: VOLUME_UP, code: 24, action: 0`. Reading other apps' logs needs READ_LOGS,
 * which is granted once from a computer with `adb shell pm grant`.
 */
object KeyLogWatcher {

    enum class State { STOPPED, NO_PERMISSION, WATCHING }

    @Volatile
    var state = State.STOPPED
        private set

    private const val TAG = "VolumeBridge"
    // Log tags are capped at 23 characters, so Amazon's tag is truncated in the log itself.
    private const val DEVICE_CONTROL_TAG = "com_amazon_devicecont.."
    private const val RETRY_DELAY_MS = 5_000L
    // Our own repeat while a key is held; DOWN lines repeated by Fire OS are ignored.
    private const val REPEAT_DELAY_MS = 400L
    private const val REPEAT_INTERVAL_MS = 200L

    private val KEY_LINE = Regex("""Received Key name: \w+, code: (\d+), action: (\d+)""")
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val LOG_GID = 1007 // AID_LOG
    private const val MIN_RESTART_INTERVAL_MS = 10 * 60_000L

    private val repeater = Executors.newSingleThreadScheduledExecutor()
    private var repeating: ScheduledFuture<*>? = null
    private var held: TvLink.Command? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var process: Process? = null

    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val appContext = context.applicationContext
        thread = Thread({ watchLoop(appContext) }, "KeyLogWatcher").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
        process?.destroy()
        release()
        state = State.STOPPED
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    private fun watchLoop(context: Context) {
        while (!Thread.currentThread().isInterrupted) {
            if (!hasPermission(context)) {
                state = State.NO_PERMISSION
            } else if (!processHasLogGroup()) {
                restartForLogAccess(context)
            } else {
                state = State.WATCHING
                try {
                    // -T 1: start at the newest line instead of replaying the whole buffer.
                    val logcat = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "$DEVICE_CONTROL_TAG:I")
                        .redirectErrorStream(true)
                        .start()
                    process = logcat
                    Log.i(TAG, "Watching key log")
                    logcat.inputStream.bufferedReader().forEachLine { onLine(context, it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Key log watcher stopped: $e")
                } finally {
                    process?.destroy()
                    process = null
                    release()
                }
            }
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
        state = State.STOPPED
    }

    /** READ_LOGS works through the "log" group, which a process only gets when it starts. */
    private fun processHasLogGroup(): Boolean = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
            ?.contains(LOG_GID.toString()) == true
    }.getOrDefault(true)

    /**
     * READ_LOGS was granted while this process was running. Exiting lets the system restart the
     * bound accessibility service in a new process that has log access. Rate-limited, so a device
     * that never adds the group can't end up in a restart loop.
     */
    private fun restartForLogAccess(context: Context) {
        val prefs = context.getSharedPreferences("key_log_watcher", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_restart", 0) < MIN_RESTART_INTERVAL_MS) {
            state = State.NO_PERMISSION
            return
        }
        // commit(), not apply(): the process is killed right after.
        prefs.edit().putLong("last_restart", now).commit()
        Log.i(TAG, "Restarting to pick up log access")
        AndroidProcess.killProcess(AndroidProcess.myPid())
    }

    private fun onLine(context: Context, line: String) {
        val match = KEY_LINE.find(line) ?: return
        val command = when (match.groupValues[1].toInt()) {
            24 -> TvLink.Command.VOLUME_UP
            25 -> TvLink.Command.VOLUME_DOWN
            164 -> TvLink.Command.MUTE
            else -> return
        }
        if (TvAddress.load(context) == null) return

        when (match.groupValues[2].toInt()) {
            ACTION_DOWN -> if (held != command) press(context, command)
            ACTION_UP -> release()
        }
    }

    private fun press(context: Context, command: TvLink.Command) {
        release()
        held = command
        TvLink.send(context, command)
        if (command == TvLink.Command.MUTE) return
        repeating = repeater.scheduleWithFixedDelay(
            { TvLink.send(context, command) },
            REPEAT_DELAY_MS,
            REPEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun release() {
        repeating?.cancel(false)
        repeating = null
        held = null
    }
}
