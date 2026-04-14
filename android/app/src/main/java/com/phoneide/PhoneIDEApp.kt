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
        const val PREF_SERVER_PID = "server_pid"

        // Internal paths
        const val ROOTFS_DIR = "ubuntu-rootfs"
        const val IDE_DIR = "phoneide"
        const val WORKSPACE_DIR = "workspace"

        // Version
        const val VERSION_NAME = "2.0.0"
        const val VERSION_CODE = 2
    }

    // Shared instances
    lateinit var processManager: ProcessManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        processManager = ProcessManager(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "IDE 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PhoneIDE 后台服务通知"
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

    fun getIdeDir(): String {
        return filesDir.absolutePath + "/" + IDE_DIR
    }

    fun getWorkspaceDir(): String {
        return filesDir.absolutePath + "/" + WORKSPACE_DIR
    }

    fun getRootfsDir(): String {
        return filesDir.absolutePath + "/" + ROOTFS_DIR
    }

    lateinit var instance: PhoneIDEApp
        private set
}
