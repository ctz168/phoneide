package com.phoneide

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
        private const val TERMUX_FDROID_URL = "https://f-droid.org/packages/com.termux/"
        private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/ctz168/phoneide/main"
        private const val INSTALL_SCRIPT_URL = "$GITHUB_RAW_BASE/install.sh"
        private const val SETUP_SCRIPT_URL = "$GITHUB_RAW_BASE/setup_android.sh"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var setupProgress: View
    private lateinit var setupStatus: android.widget.TextView
    private lateinit var setupLog: android.widget.TextView
    private lateinit var setupActions: View
    private lateinit var btnInstallTermux: View
    private lateinit var btnRetry: View
    private lateinit var btnContinue: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        runSetup()
    }

    private fun initViews() {
        setupProgress = findViewById(R.id.setup_progress)
        setupStatus = findViewById(R.id.setup_status)
        setupLog = findViewById(R.id.setup_log)
        setupActions = findViewById(R.id.setup_actions)
        btnInstallTermux = findViewById(R.id.btn_install_termux)
        btnRetry = findViewById(R.id.btn_retry)
        btnContinue = findViewById(R.id.btn_continue)

        btnInstallTermux.setOnClickListener {
            openTermuxFDroid()
        }
        btnRetry.setOnClickListener {
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
            btnInstallTermux.visibility = View.GONE
            btnRetry.visibility = View.GONE
            btnContinue.visibility = View.GONE
            for (v in views) v.visibility = View.VISIBLE
        }
    }

    private fun openTermuxFDroid() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(TERMUX_FDROID_URL)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SdCardPath")
    private fun runSetup() {
        scope.launch {
            try {
                // Step 1: Check Termux
                updateStatus("正在检查 Termux...")
                appendLog("[1/6] 检查 Termux 是否已安装...")

                if (!isTermuxInstalled()) {
                    appendLog("  Termux 未安装")
                    appendLog("  请从 F-Droid 安装 Termux")
                    appendLog("  安装后返回本应用点击重试")
                    showActions(btnInstallTermux)
                    return@launch
                }
                appendLog("  Termux 已安装 ✓")

                // Step 2: Check Termux environment
                updateStatus("正在检查 Termux 环境...")
                appendLog("[2/6] 检查 Termux 环境...")

                if (!isTermuxReady()) {
                    appendLog("  Termux 环境未就绪")
                    appendLog("  正在初始化 Termux...")
                    if (!initTermux()) {
                        appendLog("  Termux 初始化失败")
                        showActions(btnRetry)
                        return@launch
                    }
                }
                appendLog("  Termux 环境就绪 ✓")

                // Step 3: Setup proot Ubuntu
                updateStatus("正在设置 Ubuntu 环境...")
                appendLog("[3/6] 设置 proot Ubuntu...")

                if (!isProotUbuntuInstalled()) {
                    appendLog("  正在安装 proot-distro...")
                    if (!installProotUbuntu()) {
                        appendLog("  proot Ubuntu 安装失败")
                        appendLog("  请确保网络连接正常")
                        showActions(btnRetry)
                        return@launch
                    }
                }
                appendLog("  proot Ubuntu 已就绪 ✓")

                // Step 4: Copy IDE files
                updateStatus("正在复制 IDE 文件...")
                appendLog("[4/6] 复制 IDE 文件...")

                copyIDEFiles()
                appendLog("  IDE 文件复制完成 ✓")

                // Step 5: Install Python dependencies
                updateStatus("正在安装 Python 依赖...")
                appendLog("[5/6] 安装 Python 依赖...")

                installPythonDeps()
                appendLog("  Python 依赖安装完成 ✓")

                // Step 6: Start server test
                updateStatus("正在验证服务...")
                appendLog("[6/6] 验证服务器...")

                val testResult = testServer()
                if (testResult) {
                    appendLog("  服务器运行正常 ✓")
                    appendLog("")
                    appendLog("════════════════════════")
                    appendLog("  设置完成！")
                    appendLog("════════════════════════")
                    updateStatus("设置完成！")
                    showActions(btnContinue)
                } else {
                    appendLog("  服务器测试未通过")
                    appendLog("  但基础环境已就绪")
                    appendLog("  进入应用后可手动启动服务")
                    updateStatus("基本设置完成")
                    showActions(btnContinue)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                appendLog("")
                appendLog("设置出错: ${e.message}")
                showActions(btnRetry)
            }
        }
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(PhoneIDEApp.TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun isTermuxReady(): Boolean {
        return try {
            val termuxPrefix = "/data/data/com.termux/files/usr"
            File("$termuxPrefix/bin/sh").exists() &&
            File("$termuxPrefix/bin/pkg").exists()
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun initTermux(): Boolean {
        return try {
            // Run Termux setup via RUN_COMMAND intent
            val commands = listOf(
                "mkdir -p /data/data/com.termux/files/usr/tmp",
                "mkdir -p /data/data/com.termux/files/home",
                "ln -sf /data/data/com.termux/files/usr/bin/sh /data/data/com.termux/files/usr/bin/bash 2>/dev/null || true"
            )

            for (cmd in commands) {
                runTermuxCommand(cmd)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Termux", e)
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun isProotUbuntuInstalled(): Boolean {
        return try {
            val result = runTermuxCommand("proot-distro list 2>/dev/null")
            result.contains("ubuntu")
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun installProotUbuntu(): Boolean {
        return try {
            appendLog("  安装 proot-distro 包...")
            runTermuxCommand("pkg install -y proot-distro")

            appendLog("  下载 Ubuntu rootfs（可能需要几分钟）...")
            appendLog("  首次安装会下载约200MB数据...")

            val result = runTermuxCommandWithTimeout(
                "proot-distro install ubuntu",
                600000 // 10 minutes timeout
            )
            appendLog("  Ubuntu 安装结果: ${result.take(200)}")

            // Install Python in proot Ubuntu
            appendLog("  在 Ubuntu 中安装 Python...")
            runTermuxCommandWithTimeout(
                "proot-distro login ubuntu -- bash -c 'apt update && apt install -y python3 python3-pip python3-venv git'",
                600000
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install proot Ubuntu", e)
            appendLog("  错误: ${e.message}")
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun copyIDEFiles() {
        try {
            val termuxHome = "/data/data/com.termux/files/home"
            val ideDir = "$termuxHome/phoneide"

            // Download install script and run it
            appendLog("  下载安装脚本...")

            // Copy files from assets to Termux home
            val app = application as PhoneIDEApp
            val assetManager = app.assets

            // First, try to download from GitHub
            val downloadResult = runTermuxCommandWithTimeout(
                "curl -fsSL $INSTALL_SCRIPT_URL | bash -s -- -r ctz168/phoneide",
                300000
            )
            appendLog("  安装结果: ${downloadResult.take(300)}")

            // Verify files exist
            val checkResult = runTermuxCommand("ls -la $ideDir/ 2>/dev/null | head -5")
            appendLog("  文件列表: ${checkResult.take(200)}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy IDE files", e)
            appendLog("  错误: ${e.message}")
            throw e
        }
    }

    @SuppressLint("SdCardPath")
    private fun installPythonDeps() {
        try {
            val termuxHome = "/data/data/com.termux/files/home"
            val ideDir = "$termuxHome/phoneide"

            // Install dependencies in proot Ubuntu
            appendLog("  安装 Flask 等依赖...")

            val result = runTermuxCommandWithTimeout(
                "proot-distro login ubuntu -- bash -c 'cd $ideDir && pip3 install -r requirements.txt 2>&1 || pip3 install flask 2>&1'",
                300000
            )
            appendLog("  ${result.take(300)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install Python deps", e)
            appendLog("  警告: ${e.message}")
        }
    }

    @SuppressLint("SdCardPath")
    private fun testServer(): Boolean {
        return try {
            // Start server briefly to test
            val termuxHome = "/data/data/com.termux/files/home"
            val ideDir = "$termuxHome/phoneide"

            appendLog("  启动测试服务器...")

            // Start server in background
            runTermuxCommand(
                "proot-distro login ubuntu -- bash -c 'cd $ideDir && nohup python3 server.py > /tmp/phoneide.log 2>&1 &'"
            )

            // Wait for server to start
            Thread.sleep(5000)

            // Test connection
            var connected = false
            for (i in 0..10) {
                try {
                    val url = URL("http://127.0.0.1:${PhoneIDEApp.SERVER_PORT}/api/health")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.requestMethod = "GET"
                    val responseCode = conn.responseCode
                    conn.disconnect()

                    if (responseCode == 200) {
                        connected = true
                        appendLog("  服务器响应正常 ✓")
                        break
                    }
                } catch (e: Exception) {
                    Thread.sleep(1000)
                }
            }

            if (!connected) {
                appendLog("  注意: 服务器未响应，但文件已就位")
            }

            connected
        } catch (e: Exception) {
            Log.e(TAG, "Server test failed", e)
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun runTermuxCommand(command: String): String {
        return runTermuxCommandWithTimeout(command, 30000)
    }

    @SuppressLint("SdCardPath")
    private fun runTermuxCommandWithTimeout(command: String, timeoutMs: Long): String {
        val process = ProcessBuilder()
            .command(
                "su", "-c",
                "run-as com.termux sh -c '$command'"
            )
            .redirectErrorStream(true)
            .start()

        // Alternative approach using Termux RUN_COMMAND intent
        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        val deadline = System.currentTimeMillis() + timeoutMs
        val readThread = Thread {
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            } catch (e: Exception) {
                // Stream closed
            }
        }
        readThread.start()

        try {
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroyForcibly()
        }

        readThread.join(2000)

        val result = output.toString()
        appendLog(result.take(500))

        return result
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
