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
        const val SERVER_PORT = 1239
        const val SERVER_URL = "http://127.0.0.1:1239"
        const val NOTIFICATION_CHANNEL_ID = "phoneide_server"
        const val NOTIFICATION_ID = 1

        const val PREF_NAME = "phoneide_prefs"
        const val PREF_SETUP_COMPLETE = "setup_complete"

        // Version (synced with build.gradle)
        const val VERSION_NAME = "3.0.42"
        const val VERSION_CODE = 42

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
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "IDE Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PhoneIDE background server"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
