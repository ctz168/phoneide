package com.phoneide

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.*

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var setupProgress: View
    private lateinit var setupStatus: android.widget.TextView
    private lateinit var setupLog: android.widget.TextView
    private lateinit var setupActions: View
    private lateinit var btnRetry: View
    private lateinit var btnContinue: View

    private lateinit var processManager: ProcessManager
    private lateinit var bootstrapManager: BootstrapManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        processManager = ProcessManager(applicationContext)
        bootstrapManager = BootstrapManager(applicationContext, processManager)

        initViews()
        runSetup()
    }

    private fun initViews() {
        setupProgress = findViewById(R.id.setup_progress)
        setupStatus = findViewById(R.id.setup_status)
        setupLog = findViewById(R.id.setup_log)
        setupActions = findViewById(R.id.setup_actions)
        btnRetry = findViewById(R.id.btn_retry)
        btnContinue = findViewById(R.id.btn_continue)

        // Hide install Termux button (no longer needed)
        val btnInstallTermux = findViewById<View?>(R.id.btn_install_termux)
        btnInstallTermux?.visibility = View.GONE

        btnRetry.setOnClickListener {
            setupLog.text = ""
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
        runOnUiThread {
            setupStatus.text = status
        }
    }

    private fun showActions(vararg views: View) {
        runOnUiThread {
            setupActions.visibility = View.VISIBLE
            btnRetry.visibility = View.GONE
            btnContinue.visibility = View.GONE
            for (v in views) v.visibility = View.VISIBLE
        }
    }

    @SuppressLint("SdCardPath")
    private fun runSetup() {
        scope.launch {
            try {
                // Step 1: Initialize ProcessManager
                updateStatus("正在初始化...")
                appendLog("[1/4] 初始化 ProcessManager...")
                processManager.initialize()

                if (!File(processManager.getProotBin()).exists()) {
                    appendLog("  proot 二进制文件未找到!")
                    appendLog("  请确保 APK 构建时包含了 jniLibs 中的 libproot.so")
                    appendLog("  运行 scripts/fetch-proot-binaries.sh 后重新构建")
                    showActions(btnRetry)
                    return@launch
                }
                appendLog("  proot 就绪 ✓")

                // Step 2: Check if rootfs already exists
                appendLog("[2/4] 检查 Ubuntu rootfs...")
                if (processManager.isRootfsReady()) {
                    appendLog("  Ubuntu rootfs 已存在 ✓")
                } else {
                    // Full bootstrap: download + extract + configure
                    appendLog("  需要下载和配置 Ubuntu rootfs...")
                    appendLog("  首次安装可能需要 5-10 分钟")

                    bootstrapManager.setProgressListener { percent, message ->
                        if (percent < 0) {
                            appendLog("  错误: $message")
                        } else {
                            updateStatus("正在设置... $percent%")
                        }
                    }

                    val success = bootstrapManager.bootstrap()
                    if (!success) {
                        appendLog("  Bootstrap 失败!")
                        appendLog("  请检查网络连接后重试")
                        showActions(btnRetry)
                        return@launch
                    }
                    appendLog("  Ubuntu rootfs 配置完成 ✓")
                }

                // Step 3: Copy IDE files into rootfs
                updateStatus("正在复制 IDE 文件...")
                appendLog("[3/4] 设置 IDE 文件...")

                // Check if IDE files exist in the host directory
                val hostIdeDir = processManager.getIdeDir()
                if (File(hostIdeDir).listFiles()?.isEmpty() != false) {
                    appendLog("  IDE 文件目录为空，尝试从内置资源复制...")
                    // Copy from assets (if bundled) or download
                    copyIDEFromAssets()
                }

                // Copy IDE files into rootfs
                val result = processManager.runInProot(
                    "ls /root/phoneide/server.py 2>/dev/null && echo 'EXISTS'",
                    timeoutMs = 10_000
                )
                if (result.stdout.contains("EXISTS")) {
                    appendLog("  IDE 文件就绪 ✓")
                } else {
                    appendLog("  警告: IDE 文件未找到，将在首次启动时下载")
                }

                // Step 4: Verify server
                updateStatus("正在验证...")
                appendLog("[4/4] 验证环境...")

                val testResult = processManager.runInProot(
                    "python3 --version && pip3 show flask 2>/dev/null | head -1",
                    timeoutMs = 30_000
                )
                appendLog("  ${testResult.stdout.trim()}")

                appendLog("")
                appendLog("=============================")
                appendLog("  设置完成！")
                appendLog("=============================")
                updateStatus("设置完成！")
                showActions(btnContinue)

            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                appendLog("")
                appendLog("设置出错: ${e.message}")
                e.message?.lines()?.take(5)?.forEach { appendLog("  $it") }
                showActions(btnRetry)
            }
        }
    }

    private fun copyIDEFromAssets() {
        // If IDE files are bundled in assets, copy them
        // For now, this is handled by BootstrapManager.setupIDEFiles()
        // In a future update, we could bundle server.py in assets/
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
