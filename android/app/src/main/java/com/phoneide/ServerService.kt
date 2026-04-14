package com.phoneide

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverProcess: Process? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            Log.d(TAG, "Server already running")
            return START_STICKY
        }

        Log.d(TAG, "Starting PhoneIDE server...")
        startServer()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, PhoneIDEApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PhoneIDE 运行中")
            .setContentText("IDE 服务器端口: ${PhoneIDEApp.SERVER_PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(PhoneIDEApp.NOTIFICATION_ID, notification)
    }

    @SuppressLint("SdCardPath")
    private fun startServer() {
        scope.launch {
            try {
                val ideDir = (application as PhoneIDEApp).getIdeDir()
                val serverScript = File(ideDir, "server.py")

                if (!serverScript.exists()) {
                    Log.e(TAG, "Server script not found: ${serverScript.absolutePath}")
                    return@launch
                }

                // Kill any existing server process on the same port
                killExistingServer()

                // Build the command to start the server
                // Use Termux's Python or system Python
                val pythonPaths = listOf(
                    "/data/data/com.termux/files/usr/bin/python3",
                    "/data/data/com.termux/files/usr/bin/python",
                    "python3",
                    "python"
                )

                var pythonBin: String? = null
                for (path in pythonPaths) {
                    if (File(path).exists()) {
                        pythonBin = path
                        break
                    }
                }

                if (pythonBin == null) {
                    Log.e(TAG, "Python not found in Termux")
                    return@launch
                }

                Log.d(TAG, "Using Python: $pythonBin")
                Log.d(TAG, "Server dir: $ideDir")

                // Start the Flask server
                val processBuilder = ProcessBuilder()
                    .command(
                        "/data/data/com.termux/files/usr/bin/env",
                        "TMPDIR=/data/data/com.termux/files/usr/tmp",
                        "HOME=/data/data/com.termux/files/home",
                        "PATH=/data/data/com.termux/files/usr/bin:/usr/bin:/bin",
                        "LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib",
                        "PREFIX=/data/data/com.termux/files/usr",
                        pythonBin,
                        serverScript.absolutePath
                    )
                    .directory(File(ideDir))
                    .redirectErrorStream(true)

                serverProcess = processBuilder.start()
                isRunning = true

                // Read server output
                val reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "Server: $line")
                }

                Log.d(TAG, "Server process ended")
                isRunning = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                isRunning = false
            }
        }
    }

    private fun killExistingServer() {
        try {
            // Kill any process listening on our port
            val killCommands = listOf(
                arrayOf("fuser", "-k", "${PhoneIDEApp.SERVER_PORT}/tcp"),
                arrayOf("killall", "python3"),
                arrayOf("killall", "python")
            )

            for (cmd in killCommands) {
                try {
                    val p = ProcessBuilder(*cmd).start()
                    p.waitFor()
                } catch (e: Exception) {
                    // Ignore - command may not exist
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kill existing server: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        serverProcess?.destroy()
        isRunning = false
        Log.d(TAG, "ServerService destroyed")
    }
}
