package com.ctz168.phoneide

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.content.res.Configuration
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val STATUS_POLL_INTERVAL = 15_000L
        private const val HEALTH_POLL_INTERVAL = 2_000L
    }

    /** Get the actual installed APK version from PackageManager (not hardcoded constant) */
    private fun installedVersionName(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: PhoneIDEApp.VERSION_NAME
        } catch (_: Exception) {
            PhoneIDEApp.VERSION_NAME
        }
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var progressBar: View
    private lateinit var statusText: View
    private lateinit var errorOverlay: View
    private lateinit var errorText: View
    private lateinit var retryButton: View

    // Server management bar views
    private lateinit var statusIndicator: View
    private lateinit var statusLabel: TextView
    private lateinit var btnStart: View
    private lateinit var btnStop: View
    private lateinit var btnUpdate: View

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusPollJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // Longer timeouts for update operations
    private val updateHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Fullscreen immersive mode
        setupImmersiveMode()

        // Initialize views
        initViews()

        // Show crash log from previous session if any
        val app = application as PhoneIDEApp
        app.readAndClearCrashLog()?.let { crashLog ->
            MaterialAlertDialogBuilder(this)
                .setTitle("上次崩溃日志")
                .setMessage("上次启动时 PhoneIDE 发生了异常崩溃，日志如下：\n\n${crashLog}")
                .setPositiveButton("确定", null)
                .setNeutralButton("重置配置") { _, _ ->
                    app.setSetupComplete(false)
                    recreate()
                }
                .show()
        }

        // Request runtime permissions
        requestRuntimePermissions()

        // Check setup status
        if (!app.isSetupComplete()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Start server service ONLY if user hasn't manually stopped it
        if (!app.isServerStoppedByUser()) {
            startServerService()

            // Setup WebView
            setupWebView()

            // Connect to server
            connectToServer()
        } else {
            // User previously stopped the server - show stopped state directly
            setupWebView()
            updateStatusIndicator(false)
            statusLabel.text = getString(R.string.server_status_stopped)
            showError("服务已停止，点击 ▶ 启动按钮重新启动")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-apply immersive mode flags after rotation
        setupImmersiveMode()
    }

    private fun setupImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Keep status bar transparent but handle insets via layout fitsSystemWindows
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Don't use setDecorFitsSystemWindows(false) - it causes bottom bar
            // to be hidden behind system navigation. Let the layout handle it.
            window.statusBarColor = android.graphics.Color.parseColor("#1A1814")
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webview)
        loadingOverlay = findViewById(R.id.loading_overlay)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        errorOverlay = findViewById(R.id.error_overlay)
        errorText = findViewById(R.id.error_text)
        retryButton = findViewById(R.id.retry_button)

        // Server management bar views
        statusIndicator = findViewById(R.id.status_indicator)
        statusLabel = findViewById(R.id.status_label)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnUpdate = findViewById(R.id.btn_update)

        retryButton.setOnClickListener { connectToServer() }

        // Server management button handlers
        btnStart.setOnClickListener { handleStart() }
        btnStop.setOnClickListener { handleStop() }
        btnUpdate.setOnClickListener { handleCodeUpdate() }

        // Terminal button
        val btnTerminal = findViewById<View>(R.id.btn_terminal)
        btnTerminal?.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Text zoom for mobile readability
            textZoom = 100
        }

        // Enable WebView debugging for development
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url.toString()
                // Allow internal URLs
                if (url.startsWith(PhoneIDEApp.SERVER_URL) ||
                    url.startsWith("http://localhost") ||
                    url.startsWith("http://127.0.0.1")) {
                    return false
                }
                // Open external URLs in browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open URL: $url", e)
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only show error for main frame
                if (request?.isForMainFrame == true) {
                    showError("加载失败: ${error?.description}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d("WebViewConsole", "${msg?.message()} [${msg?.sourceId()}:${msg?.lineNumber()}]")
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // Could update progress bar here
            }
        }

        // Add JavascriptInterface for native bridge
        webView.addJavascriptInterface(NativeBridge(), "AndroidBridge")
        webView.addJavascriptInterface(UpdateBridge(), "UpdateBridge")
    }

    // ========================
    // Native Bridge
    // ========================

    private var ideMsgNotificationId = PhoneIDEApp.IDE_MSG_NOTIFICATION_ID_BASE
    private val ideNotificationTimer = mutableMapOf<Int, kotlinx.coroutines.Job>()

    inner class NativeBridge {
        @JavascriptInterface
        fun showToast(msg: String) {
            scope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        /**
         * Show an Android notification from the IDE web page.
         * Called by showToast() in app.js when running inside the APK.
         *
         * @param title   Notification title (e.g. "PhoneIDE")
         * @param message Notification body text
         * @param type    "error" | "success" | "info" | "warning" — controls icon color
         * @param durationMs Auto-dismiss after this many ms (0 = manual dismiss)
         */
        @JavascriptInterface
        fun showNotification(title: String, message: String, type: String, durationMs: Int) {
            Log.d(TAG, "IDE notification: [$type] $title — $message")
            scope.launch {
                withContext(Dispatchers.Main) {
                    showIdeNotification(title, message, type, durationMs)
                }
            }
        }
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun showIdeNotification(title: String, message: String, type: String, durationMs: Int) {
        val notificationId = ideMsgNotificationId++

        // Icon based on type
        val iconRes = when (type) {
            "error" -> android.R.drawable.ic_dialog_alert
            "success" -> android.R.drawable.ic_menu_agenda
            "warning" -> android.R.drawable.ic_dialog_info
            else -> android.R.drawable.ic_dialog_info
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, PhoneIDEApp.IDE_MSG_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_HIGH)
        }

        val notification = builder
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)

        // Auto-dismiss after duration
        if (durationMs > 0) {
            val existingTimer = ideNotificationTimer[notificationId]
            existingTimer?.cancel()
            val timer = scope.launch {
                delay(durationMs.toLong())
                manager.cancel(notificationId)
                ideNotificationTimer.remove(notificationId)
            }
            ideNotificationTimer[notificationId] = timer
        }
    }

    // ========================
    // APK Auto-Update via JS Bridge
    // ========================

    inner class UpdateBridge {
        @JavascriptInterface
        fun downloadAndInstallApk(apkUrl: String, version: String) {
            Log.d(TAG, "Download APK requested: $apkUrl (v$version)")
            scope.launch {
                downloadAndInstallApkInternal(apkUrl, version)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun downloadAndInstallApkInternal(apkUrl: String, version: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading Update v$version")
            .setMessage("Downloading APK, please wait...")
            .setCancelable(false)
            .setView(TextView(this).apply {
                text = "Preparing download..."
                setPadding(48, 0, 48, 0)
                textSize = 14f
            })
            .create()

        withContext(Dispatchers.Main) {
            progressDialog.show()
            progressDialog.window?.setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        try {
            val outputFile = File(externalCacheDir, "phoneide-${version}.apk")

            val success = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(apkUrl).build()
                    val response = updateHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "APK download failed: HTTP ${response.code}")
                        false
                    } else {
                        val body = response.body ?: return@withContext false
                        val totalBytes = body.contentLength()
                        val inputStream = body.byteStream()

                        var bytesRead = 0L
                        val buffer = ByteArray(8192)
                        val outputStream = FileOutputStream(outputFile)

                        inputStream.use { input ->
                            outputStream.use { output ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    bytesRead += read

                                    if (bytesRead % (500 * 1024) < 8192L) {
                                        val progress = if (totalBytes > 0) {
                                            "${(bytesRead * 100 / totalBytes)}% (${bytesRead / 1024 / 1024}MB / ${totalBytes / 1024 / 1024}MB)"
                                        } else {
                                            "${bytesRead / 1024 / 1024}MB downloaded"
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (progressDialog.isShowing) {
                                                progressDialog.findViewById<TextView>(android.R.id.message)?.text = progress
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Log.d(TAG, "APK downloaded: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                        true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "APK download error", e)
                    false
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
            }

            if (!success) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Download Failed")
                    .setMessage("Failed to download the update. Please check your network connection and try again.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            installApk(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "APK update failed", e)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Update Failed")
                    .setMessage("Error: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    @SuppressLint("QueryablePermissions")
    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this@MainActivity,
                "${packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if can resolve
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback: try without FileProvider
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Install Failed")
                .setMessage("Could not install the update: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun connectToServer() {
        showLoading("正在连接服务器...")
        scope.launch {
            var attempts = 0
            val maxAttempts = 30

            while (attempts < maxAttempts && isActive) {
                try {
                    val connected = withContext(Dispatchers.IO) {
                        checkServerConnection()
                    }
                    if (connected) {
                        hideAllOverlays()
                        loadIDE()
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Connection attempt ${attempts + 1} failed: ${e.message}")
                }
                attempts++
                delay(1000)
            }

            // If we get here, server didn't respond
            showError("无法连接到服务器。请检查 PhoneIDE 服务是否正在运行。\n\n提示：可以在终端中手动运行:\ncd ~/phoneide && python3 server.py")
        }
    }

    private fun checkServerConnection(): Boolean {
        try {
            val request = Request.Builder()
                .url("${PhoneIDEApp.SERVER_URL}/api/health")
                .build()
            val response = httpClient.newCall(request).execute()
            return response.isSuccessful
        } catch (e: SocketTimeoutException) {
            return false
        } catch (e: IOException) {
            return false
        }
    }

    private fun loadIDE() {
        webView.loadUrl(PhoneIDEApp.SERVER_URL)
    }

    private fun startServerService() {
        try {
            ServerService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server service", e)
            showError("启动服务失败: ${e.message}")
        }
    }

    private fun showLoading(message: String) {
        loadingOverlay.visibility = View.VISIBLE
        errorOverlay.visibility = View.GONE
        (statusText as android.widget.TextView).text = message
    }

    private fun showError(message: String) {
        loadingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.VISIBLE
        (errorText as android.widget.TextView).text = message
    }

    private fun hideAllOverlays() {
        loadingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.GONE
    }

    // ========================
    // Server Status Polling
    // ========================

    private fun startStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = scope.launch {
            while (isActive) {
                pollServerStatus()
                delay(STATUS_POLL_INTERVAL)
            }
        }
        // Also poll immediately
        scope.launch { pollServerStatus() }
    }

    private fun stopStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = null
    }

    private suspend fun pollServerStatus() {
        val isUp = withContext(Dispatchers.IO) {
            checkServerConnection()
        }
        updateStatusIndicator(isUp)
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatusIndicator(isRunning: Boolean) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isRunning) {
                setColor(Color.parseColor("#A8C49A")) // Claude Code sage green
            } else {
                setColor(Color.parseColor("#D46A6A")) // Claude Code warm red
            }
        }
        statusIndicator.background = drawable
        if (isRunning) {
            statusLabel.text = getString(R.string.server_status_running)
            statusLabel.setTextColor(Color.parseColor("#A8C49A"))
        } else {
            statusLabel.text = getString(R.string.server_status_stopped)
            statusLabel.setTextColor(Color.parseColor("#D46A6A"))
        }
    }

    // ========================
    // Start Button Handler
    // ========================

    private fun handleStart() {
        btnStart.isEnabled = false
        scope.launch {
            try {
                updateStatusIndicator(false)
                statusLabel.text = getString(R.string.server_starting)

                // Clear the "stopped by user" flag so onCreate won't skip startup
                (application as PhoneIDEApp).setServerStoppedByUser(false)

                // Start the server service
                startServerService()

                // Poll health every 2 seconds until server responds
                showLoading(getString(R.string.server_starting))
                var attempts = 0
                val maxAttempts = 30
                while (attempts < maxAttempts && isActive) {
                    delay(HEALTH_POLL_INTERVAL)
                    val isUp = withContext(Dispatchers.IO) {
                        checkServerConnection()
                    }
                    if (isUp) {
                        break
                    }
                    attempts++
                }

                hideAllOverlays()
                loadIDE()
                updateStatusIndicator(true)
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                hideAllOverlays()
                updateStatusIndicator(false)
                Toast.makeText(this@MainActivity, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                btnStart.isEnabled = true
            }
        }
    }

    // ========================
    // Stop Button Handler
    // ========================

    private fun handleStop() {
        btnStop.isEnabled = false
        scope.launch {
            try {
                updateStatusIndicator(false)
                statusLabel.text = getString(R.string.server_stopping)

                // Persist that user manually stopped the server
                // This prevents onCreate() from auto-starting it again
                (application as PhoneIDEApp).setServerStoppedByUser(true)

                // Stop status polling while stopping
                stopStatusPolling()

                // Try graceful shutdown via HTTP first
                withContext(Dispatchers.IO) {
                    postToServer("/api/server/stop")
                }

                // Stop the Android service (sends ACTION_STOP + stops service)
                stopServerService()

                // Kill any lingering python3/proot processes via ProcessManager
                withContext(Dispatchers.IO) {
                    try {
                        val pm = ProcessManager(this@MainActivity)
                        pm.runInProotSync(
                            "killall python3 2>/dev/null; fuser -k ${PhoneIDEApp.SERVER_PORT}/tcp 2>/dev/null; true",
                            10
                        )
                    } catch (_: Exception) {}
                }

                // Wait briefly for process to die
                delay(1500)

                // Verify server is actually down
                var attempts = 0
                while (attempts < 15 && isActive) {
                    val isUp = withContext(Dispatchers.IO) {
                        checkServerConnection()
                    }
                    if (!isUp) {
                        break
                    }
                    attempts++
                    delay(500)
                }

                updateStatusIndicator(false)
                statusLabel.text = getString(R.string.server_status_stopped)
                webView.loadUrl("about:blank")
                showError("服务已停止")
            } catch (e: Exception) {
                Log.e(TAG, "Stop failed", e)
                updateStatusIndicator(false)
                statusLabel.text = getString(R.string.server_status_stopped)
            } finally {
                btnStop.isEnabled = true
            }
        }
    }

    // ========================
    // Logs Button Handler (SSE Stream)
    // ========================

    private fun handleShowLogs() {
        // Create dialog with monospace TextView inside ScrollView
        val logTextView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.parseColor("#F5F0EB"))
            setBackgroundColor(Color.parseColor("#1A1814"))
            setPadding(16, 16, 16, 16)
            text = "Loading logs...\n"
        }

        val scrollView = ScrollView(this).apply {
            addView(logTextView)
            isVerticalScrollBarEnabled = true
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.server_logs))
            .setView(scrollView as android.view.View)
            .setPositiveButton("Close", null)
            .setCancelable(true)
            .create()

        dialog.show()

        // Make dialog larger
        dialog.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Connect to SSE log stream in background
        scope.launch(Dispatchers.IO) {
            try {
                val logHttpClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS) // Long timeout for SSE stream
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("${PhoneIDEApp.SERVER_URL}/api/server/logs/stream")
                    .build()
                val response = logHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        logTextView.text = "Failed to load logs (HTTP ${response.code})"
                    }
                    return@launch
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null && isActive) {
                    val logLine = line ?: continue
                    // Parse SSE data: extract JSON text field
                    val displayText = if (logLine.startsWith("data: ")) {
                        try {
                            val json = org.json.JSONObject(logLine.removePrefix("data: "))
                            val time = json.optString("time", "")
                            val text = json.optString("text", "")
                            if (time.isNotEmpty()) "[$time] $text" else text
                        } catch (_: Exception) {
                            logLine.removePrefix("data: ")
                        }
                    } else {
                        logLine
                    }
                    if (displayText.isBlank()) continue
                    withContext(Dispatchers.Main) {
                        if (dialog.isShowing) {
                            logTextView.append("$displayText\n")
                            // Auto-scroll to bottom
                            scrollView.post {
                                scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                            }
                        }
                    }
                }
                reader.close()
            } catch (e: Exception) {
                Log.e(TAG, "Log stream error", e)
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        logTextView.append("\n--- Stream ended: ${e.message} ---\n")
                    }
                }
            }
        }
    }

    // ========================
    // Update Button Handler (check GitHub Releases for APK update)
    // ========================

    @SuppressLint("SetTextI18n")
    private fun handleCodeUpdate() {
        btnUpdate.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "正在检查APK更新...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Query GitHub Releases API for ctz168/phoneide
                val releaseInfo = withContext(Dispatchers.IO) {
                    try {
                        val request = Request.Builder()
                            .url("https://api.github.com/repos/ctz168/phoneide/releases/latest")
                            .header("Accept", "application/vnd.github+json")
                            .build()
                        val response = updateHttpClient.newCall(request).execute()
                        val body = response.body?.string()
                        response.close()
                        if (!body.isNullOrEmpty()) {
                            parseReleaseResponse(body)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Release check failed", e)
                        null
                    }
                }

                if (releaseInfo == null) {
                    withContext(Dispatchers.Main) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("检查更新失败")
                            .setMessage("无法连接 GitHub API，请检查网络后重试。\n\n提示：需要能访问 api.github.com")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                    return@launch
                }

                val latestVersion = releaseInfo.first
                val releaseBody = releaseInfo.second
                val apkUrl = releaseInfo.third

                if (apkUrl == null) {
                    // No APK in the release
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("没有可用的更新")
                        .setMessage("最新版本: $latestVersion\n但该版本未包含 APK 安装包。")
                        .setPositiveButton("确定", null)
                        .show()
                    return@launch
                }

                // Compare versions: check if latest is newer than current
                if (!isNewerVersion(latestVersion, installedVersionName())) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("已是最新版本")
                        .setMessage("当前版本: v${installedVersionName()}\n最新版本: $latestVersion")
                        .setPositiveButton("确定", null)
                        .show()
                    return@launch
                }

                // Show update dialog
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("发现新版本 $latestVersion")
                    .setMessage(
                        "当前版本: v${installedVersionName()}\n" +
                        "最新版本: $latestVersion\n\n" +
                        "${releaseBody.take(500)}\n\n" +
                        "点击「立即更新」下载并安装新版本 APK。"
                    )
                    .setPositiveButton("立即更新") { _, _ ->
                        // Trigger APK download via UpdateBridge (same method used by JS bridge)
                        UpdateBridge().downloadAndInstallApk(apkUrl, latestVersion)
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "更新检查失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                btnUpdate.isEnabled = true
            }
        }
    }

    /**
     * Parse GitHub release JSON response.
     * Returns Triple(releaseTag, bodyText, apkDownloadUrl?) or null.
     */
    private fun parseReleaseResponse(jsonStr: String): Triple<String, String, String?>? {
        return try {
            val json = JSONObject(jsonStr)
            val tagName = json.optString("tag_name", "")
            val body = json.optString("body", "")
            val assets = json.getJSONArray("assets")

            // Find the release APK (prefer release APK over debug)
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.contains("release", ignoreCase = true) && name.endsWith(".apk")) {
                    apkUrl = url
                    break
                }
            }
            // Fallback to any APK if no "release" APK found
            if (apkUrl == null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val url = asset.optString("browser_download_url", "")
                    if (url.endsWith(".apk")) {
                        apkUrl = url
                        break
                    }
                }
            }

            Triple(tagName, body, apkUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse release JSON", e)
            null
        }
    }

    /**
     * Compare two version strings (e.g. "3.0.43" vs "3.0.44").
     * Returns true if remoteVersion is strictly newer than localVersion.
     */
    private fun isNewerVersion(remoteVersion: String, localVersion: String): Boolean {
        // Strip "v" prefix and "-build.XXX" suffix if present
        fun normalize(v: String): String {
            return v.removePrefix("v").split("-").firstOrNull() ?: v
        }
        val rv = normalize(remoteVersion).split(".").map { it.toIntOrNull() ?: 0 }
        val lv = normalize(localVersion).split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rv.size, lv.size)
        for (i in 0 until maxLen) {
            val r = rv.getOrElse(i) { 0 }
            val l = lv.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    // ========================
    // Code Update via Server (git pull) — kept for internal use
    // ========================

    @SuppressLint("SetTextI18n")
    private fun applyCodeUpdate(version: String) {
        btnUpdate.isEnabled = false
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在更新代码")
            .setMessage("正在停止服务并拉取最新代码...\n\n请稍候")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            try {
                // Step 1: Stop server service
                withContext(Dispatchers.Main) {
                    updateStatusIndicator(false)
                    statusLabel.text = getString(R.string.server_restarting)
                }
                withContext(Dispatchers.IO) {
                    ServerService.stop(this@MainActivity)
                    // Wait for server process to fully stop
                    Thread.sleep(2000)
                }

                // Step 2: Run git fetch + reset directly via proot (server is stopped, so no HTTP)
                val updateOutput = withContext(Dispatchers.IO) {
                    val pm = ProcessManager(this@MainActivity)
                    val output = pm.runInProotSync(
                        "cd /root/phoneide && " +
                        "git fetch origin main 2>&1 && " +
                        "git reset --hard origin/main 2>&1 && " +
                        "echo 'UPDATE_SUCCESS'",
                        120
                    )
                    output
                }

                val success = updateOutput.contains("UPDATE_SUCCESS")

                withContext(Dispatchers.Main) { progressDialog.dismiss() }

                if (success) {
                    // Step 3: Restart server service
                    withContext(Dispatchers.Main) {
                        startServerService()
                        showLoading("服务器启动中...")
                    }

                    // Step 4: Wait for server to be ready, then reload IDE
                    scope.launch {
                        var attempts = 0
                        while (attempts < 30 && isActive) {
                            delay(HEALTH_POLL_INTERVAL)
                            val isUp = withContext(Dispatchers.IO) { checkServerConnection() }
                            if (isUp) break
                            attempts++
                        }
                        hideAllOverlays()
                        loadIDE()
                        updateStatusIndicator(true)
                        Toast.makeText(this@MainActivity, "代码已更新到 $version", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // Update failed — try to start server anyway
                    withContext(Dispatchers.Main) {
                        startServerService()
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("更新失败")
                            .setMessage("git 更新出错，请检查网络后重试\n\n输出:\n$updateOutput")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                    scope.launch {
                        var attempts = 0
                        while (attempts < 15 && isActive) {
                            delay(HEALTH_POLL_INTERVAL)
                            val isUp = withContext(Dispatchers.IO) { checkServerConnection() }
                            if (isUp) break
                            attempts++
                        }
                        hideAllOverlays()
                        loadIDE()
                        updateStatusIndicator(true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update apply failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    startServerService()
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("更新失败")
                        .setMessage("错误: ${e.message}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            } finally {
                btnUpdate.isEnabled = true
            }
        }
    }

    // ========================
    // HTTP Helpers
    // ========================

    private fun postToServer(path: String, useUpdateClient: Boolean = false): Boolean {
        return try {
            val client = if (useUpdateClient) updateHttpClient else httpClient
            val request = Request.Builder()
                .url("${PhoneIDEApp.SERVER_URL}$path")
                .post(RequestBody.create(null, byteArrayOf()))
                .build()
            val response = client.newCall(request).execute()
            response.close()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed", e)
            false
        }
    }

    private fun postToServerWithResponse(path: String): String? {
        return try {
            val request = Request.Builder()
                .url("${PhoneIDEApp.SERVER_URL}$path")
                .post(RequestBody.create(null, byteArrayOf()))
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            body
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed", e)
            null
        }
    }

    // ========================
    // Menu
    // ========================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_terminal -> {
                startActivity(Intent(this, TerminalActivity::class.java))
                true
            }
            R.id.menu_refresh -> {
                webView.reload()
                true
            }
            R.id.menu_restart_server -> {
                stopServerService()
                startServerService()
                connectToServer()
                true
            }
            R.id.menu_about -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("PhoneIDE v${installedVersionName()}")
                    .setMessage("基于 proot Ubuntu 的手机端 Web IDE\n\n" +
                            "功能：代码编辑、终端、Git、LLM Agent\n" +
                            "端口：${PhoneIDEApp.SERVER_PORT}")
                    .setPositiveButton("确定", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun stopServerService() {
        val intent = Intent(this, ServerService::class.java)
        intent.action = ServerService.ACTION_STOP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Also stop the service directly to ensure onDestroy is called
        ServerService.stop(this)
    }

    // ========================
    // Runtime Permissions
    // ========================

    private fun requestRuntimePermissions() {
        val permsToRequest = mutableListOf<String>()

        // POST_NOTIFICATIONS is runtime permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permsToRequest.toTypedArray(),
                1001
            )
        }

        // Request battery optimization exemption (critical for background service survival)
        // Only ask ONCE — not on every Activity recreation (e.g. after minimize/restore)
        val app2 = application as PhoneIDEApp
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !app2.isBatteryOptAsked()) {
            app2.setBatteryOptAsked(true)
            try {
                val intent = Intent()
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        stopStatusPolling()
    }

    override fun onDestroy() {
        stopStatusPolling()
        scope.cancel()
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("退出 PhoneIDE")
                .setMessage("确定要退出吗？服务器将继续在后台运行。")
                .setPositiveButton("退出") { _, _ ->
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
