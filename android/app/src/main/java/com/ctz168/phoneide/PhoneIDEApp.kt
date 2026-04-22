package com.ctz168.phoneide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class PhoneIDEApp : Application() {

    companion object {
        const val TAG = "PhoneIDE"
        const val SERVER_PORT = 12345
        const val SERVER_URL = "http://127.0.0.1:12345"
        const val NOTIFICATION_CHANNEL_ID = "phoneide_server"
        const val NOTIFICATION_ID = 1
        const val IDE_MSG_CHANNEL_ID = "phoneide_messages"
        const val IDE_MSG_NOTIFICATION_ID_BASE = 1000

        const val PREF_NAME = "phoneide_prefs"
        const val PREF_SETUP_COMPLETE = "setup_complete"
        const val PREF_SERVER_STOPPED_BY_USER = "server_stopped_by_user"
        const val PREF_BATTERY_OPT_ASKED = "battery_opt_asked"

        // Version (synced with build.gradle)
        const val VERSION_NAME = "3.1.6"
        const val VERSION_CODE = 30106

        /** Last crash info saved by global exception handler. */
        var lastCrashInfo: String? = null
            private set
    }

    // Shared instances
    lateinit var processManager: ProcessManager
        private set
    lateinit var bootstrapManager: BootstrapManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install global crash handler to capture and log uncaught exceptions
        installCrashHandler()

        createNotificationChannel()

        // Initialize managers with direct paths (matching stableclaw pattern)
        val filesDir = filesDir.absolutePath
        val nativeLibDir = applicationInfo.nativeLibraryDir
        processManager = ProcessManager(filesDir, nativeLibDir)
        bootstrapManager = BootstrapManager(this, filesDir, nativeLibDir)

        // Ensure directories and resolv.conf exist on every start
        Thread {
            try { processManager.initialize() } catch (_: Exception) {}
            try { bootstrapManager.writeResolvConf() } catch (_: Exception) {}
        }.start()
    }

    /**
     * Global uncaught exception handler.
     * Writes crash trace to filesDir/crash.log so it can be retrieved
     * and displayed on next launch for debugging.
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            sw.appendLine("=== PhoneIDE Crash ===")
            sw.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            sw.appendLine("Thread: ${thread.name}")
            sw.appendLine("Version: $VERSION_NAME ($VERSION_CODE)")
            sw.appendLine()
            sw.appendLine(throwable.stackTraceToString())

            val crashText = sw.toString()
            lastCrashInfo = crashText
            Log.e(TAG, "Uncaught exception:\n$crashText")

            // Persist to file
            try {
                val crashFile = File(filesDir, "crash.log")
                crashFile.writeText(crashText)
            } catch (_: Exception) {}

            // Let the system default handler show the "app stopped" dialog
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Server foreground service channel (low importance, silent)
            val serverChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "IDE Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PhoneIDE background server"
                setShowBadge(false)
            }
            manager.createNotificationChannel(serverChannel)

            // IDE message channel (high importance, pops up, with sound)
            val msgChannel = NotificationChannel(
                IDE_MSG_CHANNEL_ID,
                "IDE Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications from the IDE (errors, task completion, etc.)"
                setShowBadge(true)
                enableVibration(true)
                setBypassDnd(false)
            }
            manager.createNotificationChannel(msgChannel)
        }
    }

    fun isSetupComplete(): Boolean {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SETUP_COMPLETE, false)
    }

    fun setSetupComplete(complete: Boolean) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_SETUP_COMPLETE, complete).apply()
    }

    /**
     * Track whether the user manually stopped the server.
     * This prevents onCreate() from auto-starting the service after
     * the Activity is recreated (e.g., by system memory pressure).
     */
    fun isServerStoppedByUser(): Boolean {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SERVER_STOPPED_BY_USER, false)
    }

    fun setServerStoppedByUser(stopped: Boolean) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_SERVER_STOPPED_BY_USER, stopped).apply()
    }

    fun isBatteryOptAsked(): Boolean {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_BATTERY_OPT_ASKED, false)
    }

    fun setBatteryOptAsked(asked: Boolean) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_BATTERY_OPT_ASKED, asked).apply()
    }

    /** Read and clear the persisted crash log. */
    fun readAndClearCrashLog(): String? {
        val crashFile = File(filesDir, "crash.log")
        return if (crashFile.exists()) {
            val text = crashFile.readText()
            crashFile.delete()
            text
        } else null
    }

    fun getIdeDir(): String = filesDir.absolutePath + "/phoneide"
    fun getRootfsDir(): String = filesDir.absolutePath + "/rootfs/ubuntu"

    lateinit var instance: PhoneIDEApp
        private set
}
