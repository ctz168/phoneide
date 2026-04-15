package com.ctz168.phoneide

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var setupStatus: android.widget.TextView
    private lateinit var setupLog: android.widget.TextView
    private lateinit var btnRetry: View
    private lateinit var btnContinue: View

    private lateinit var processManager: ProcessManager
    private lateinit var bootstrapManager: BootstrapManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as PhoneIDEApp
        processManager = app.processManager
        bootstrapManager = app.bootstrapManager

        initViews()
        runSetup()
    }

    private fun initViews() {
        setupStatus = findViewById(R.id.setup_status)
        setupLog = findViewById(R.id.setup_log)
        btnRetry = findViewById(R.id.btn_retry)
        btnContinue = findViewById(R.id.btn_continue)

        val btnInstallTermux = findViewById<View?>(R.id.btn_install_termux)
        btnInstallTermux?.visibility = View.GONE

        val setupActions = findViewById<View>(R.id.setup_actions)

        btnRetry.setOnClickListener {
            setupLog.text = ""
            setupActions.visibility = View.GONE
            runSetup()
        }
        btnContinue.setOnClickListener {
            (application as PhoneIDEApp).setSetupComplete(true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            setupLog.append(message + "\n")
            val scrollView = setupLog.parent as? android.widget.ScrollView
            scrollView?.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread { setupStatus.text = status }
    }

    private fun showButton(view: View) {
        runOnUiThread {
            val actions = findViewById<View>(R.id.setup_actions)
            actions.visibility = View.VISIBLE
            btnRetry.visibility = View.GONE
            btnContinue.visibility = View.GONE
            view.visibility = View.VISIBLE
        }
    }

    @SuppressLint("SdCardPath")
    private fun runSetup() {
        scope.launch {
            try {
                // Step 1: Initialize
                updateStatus("Initializing...")
                appendLog("[1/5] Initializing ProcessManager...")
                processManager.initialize()

                if (!java.io.File(processManager.getProotPath()).exists()) {
                    appendLog("  ERROR: proot binary not found!")
                    appendLog("  Make sure APK includes jniLibs/libproot.so")
                    appendLog("  Run scripts/fetch-proot-binaries.sh and rebuild")
                    showButton(btnRetry)
                    return@launch
                }
                appendLog("  proot ready")

                // Step 2: Check/setup rootfs
                appendLog("[2/5] Checking Ubuntu rootfs...")
                if (processManager.isRootfsReady()) {
                    appendLog("  Ubuntu rootfs exists")
                } else {
                    appendLog("  Need to download Ubuntu 24.04 rootfs...")
                    appendLog("  First install may take 5-10 minutes")

                    bootstrapManager.setProgressListener { percent, message ->
                        if (percent < 0) appendLog("  ERROR: $message")
                        else updateStatus("Setting up... $percent%")
                    }

                    val success = bootstrapManager.bootstrap()
                    if (!success) {
                        appendLog("  Bootstrap failed!")
                        appendLog("  Check network connection and retry")
                        showButton(btnRetry)
                        return@launch
                    }
                    appendLog("  Ubuntu rootfs configured")
                }

                // Step 3: Copy IDE files
                updateStatus("Copying IDE files...")
                appendLog("[3/5] Setting up IDE files...")

                val hostIdeDir = processManager.hostIdeDir
                if (java.io.File(hostIdeDir).listFiles()?.isEmpty() != false) {
                    appendLog("  IDE directory empty, copying from assets...")
                    copyIDEFromAssets()
                }

                // Copy into rootfs
                bootstrapManager.setupIDEFiles()

                val checkResult = try {
                    processManager.runInProotSync(
                        "ls /root/phoneide/server.py 2>/dev/null && echo EXISTS",
                        15
                    )
                } catch (e: Exception) { e.message ?: "" }

                if (checkResult.contains("EXISTS")) {
                    appendLog("  IDE files ready")
                } else {
                    appendLog("  WARNING: IDE files not found in rootfs")
                }

                // Step 4: Verify Python
                updateStatus("Verifying...")
                appendLog("[4/5] Verifying Python environment...")
                try {
                    val testResult = processManager.runInProotSync(
                        "python3 --version 2>&1",
                        30
                    )
                    appendLog("  ${testResult.trim()}")
                } catch (e: Exception) {
                    appendLog("  Python check: ${e.message}")
                    appendLog("  Will install on first server start")
                }

                // Step 5: Verify Flask
                appendLog("[5/5] Verifying Flask...")
                try {
                    val flaskResult = processManager.runInProotSync(
                        "pip3 show flask 2>/dev/null | head -1 || echo NOT_INSTALLED",
                        15
                    )
                    appendLog("  ${flaskResult.trim()}")
                } catch (e: Exception) {
                    appendLog("  Flask check: ${e.message}")
                    appendLog("  Will install on first server start")
                }

                appendLog("")
                appendLog("=============================")
                appendLog("  Setup Complete!")
                appendLog("=============================")
                updateStatus("Setup Complete!")
                showButton(btnContinue)

            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                appendLog("")
                appendLog("Setup error: ${e.message}")
                e.message?.lines()?.take(5)?.forEach { appendLog("  $it") }
                showButton(btnRetry)
            }
        }
    }

    private fun copyIDEFromAssets() {
        try {
            val hostIdeDir = processManager.hostIdeDir
            java.io.File(hostIdeDir).mkdirs()

            // Copy IDE files from assets/ide/ to hostIdeDir
            val assetManager = assets
            copyAssetDir("ide", java.io.File(hostIdeDir))
            appendLog("  Copied from assets/ide/ to $hostIdeDir")
        } catch (e: Exception) {
            appendLog("  Failed to copy from assets: ${e.message}")
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: java.io.File) {
        val files = assets.list(assetPath) ?: return
        for (file in files) {
            val srcPath = "$assetPath/$file"
            val destFile = java.io.File(destDir, file)
            try {
                val inputStream = assets.open(srcPath)
                java.io.FileOutputStream(destFile).use { out ->
                    inputStream.copyTo(out)
                }
                inputStream.close()
            } catch (e: Exception) {
                // Might be a directory
                try {
                    destDir.mkdirs()
                    copyAssetDir(srcPath, destFile)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
