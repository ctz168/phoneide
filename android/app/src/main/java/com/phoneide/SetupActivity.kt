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
import java.io.FileOutputStream
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
                // Step 1: Initialize ProcessManager (sets up proot binary)
                updateStatus("正在初始化...")
                appendLog("[1/5] 初始化 ProcessManager...")
                processManager.initialize()

                if (!File(processManager.getProotBin()).exists()) {
                    appendLog("  proot 二进制文件未找到!")
                    appendLog("  请确保 APK 构建时包含了 jniLibs 中的 libproot.so")
                    showActions(btnRetry)
                    return@launch
                }
                appendLog("  proot 就绪 ✓")

                // Step 2: Extract IDE files from APK assets (independent of rootfs)
                updateStatus("正在提取 IDE 文件...")
                appendLog("[2/5] 提取内置 IDE 文件...")
                val hostIdeDir = processManager.getIdeDir()
                val ideServerPy = File("$hostIdeDir/server.py")
                val ideIndexHtml = File("$hostIdeDir/static/index.html")

                if (!ideServerPy.exists() || !ideIndexHtml.exists()) {
                    appendLog("  从 APK 内置资源提取...")
                    copyIDEFromAssets(hostIdeDir)
                }

                if (ideServerPy.exists() && ideIndexHtml.exists()) {
                    appendLog("  IDE 文件就绪 ✓ (${ideServerPy.length() / 1024}KB)")
                } else {
                    appendLog("  警告: IDE 文件提取不完整!")
                    appendLog("  server.py: ${ideServerPy.exists()}, index.html: ${ideIndexHtml.exists()}")
                    Log.e(TAG, "IDE files missing - server.py=${ideServerPy.exists()}, index.html=${ideIndexHtml.exists()}")
                }

                // Step 3: Check if rootfs already exists
                appendLog("[3/5] 检查 Ubuntu rootfs...")
                if (processManager.isRootfsReady()) {
                    appendLog("  Ubuntu rootfs 已存在 ✓")
                } else {
                    // Full bootstrap: download + extract + configure + install deps
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
                        appendLog("  你可以跳过，稍后在终端中手动操作")
                        appendLog("  或点击重试再次尝试")
                        // Show both skip and retry buttons
                        runOnUiThread {
                            setupActions.visibility = View.VISIBLE
                            btnRetry.visibility = View.VISIBLE
                            btnContinue.visibility = View.VISIBLE
                            btnContinue.text = "跳过（进终端）"
                        }
                        return@launch
                    }
                    appendLog("  Ubuntu rootfs 配置完成 ✓")
                }

                // Step 4: Verify Python/Flask inside proot
                updateStatus("正在验证...")
                appendLog("[4/5] 验证 Python 环境...")
                try {
                    val testResult = processManager.runInProot(
                        "python3 --version 2>&1 && pip3 show flask 2>/dev/null | head -1",
                        timeoutMs = 30_000
                    )
                    if (testResult.success) {
                        appendLog("  ${testResult.stdout.trim()}")
                    } else {
                        appendLog("  Python 环境检查未通过（可稍后修复）")
                    }
                } catch (e: Exception) {
                    appendLog("  Python 检查跳过: ${e.message}")
                }

                // Step 5: Final check
                appendLog("[5/5] 最终检查...")
                appendLog("  proot: ${if (File(processManager.getProotBin()).exists()) "✓" else "✗"}")
                appendLog("  rootfs: ${if (processManager.isRootfsReady()) "✓" else "✗"}")
                appendLog("  IDE: ${if (ideServerPy.exists()) "✓" else "✗"}")

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

    /**
     * Extract IDE files from APK assets/ide/ to hostIdeDir.
     * This is independent of bootstrap - works even without rootfs.
     */
    private fun copyIDEFromAssets(targetDir: String) {
        try {
            val target = File(targetDir)
            target.mkdirs()
            extractAssetsDir("ide", target)
            appendLog("  提取完成: ${countFiles(target)} 个文件")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract IDE from assets", e)
            appendLog("  提取失败: ${e.message}")
        }
    }

    /**
     * Recursively extract a directory from APK assets.
     */
    private fun extractAssetsDir(assetPath: String, targetDir: File) {
        val am = assets
        val entries = am.list(assetPath) ?: return

        for (entry in entries) {
            val fullAssetPath = "$assetPath/$entry"
            val targetFile = File(targetDir, entry)

            // Try to list to determine if directory or file
            val subEntries = try { am.list(fullAssetPath) } catch (e: Exception) { null }

            if (subEntries != null && subEntries.isNotEmpty()) {
                // Directory - recurse
                targetFile.mkdirs()
                extractAssetsDir(fullAssetPath, targetFile)
            } else {
                // File - copy bytes
                targetFile.parentFile?.mkdirs()
                try {
                    am.open(fullAssetPath).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buf = ByteArray(8192)
                            var len: Int
                            while (input.read(buf).also { len = it } != -1) {
                                output.write(buf, 0, len)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract: $fullAssetPath - ${e.message}")
                }
            }
        }
    }

    /**
     * Count files recursively in a directory.
     */
    private fun countFiles(dir: File): Int {
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) count += countFiles(file)
            else count++
        }
        return count
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
