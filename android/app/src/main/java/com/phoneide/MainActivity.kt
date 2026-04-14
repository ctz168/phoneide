package com.phoneide

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var progressBar: View
    private lateinit var statusText: View
    private lateinit var errorOverlay: View
    private lateinit var errorText: View
    private lateinit var retryButton: View

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
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

        retryButton.setOnClickListener { connectToServer() }
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
                    .setTitle("PhoneIDE v1.0")
                    .setMessage("基于 Termux + proot Ubuntu 的手机端 Web IDE\n\n" +
                            "功能：代码编辑、终端、Git、LLM 聊天\n" +
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
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
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
