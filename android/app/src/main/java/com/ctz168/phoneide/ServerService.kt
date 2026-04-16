package com.ctz168.phoneide

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ServerService - Foreground service running Flask server inside proot.
 * Based on stableclaw_android's GatewayService pattern.
 */
class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        const val ACTION_START = "com.phoneide.action.START_SERVER"
        const val ACTION_STOP = "com.phoneide.action.STOP_SERVER"
        const val ACTION_RESTART = "com.phoneide.action.RESTART_SERVER"
        const val CHANNEL_ID = "phoneide_server"
        const val NOTIFICATION_ID = 1

        @Volatile var isRunning = false
            private set
        private var instance: ServerService? = null
        private val mainHandler = Handler(Looper.getMainLooper())
        private val logListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

        fun isProcessAlive(): Boolean {
            val inst = instance ?: return false
            if (!isRunning) return false
            return inst.serverProcess?.isAlive == true
        }

        fun addLogListener(listener: (String) -> Unit) { logListeners.add(listener) }
        fun removeLogListener(listener: (String) -> Unit) { logListeners.remove(listener) }

        fun start(context: Context) {
            try {
                val intent = Intent(context, ServerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            context.stopService(intent)
        }
    }

    private var serverProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var restartCount = 0
    private val maxRestarts = 5
    private var startTime: Long = 0
    private val logBuffer = StringBuffer()
    private val logLock = Object()
    private var processManager: ProcessManager? = null
    @Volatile private var stopping = false

    var onServerStatusChanged: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Use shared instance from Application (matching stableclaw pattern)
        processManager = (applicationContext as? PhoneIDEApp)?.processManager
            ?: ProcessManager(applicationContext.filesDir.absolutePath, applicationContext.applicationInfo.nativeLibraryDir)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        when (intent?.action) {
            ACTION_STOP -> { stopServer(); return START_NOT_STICKY }
            ACTION_RESTART -> { restartServer(); return START_STICKY }
            else -> { }
        }
        if (isRunning) {
            updateNotificationRunning()
            return START_STICKY
        }
        stopping = false
        acquireWakeLock()
        startServerInternal()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        stopServerInternal()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun isPortInUse(port: Int = PhoneIDEApp.SERVER_PORT): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                true
            }
        } catch (_: Exception) { false }
    }

    private fun startServerInternal() {
        isRunning = true
        instance = this
        startTime = System.currentTimeMillis()

        val pm = processManager ?: return

        Thread {
            try {
                // Setup directories (Android may clear filesDir during update)
                val bm = BootstrapManager(applicationContext, pm.filesDir, pm.nativeLibDir)
                try { bm.setupDirectories() } catch (e: Exception) {
                    emitLog("[WARN] setupDirectories failed: ${e.message}")
                }
                try { bm.writeResolvConf() } catch (_: Exception) {}

                // Always re-copy IDE files from assets to host dir on every start
                // This ensures new versions of CSS/JS/vendor files are deployed
                try {
                    copyIDEFromAssets(pm.filesDir)
                    bm.setupIDEFiles()
                    emitLog("[INFO] IDE files synced from assets")
                } catch (e: Exception) {
                    emitLog("[WARN] IDE file sync: ${e.message}")
                }

                // Ensure /root/phoneide is a git repo for code updates
                try {
                    ensureGitRepo(pm)
                } catch (e: Exception) {
                    emitLog("[WARN] Git init: ${e.message}")
                }

                if (stopping) return@Thread

                // Check if port already in use
                if (isPortInUse()) {
                    emitLog("[INFO] Server already running on port ${PhoneIDEApp.SERVER_PORT}")
                    updateNotificationRunning()
                    startWatchdog()
                    return@Thread
                }

                // Kill any existing process on the port
                try {
                    pm.runInProotSync(
                        "fuser -k ${PhoneIDEApp.SERVER_PORT}/tcp 2>/dev/null; killall python3 2>/dev/null; true",
                        15
                    )
                } catch (_: Exception) {}

                if (stopping) return@Thread

                emitLog("[INFO] Starting Flask server via proot...")
                updateNotificationRunning()

                val command = "cd /root/phoneide && PHONEIDE_VERSION=${PhoneIDEApp.VERSION_NAME} python3 server.py 2>&1"
                serverProcess = pm.startProotProcess(command)

                if (serverProcess == null) {
                    emitLog("[ERROR] Failed to start proot process")
                    isRunning = false
                    onServerStatusChanged?.invoke(false)
                    return@Thread
                }

                emitLog("[INFO] Server process spawned")
                onServerStatusChanged?.invoke(true)

                // Read stdout
                val proc = serverProcess!!
                val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
                Thread {
                    try {
                        var line: String?
                        while (stdoutReader.readLine().also { line = it } != null) {
                            emitLog(line ?: "")
                        }
                    } catch (_: Exception) {}
                }.start()

                // Read stderr
                val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))
                Thread {
                    try {
                        var line: String?
                        while (stderrReader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (!l.contains("proot warning") && !l.contains("can't sanitize")) {
                                emitLog("[ERR] $l")
                            }
                        }
                    } catch (_: Exception) {}
                }.start()

                val exitCode = proc.waitFor()
                val uptimeMs = System.currentTimeMillis() - startTime
                emitLog("[INFO] Server exited with code $exitCode (uptime: ${uptimeMs / 1000}s)")

                if (stopping) return@Thread

                // Reset restart count if ran > 60s
                if (uptimeMs > 60_000) restartCount = 0

                isRunning = false
                onServerStatusChanged?.invoke(false)

                if (restartCount < maxRestarts) {
                    restartCount++
                    val delayMs = minOf(2000L * (1 shl (restartCount - 1)), 16000L)
                    emitLog("[INFO] Auto-restart in ${delayMs / 1000}s (attempt $restartCount/$maxRestarts)")
                    updateNotification("Restarting in ${delayMs / 1000}s...")
                    Thread.sleep(delayMs)
                    if (!stopping) {
                        startTime = System.currentTimeMillis()
                        startServerInternal()
                    }
                } else {
                    emitLog("[WARN] Max restarts reached. Server stopped.")
                    updateNotification("Server stopped")
                }
            } catch (e: Exception) {
                if (!stopping) {
                    emitLog("[ERROR] Server error: ${e.message}")
                    isRunning = false
                    onServerStatusChanged?.invoke(false)
                    updateNotification("Server error")
                }
            }
        }.start()
    }

    private fun stopServerInternal() {
        stopping = true
        restartCount = maxRestarts // Prevent auto-restart
        val proc = serverProcess
        serverProcess = null

        proc?.let {
            Thread({
                try {
                    it.destroy() // SIGTERM first
                    if (!it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        it.destroyForcibly()
                    }
                } catch (_: Exception) {
                    try { it.destroyForcibly() } catch (_: Exception) {}
                }
            }, "server-stop").apply { isDaemon = true; start() }
        }

        isRunning = false
        onServerStatusChanged?.invoke(false)
        emitLog("Server stopped by user")
    }

    private fun stopServer() { stopServerInternal() }

    private fun restartServer() {
        stopServerInternal()
        Thread {
            Thread.sleep(1000)
            if (!stopping) {
                stopping = false
                startServerInternal()
            }
        }.start()
    }

    private fun startWatchdog() {
        Thread {
            try {
                Thread.sleep(45_000)
                while (isRunning && !stopping) {
                    if (!isPortInUse()) {
                        emitLog("[WARN] Watchdog: port not responding")
                    }
                    Thread.sleep(15_000)
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
    }

    private fun emitLog(message: String) {
        synchronized(logLock) {
            logBuffer.append(message).append("\n")
            if (logBuffer.length > 1_000_000) logBuffer.delete(0, 500_000)
        }
        mainHandler.post {
            logListeners.forEach { try { it(message) } catch (_: Exception) {} }
        }
    }

    fun getLogs(): String {
        synchronized(logLock) { return logBuffer.toString() }
    }

    fun getRecentLogs(maxLines: Int = 200): String {
        synchronized(logLock) {
            return logBuffer.toString().lines().takeLast(maxLines).joinToString("\n")
        }
    }

    // ================================================================
    // IDE File Sync (copy from APK assets to host dir + rootfs)
    // ================================================================

    private fun copyIDEFromAssets(filesDir: String) {
        val hostIdeDir = "$filesDir/phoneide"
        java.io.File(hostIdeDir).mkdirs()
        // Clean up stale .pyc cache before copying new assets
        cleanPycache(java.io.File(hostIdeDir))
        copyAssetDirRecursive("ide", java.io.File(hostIdeDir))
    }

    private fun cleanPycache(dir: java.io.File) {
        if (!dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name == "__pycache__") {
                    child.deleteRecursively()
                } else {
                    cleanPycache(child)
                }
            }
        }
    }

    private fun copyAssetDirRecursive(assetPath: String, destDir: java.io.File) {
        val files = assets.list(assetPath) ?: return
        for (file in files) {
            val srcPath = "$assetPath/$file"
            val destFile = java.io.File(destDir, file)
            try {
                val inputStream = assets.open(srcPath)
                java.io.FileOutputStream(destFile).use { out ->
                    inputStream.use { inp ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (_: Exception) {
                // Likely a directory — recurse
                destFile.mkdirs()
                copyAssetDirRecursive(srcPath, destFile)
            }
        }
    }

    /**
     * Ensure /root/phoneide is a git repo so code updates (git pull) work.
     * Only initializes git + remote; does NOT auto-checkout to avoid
     * overwriting asset files before the user explicitly requests an update.
     */
    private fun ensureGitRepo(pm: ProcessManager) {
        val check = pm.runInProotSync(
            "cd /root/phoneide && git rev-parse --is-inside-work-tree 2>/dev/null", 10
        )
        if (check.contains("true")) {
            emitLog("[INFO] Git repo already initialized")
            return
        }
        // Not a git repo - initialize (without checking out remote files)
        // Remote points to ctz168/ide for code updates
        pm.runInProotSync(
            "cd /root/phoneide && " +
            "git init && " +
            "git remote add origin https://github.com/ctz168/ide.git 2>/dev/null; " +
            "git add -A && " +
            "git commit -m 'initial from assets' 2>/dev/null || true",
            30
        )
        emitLog("[INFO] Git repo initialized for code updates")
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneIDE::ServerWakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        } catch (e: SecurityException) {
            Log.w(TAG, "WAKE_LOCK permission not granted, skipping wake lock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "PhoneIDE Server", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the IDE server running"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ServerService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("PhoneIDE Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)

        if (isRunning && startTime > 0) {
            builder.setWhen(startTime)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun updateNotificationRunning() {
        val elapsed = System.currentTimeMillis() - startTime
        val sec = elapsed / 1000
        val min = sec / 60
        val hr = min / 60
        val upStr = when {
            hr > 0 -> "${hr}h ${min % 60}m"
            min > 0 -> "${min}m"
            else -> "${sec}s"
        }
        updateNotification("Running on port ${PhoneIDEApp.SERVER_PORT} - $upStr")
    }
}
