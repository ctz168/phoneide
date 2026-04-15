package com.ctz168.phoneide

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.BufferedReader
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
    private lateinit var btnRestart: View
    private lateinit var btnLogs: View
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

        // Check setup status
        if (!(application as PhoneIDEApp).isSetupComplete()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Start server service
        startServerService()

        // Setup WebView
        setupWebView()

        // Connect to server
        connectToServer()
    }

    private fun setupImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
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
        btnRestart = findViewById(R.id.btn_restart)
        btnLogs = findViewById(R.id.btn_logs)
        btnUpdate = findViewById(R.id.btn_update)

        retryButton.setOnClickListener { connectToServer() }

        // Server management button handlers
        btnRestart.setOnClickListener { handleRestart() }
        btnLogs.setOnClickListener { handleShowLogs() }
        btnUpdate.setOnClickListener { handleCheckUpdate() }
    }

    @SuppressLint("SetJavaScriptEnabled")
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
        val intent = Intent(this, ServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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
                setColor(Color.parseColor("#3FB950")) // GitHub green
            } else {
                setColor(Color.parseColor("#F85149")) // GitHub red
            }
        }
        statusIndicator.background = drawable
        if (isRunning) {
            statusLabel.text = getString(R.string.server_status_running)
            statusLabel.setTextColor(Color.parseColor("#3FB950"))
        } else {
            statusLabel.text = getString(R.string.server_status_stopped)
            statusLabel.setTextColor(Color.parseColor("#F85149"))
        }
    }

    // ========================
    // Restart Button Handler
    // ========================

    private fun handleRestart() {
        btnRestart.isEnabled = false
        scope.launch {
            try {
                // Show restarting toast/status
                updateStatusIndicator(false)
                statusLabel.text = getString(R.string.server_restarting)

                // POST to /api/server/restart
                val success = withContext(Dispatchers.IO) {
                    postToServer("/api/server/restart")
                }

                if (!success) {
                    // Fallback: restart Android service directly
                    withContext(Dispatchers.Main) {
                        stopServerService()
                        startServerService()
                    }
                }

                // Poll health every 2 seconds until server responds
                showLoading(getString(R.string.server_restarting))
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

                // Reload WebView
                hideAllOverlays()
                loadIDE()
                updateStatusIndicator(true)
            } catch (e: Exception) {
                Log.e(TAG, "Restart failed", e)
                hideAllOverlays()
                updateStatusIndicator(false)
            } finally {
                btnRestart.isEnabled = true
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
            setTextColor(Color.parseColor("#C9D1D9"))
            setBackgroundColor(Color.parseColor("#0D1117"))
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
                val request = Request.Builder()
                    .url("${PhoneIDEApp.SERVER_URL}/api/server/logs/stream")
                    .build()
                val response = httpClient.newCall(request).execute()

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
                    withContext(Dispatchers.Main) {
                        if (dialog.isShowing) {
                            logTextView.append("$logLine\n")
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
    // Update Button Handler
    // ========================

    @SuppressLint("SetTextI18n")
    private fun handleCheckUpdate() {
        btnUpdate.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    // Show toast
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_checking),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                // POST to /api/update/check
                val responseBody = withContext(Dispatchers.IO) {
                    postToServerWithResponse("/api/update/check")
                }

                if (responseBody == null) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Could not check for updates",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val json = JSONObject(responseBody)
                val updateAvailable = json.optBoolean("update_available", false)
                val currentVersion = json.optString("current_version", "unknown")
                val newVersion = json.optString("new_version", "")
                val releaseNotes = json.optString("release_notes", "")

                if (!updateAvailable) {
                    // Up to date
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.update_current))
                        .setMessage("Current version: $currentVersion")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }

                // Update available - show dialog
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.update_available))
                    .setMessage(
                        "New version: $newVersion\n" +
                        "Current version: $currentVersion\n\n" +
                        "Release notes:\n$releaseNotes"
                    )
                    .setPositiveButton("Update Now") { _, _ ->
                        applyUpdate(newVersion)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Update check failed: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                btnUpdate.isEnabled = true
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applyUpdate(version: String) {
        btnUpdate.isEnabled = false

        // Show progress dialog
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.updating))
            .setMessage("Downloading and applying update to $version...\n\nPlease wait, this may take a moment.")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            try {
                // POST to /api/update/apply
                val success = withContext(Dispatchers.IO) {
                    postToServer("/api/update/apply", useUpdateClient = true)
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                }

                if (success) {
                    // Restart server and reload
                    withContext(Dispatchers.Main) {
                        updateStatusIndicator(false)
                        statusLabel.text = getString(R.string.server_restarting)

                        stopServerService()
                        startServerService()

                        showLoading("Server restarting after update...")

                        // Poll until server is back
                        scope.launch {
                            var attempts = 0
                            while (attempts < 30 && isActive) {
                                delay(HEALTH_POLL_INTERVAL)
                                val isUp = withContext(Dispatchers.IO) {
                                    checkServerConnection()
                                }
                                if (isUp) break
                                attempts++
                            }
                            hideAllOverlays()
                            loadIDE()
                            updateStatusIndicator(true)

                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Updated to $version successfully!",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Update Failed")
                        .setMessage("The update could not be applied. Please try again or check server logs.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update apply failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Update Failed")
                        .setMessage("Error: ${e.message}")
                        .setPositiveButton("OK", null)
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
                    .setTitle("PhoneIDE v${PhoneIDEApp.VERSION_NAME}")
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
        stopService(Intent(this, ServerService::class.java))
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
