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
import java.io.InputStreamReader

class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        const val ACTION_START = "com.phoneide.action.START_SERVER"
        const val ACTION_STOP = "com.phoneide.action.STOP_SERVER"
        const val ACTION_RESTART = "com.phoneide.action.RESTART_SERVER"
        const val ACTION_GET_STATUS = "com.phoneide.action.GET_STATUS"
        const val EXTRA_STATUS = "server_status"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverProcess: Process? = null
    private var isRunning = false
    private var restartCount = 0
    private val maxRestarts = 5
    private val logBuffer = StringBuffer()
    private val logLock = Object()

    private lateinit var processManager: ProcessManager

    // Callback for status updates
    var onServerStatusChanged: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        processManager = ProcessManager(applicationContext)
        processManager.initialize()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            ACTION_RESTART -> restartServer()
            ACTION_GET_STATUS -> {
                // Broadcast status back
                // Could use LocalBroadcastManager or just update internal state
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, PhoneIDEApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PhoneIDE 运行中")
            .setContentText("IDE 服务器端口: ${PhoneIDEApp.SERVER_PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(PhoneIDEApp.NOTIFICATION_ID, notification)
    }

    @SuppressLint("SdCardPath")
    private fun startServer() {
        if (isRunning) {
            Log.d(TAG, "Server already running")
            return
        }

        Log.d(TAG, "Starting PhoneIDE server via proot...")
        scope.launch {
            try {
                // Kill any existing server on the same port
                killExistingServer()

                // Start Flask server inside proot
                val command = "cd /root/phoneide && python3 server.py 2>&1"
                serverProcess = processManager.startProotProcess(command)

                if (serverProcess == null) {
                    appendLog("ERROR: Failed to start proot process\n")
                    appendLog("Trying direct execution without proot...\n")
                    // Fallback: try running python3 directly from rootfs
                    try {
                        val rootfsDir = processManager.getRootfsDir()
                        val pb = ProcessBuilder(
                            "$rootfsDir/usr/bin/python3",
                            "$rootfsDir/root/phoneide/server.py"
                        )
                        pb.environment()["LD_LIBRARY_PATH"] = "$rootfsDir/usr/lib/aarch64-linux-gnu:$rootfsDir/lib/aarch64-linux-gnu"
                        pb.redirectErrorStream(true)
                        serverProcess = pb.start()
                        appendLog("Direct python3 started\n")
                    } catch (e2: Exception) {
                        appendLog("Direct execution also failed: ${e2.message}\n")
                        return@launch
                    }
                }

                isRunning = true
                restartCount = 0
                onServerStatusChanged?.invoke(true)
                appendLog("Server process started (PID unknown in proot)\n")

                // Read server output for logging
                val reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    appendLog(line + "\n")
                }

                // Process ended
                val exitCode = serverProcess!!.exitValue()
                appendLog("Server process exited with code $exitCode\n")
                isRunning = false
                onServerStatusChanged?.invoke(false)

                // Auto-restart with exponential backoff
                if (restartCount < maxRestarts) {
                    restartCount++
                    val delayMs = minOf(2000L * (1 shl (restartCount - 1)), 16000L)
                    appendLog("Auto-restarting in ${delayMs}ms (attempt $restartCount/$maxRestarts)...\n")
                    delay(delayMs)
                    startServer()
                } else {
                    appendLog("Max restart attempts reached. Stopping.\n")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Server coroutine cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                appendLog("ERROR: ${e.message}\n")
                isRunning = false
                onServerStatusChanged?.invoke(false)
            }
        }
    }

    private fun stopServer() {
        Log.d(TAG, "Stopping server...")
        isRunning = false
        try {
            serverProcess?.destroy()
            // Wait for graceful shutdown
            Thread.sleep(1000)
            if (serverProcess?.isAlive == true) {
                serverProcess?.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server: ${e.message}")
        }
        serverProcess = null
        onServerStatusChanged?.invoke(false)
        appendLog("Server stopped\n")
    }

    private fun restartServer() {
        Log.d(TAG, "Restarting server...")
        stopServer()
        scope.launch {
            delay(1000)
            startServer()
        }
    }

    fun isServerRunning(): Boolean = isRunning

    fun getLogs(): String {
        synchronized(logLock) {
            return logBuffer.toString()
        }
    }

    fun getRecentLogs(maxLines: Int = 200): String {
        synchronized(logLock) {
            val lines = logBuffer.toString().lines()
            return lines.takeLast(maxLines).joinToString("\n")
        }
    }

    private fun appendLog(message: String) {
        synchronized(logLock) {
            logBuffer.append(message)
            // Keep buffer manageable (max 1MB)
            if (logBuffer.length > 1_000_000) {
                logBuffer.delete(0, 500_000)
            }
        }
    }

    private fun killExistingServer() {
        try {
            // Kill any process on our port inside proot
            val result = processManager.runInProot(
                "fuser -k ${PhoneIDEApp.SERVER_PORT}/tcp 2>/dev/null; killall python3 2>/dev/null; true",
                timeoutMs = 10_000
            )
            Log.d(TAG, "Kill existing: ${result.stdout.take(200)}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kill existing server: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        scope.cancel()
        Log.d(TAG, "ServerService destroyed")
    }
}
