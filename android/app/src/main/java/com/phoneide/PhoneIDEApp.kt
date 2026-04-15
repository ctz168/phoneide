package com.phoneide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class PhoneIDEApp : Application() {

    companion object {
        const val TAG = "PhoneIDE"
        const val SERVER_PORT = 1239
        const val SERVER_URL = "http://127.0.0.1:1239"
        const val NOTIFICATION_CHANNEL_ID = "phoneide_server"
        const val NOTIFICATION_ID = 1

        const val PREF_NAME = "phoneide_prefs"
        const val PREF_SETUP_COMPLETE = "setup_complete"

        // Version
        const val VERSION_NAME = "3.0.0"
        const val VERSION_CODE = 3
    }

    // Shared instances
    lateinit var processManager: ProcessManager
        private set
    lateinit var bootstrapManager: BootstrapManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
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

    fun getIdeDir(): String = filesDir.absolutePath + "/phoneide"
    fun getRootfsDir(): String = filesDir.absolutePath + "/rootfs/ubuntu"

    lateinit var instance: PhoneIDEApp
        private set
}
