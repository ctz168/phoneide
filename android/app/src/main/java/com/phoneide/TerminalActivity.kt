package com.phoneide

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.*

class TerminalActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TerminalActivity"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var terminalContainer: FrameLayout  // FIXED: was LinearLayout, but XML uses FrameLayout
    private lateinit var outputScrollView: ScrollView
    private lateinit var outputView: TextView
    private lateinit var inputLine: LinearLayout
    private lateinit var inputPrompt: TextView
    private lateinit var inputField: EditText
    private lateinit var btnSend: Button

    private var currentFontSize = 13
    private var sessionProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var outputBuffer = StringBuilder()
    private var isDestroyed = false

    // Session types
    private enum class SessionType {
        UBUNTU,        // proot Ubuntu shell
        PYTHON,        // Python REPL inside proot
        NATIVE,        // Android native shell (fallback, no proot needed)
    }

    private var currentSession = SessionType.NATIVE  // Default to NATIVE (always works)
    private lateinit var processManager: ProcessManager

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_terminal)

            processManager = ProcessManager(applicationContext)
            try {
                processManager.initialize()
            } catch (e: Exception) {
                Log.e(TAG, "ProcessManager init failed: ${e.message}")
            }

            initViews()
            buildTerminalUI()
            setupInputHandling()

            // Auto-select best available session
            autoSelectSession()
            startTerminalSession()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL in onCreate: ${e.message}", e)
            // Show minimal error UI instead of crashing
            showFatalError(e)
        }
    }

    private fun showFatalError(e: Exception) {
        try {
            val container = findViewById<FrameLayout>(R.id.terminal_container)
            container.removeAllViews()
            val tv = TextView(this).apply {
                text = "Terminal Error:\n${e.message}\n\n${e.stackTraceToString().take(1000)}"
                setTextColor(Color.RED)
                setBackgroundColor(Color.parseColor("#0D1117"))
                setPadding(24, 24, 24, 24)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            container.addView(tv)
        } catch (e2: Exception) {
            // Can't even show error - just finish
            Log.e(TAG, "Cannot show error UI", e2)
            finish()
        }
    }

    private fun initViews() {
        terminalContainer = findViewById(R.id.terminal_container)
        val btnClose = findViewById<View>(R.id.btn_close_terminal)
        val btnFontSizeUp = findViewById<View>(R.id.btn_font_size_up)
        val btnFontSizeDown = findViewById<View>(R.id.btn_font_size_down)

        btnClose.setOnClickListener { finish() }
        btnFontSizeUp.setOnClickListener { adjustFontSize(1) }
        btnFontSizeDown.setOnClickListener { adjustFontSize(-1)
        }
    }

    private fun buildTerminalUI() {
        terminalContainer.removeAllViews()
        isDestroyed = false

        val context = this
        val baseLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Output area
        outputScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            isVerticalScrollBarEnabled = true
            scrollBarStyle = ScrollView.SCROLLBARS_INSIDE_OVERLAY
        }

        outputView = TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#7EE787"))
            setBackgroundColor(Color.parseColor("#0D1117"))
            textSize = currentFontSize.toFloat()
            setPadding(12, 12, 12, 12)
            setTextIsSelectable(true)
        }
        outputScrollView.addView(outputView)
        baseLayout.addView(outputScrollView)

        // Input area
        inputLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#161B22"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        inputPrompt = TextView(context).apply {
            text = "$ "
            setTextColor(Color.parseColor("#58A6FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = currentFontSize.toFloat()
            setPadding(4, 0, 4, 0)
        }

        val inputScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        inputField = EditText(context).apply {
            setTextColor(Color.parseColor("#C9D1D9"))
            setHintTextColor(Color.parseColor("#484F58"))
            hint = "cmd"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = currentFontSize.toFloat()
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            setPadding(0, 0, 0, 0)
        }
        inputScroll.addView(inputField)

        btnSend = Button(context).apply {
            text = "Go"
            setTextColor(Color.parseColor("#58A6FF"))
            setBackgroundColor(Color.parseColor("#21262D"))
            textSize = 12f
            setPadding(8, 0, 8, 0)
            setOnClickListener { executeCommand() }
        }

        inputLine.addView(inputPrompt)
        inputLine.addView(inputScroll)
        inputLine.addView(btnSend)
        baseLayout.addView(inputLine)

        terminalContainer.addView(baseLayout)
    }

    private fun setupInputHandling() {
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_NULL ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT) {
                executeCommand()
                true
            } else {
                false
            }
        }

        inputField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                executeCommand()
                true
            } else {
                false
            }
        }

        inputField.requestFocus()
    }

    private fun autoSelectSession() {
        val prootBin = processManager.getProotBin()
        val prootExists = try { File(prootBin).exists() } catch (e: Exception) { false }
        val rootfsReady = processManager.isRootfsReady()

        Log.d(TAG, "autoSelectSession: proot=$prootExists, rootfs=$rootfsReady")

        if (prootExists && rootfsReady) {
            currentSession = SessionType.UBUNTU
        } else {
            currentSession = SessionType.NATIVE
            appendOutput("[PhoneIDE] proot/rootfs not ready, using native shell\n")
            if (!prootExists) appendOutput("  proot: NOT FOUND at $prootBin\n")
            if (!rootfsReady) appendOutput("  rootfs: NOT READY at ${processManager.getRootfsDir()}\n")
            appendOutput("  Type 'ubuntu' to try proot, 'help' for commands\n\n")
        }
    }

    private fun executeCommand() {
        val command = inputField.text.toString().trim()
        if (command.isEmpty()) {
            return
        }

        val prompt = when (currentSession) {
            SessionType.UBUNTU -> "(ubuntu)$ "
            SessionType.PYTHON -> ">>> "
            SessionType.NATIVE -> "$ "
        }

        appendOutput("$prompt$command\n")
        inputField.setText("")

        // Handle special commands
        when (command.lowercase()) {
            "clear", "cls" -> {
                outputBuffer.clear()
                runOnUiThread { outputView.text = "" }
                return
            }
            "exit", "quit" -> {
                finish()
                return
            }
            "help" -> {
                appendOutput("Commands:\n")
                appendOutput("  ubuntu  - Switch to Ubuntu proot shell\n")
                appendOutput("  python  - Switch to Python REPL (proot)\n")
                appendOutput("  native  - Switch to Android native shell\n")
                appendOutput("  diag    - Show diagnostic info\n")
                appendOutput("  setup   - Go to setup page\n")
                appendOutput("  clear   - Clear terminal\n")
                appendOutput("  exit    - Close terminal\n\n")
                return
            }
            "diag", "diagnostic" -> {
                showDiagnostics()
                return
            }
            "setup" -> {
                // Reset setup flag and go to setup
                (application as PhoneIDEApp).setSetupComplete(false)
                finish()
                startActivity(android.content.Intent(this, SetupActivity::class.java))
                return
            }
            "ubuntu", "bash" -> {
                if (File(processManager.getProotBin()).exists() && processManager.isRootfsReady()) {
                    switchSession(SessionType.UBUNTU)
                } else {
                    appendOutput("Ubuntu shell not available (proot/rootfs missing)\n")
                    appendOutput("Run 'setup' to configure, or 'diag' for details\n")
                }
                return
            }
            "python", "python3" -> {
                if (File(processManager.getProotBin()).exists() && processManager.isRootfsReady()) {
                    switchSession(SessionType.PYTHON)
                } else {
                    appendOutput("Python REPL not available (proot/rootfs missing)\n")
                }
                return
            }
            "native", "android", "sh" -> {
                switchSession(SessionType.NATIVE)
                return
            }
        }

        // Send to process
        scope.launch {
            try {
                writer?.write("$command\n")
                writer?.flush()
            } catch (e: Exception) {
                safeAppend("Error: ${e.message}\n")
            }
        }
    }

    @SuppressLint("SdCardPath")
    private fun showDiagnostics() {
        appendOutput("=== PhoneIDE Diagnostics ===\n")
        appendOutput("App dir: ${filesDir.absolutePath}\n")
        appendOutput("Native lib dir: ${applicationInfo.nativeLibraryDir}\n")

        val prootBin = processManager.getProotBin()
        appendOutput("Proot bin: $prootBin\n")
        appendOutput("  exists: ${File(prootBin).exists()}")
        if (File(prootBin).exists()) {
            appendOutput(" (${File(prootBin).length()} bytes, exec=${File(prootBin).canExecute()})")
        }
        appendOutput("\n")

        val rootfsDir = processManager.getRootfsDir()
        appendOutput("Rootfs dir: $rootfsDir\n")
        appendOutput("  exists: ${File(rootfsDir).exists()}\n")
        appendOutput("  bin/bash: ${File("$rootfsDir/bin/bash").exists()}\n")
        appendOutput("  etc/apt: ${File("$rootfsDir/etc/apt").exists()}\n")
        appendOutput("  usr: ${File("$rootfsDir/usr").exists()}\n")

        val ideDir = processManager.getIdeDir()
        appendOutput("IDE dir: $ideDir\n")
        appendOutput("  server.py: ${File("$ideDir/server.py").exists()}")
        if (File("$ideDir/server.py").exists()) {
            appendOutput(" (${File("$ideDir/server.py").length() / 1024}KB)")
        }
        appendOutput("\n")

        // Try running proot --version
        appendOutput("\nTesting proot...\n")
        try {
            val result = processManager.runInProot("echo proot-test-ok", timeoutMs = 15_000)
            appendOutput("  exit=${result.exitCode}, success=${result.success}\n")
            if (result.stdout.isNotEmpty()) {
                appendOutput("  stdout: ${result.stdout.trim()}\n")
            }
            if (!result.success && result.stdout.isNotEmpty()) {
                appendOutput("  error: ${result.stdout.take(300)}\n")
            }
        } catch (e: Exception) {
            appendOutput("  FAILED: ${e.message}\n")
        }

        // Test native shell
        appendOutput("\nTesting native shell...\n")
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo ok && uname -m"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            appendOutput("  exit=${p.exitValue()}, out=$out\n")
        } catch (e: Exception) {
            appendOutput("  FAILED: ${e.message}\n")
        }

        appendOutput("\n===========================\n\n")
    }

    private fun switchSession(session: SessionType) {
        // Kill current session
        try {
            writer?.close()
            writer = null
        } catch (e: Exception) { }
        try {
            sessionProcess?.destroy()
            sessionProcess = null
        } catch (e: Exception) { }

        currentSession = session
        val label = when (session) {
            SessionType.UBUNTU -> "Ubuntu (proot)"
            SessionType.PYTHON -> "Python REPL"
            SessionType.NATIVE -> "Android Shell"
        }
        safeAppend("\n--- Switch to $label ---\n")
        runOnUiThread {
            inputPrompt.text = when (session) {
                SessionType.UBUNTU -> "(ubuntu)$ "
                SessionType.PYTHON -> ">>> "
                SessionType.NATIVE -> "$ "
            }
        }

        startTerminalSession()
    }

    private fun startTerminalSession() {
        scope.launch {
            try {
                val label = when (currentSession) {
                    SessionType.UBUNTU -> "Ubuntu proot shell"
                    SessionType.PYTHON -> "Python REPL"
                    SessionType.NATIVE -> "Android native shell"
                }
                safeAppend("Starting $label...\n")

                when (currentSession) {
                    SessionType.UBUNTU -> {
                        sessionProcess = processManager.startLoginShell(columns = 80, rows = 24)
                    }
                    SessionType.PYTHON -> {
                        sessionProcess = processManager.startProotProcess("python3 -i 2>&1")
                    }
                    SessionType.NATIVE -> {
                        // Use Android's native shell - always works, no proot needed
                        sessionProcess = Runtime.getRuntime().exec("sh")
                    }
                }

                if (sessionProcess == null) {
                    safeAppend("Failed to start $label.\n")
                    safeAppend("Falling back to native shell...\n")
                    sessionProcess = Runtime.getRuntime().exec("sh")
                    currentSession = SessionType.NATIVE
                    runOnUiThread { inputPrompt.text = "$ " }
                }

                writer = BufferedWriter(OutputStreamWriter(sessionProcess!!.outputStream))

                // Read stdout
                val stdoutReader = BufferedReader(InputStreamReader(sessionProcess!!.inputStream))
                val buffer = CharArray(4096)

                // Also read stderr in separate thread (for NATIVE session)
                val stderrThread = Thread {
                    try {
                        val errReader = BufferedReader(InputStreamReader(sessionProcess!!.errorStream))
                        val errBuf = CharArray(2048)
                        while (!isDestroyed) {
                            val count = errReader.read(errBuf)
                            if (count == -1) break
                            val text = String(errBuf, 0, count)
                            safeAppend(text)
                        }
                    } catch (e: Exception) { }
                }
                stderrThread.start()

                while (!isDestroyed) {
                    val count = stdoutReader.read(buffer)
                    if (count == -1) break
                    val text = String(buffer, 0, count)
                    safeAppend(text)
                }

                stderrThread.join(1000)
                safeAppend("\n--- Session ended ---\n")
            } catch (e: CancellationException) {
                // Scope cancelled - normal shutdown
            } catch (e: Exception) {
                Log.e(TAG, "Terminal session error", e)
                safeAppend("\nError: ${e.message}\n")
                // Try to fallback to native shell
                if (currentSession != SessionType.NATIVE) {
                    safeAppend("Falling back to native shell...\n")
                    currentSession = SessionType.NATIVE
                    runOnUiThread { inputPrompt.text = "$ " }
                    try {
                        sessionProcess = Runtime.getRuntime().exec("sh")
                        writer = BufferedWriter(OutputStreamWriter(sessionProcess!!.outputStream))
                        val reader = BufferedReader(InputStreamReader(sessionProcess!!.inputStream))
                        val buf = CharArray(4096)
                        while (!isDestroyed) {
                            val c = reader.read(buf)
                            if (c == -1) break
                            safeAppend(String(buf, 0, c))
                        }
                    } catch (e2: Exception) {
                        safeAppend("Native shell also failed: ${e2.message}\n")
                    }
                }
            }
        }
    }

    private fun safeAppend(text: String) {
        if (isDestroyed) return
        try {
            outputBuffer.append(text)
            if (outputBuffer.length > 100000) {
                outputBuffer.delete(0, 50000)
            }
            runOnUiThread {
                if (!isDestroyed && !outputView.isAttachedToWindow) return@runOnUiThread
                try {
                    outputView.text = outputBuffer.toString()
                    outputScrollView.post {
                        if (!isDestroyed) outputScrollView.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "safeAppend failed: ${e.message}")
        }
    }

    private fun appendOutput(text: String) {
        safeAppend(text)
    }

    private fun adjustFontSize(delta: Int) {
        currentFontSize = (currentFontSize + delta).coerceIn(8, 28)
        outputView.textSize = currentFontSize.toFloat()
        inputPrompt.textSize = currentFontSize.toFloat()
        inputField.textSize = currentFontSize.toFloat()
    }

    override fun onResume() {
        super.onResume()
        try { inputField.requestFocus() } catch (e: Exception) { }
    }

    override fun onPause() {
        super.onPause()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(inputField.windowToken, 0)
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        isDestroyed = true
        scope.cancel()
        try { writer?.close() } catch (e: Exception) { }
        try { sessionProcess?.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }
}
