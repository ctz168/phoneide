package com.phoneide

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.atomic.AtomicInteger

class TerminalActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TerminalActivity"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var terminalContainer: LinearLayout
    private lateinit var outputScrollView: ScrollView
    private lateinit var outputView: TextView
    private lateinit var inputLine: LinearLayout
    private lateinit var inputPrompt: TextView
    private lateinit var inputField: TextView
    private lateinit var btnClose: View
    private lateinit var btnFontSizeUp: View
    private lateinit var btnFontSizeDown: View

    private var currentFontSize = 13
    private var sessionProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var outputBuffer = StringBuilder()

    // Session types
    private enum class SessionType {
        TERMUX,        // Termux shell
        UBUNTU,        // proot Ubuntu shell
        PYTHON,        // Python REPL
    }

    private var currentSession = SessionType.TERMUX

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        initViews()
        setupInputHandling()
        startTerminalSession()
    }

    private fun initViews() {
        terminalContainer = findViewById(R.id.terminal_container)
        btnClose = findViewById(R.id.btn_close_terminal)
        btnFontSizeUp = findViewById(R.id.btn_font_size_up)
        btnFontSizeDown = findViewById(R.id.btn_font_size_down)

        btnClose.setOnClickListener { finish() }
        btnFontSizeUp.setOnClickListener { adjustFontSize(1) }
        btnFontSizeDown.setOnClickListener { adjustFontSize(-1) }

        buildTerminalUI()
    }

    private fun buildTerminalUI() {
        terminalContainer.removeAllViews()

        val context = this
        val baseLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
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
            gravity = Gravity.BOTTOM
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

        inputField = TextView(context).apply {
            setTextColor(Color.parseColor("#C9D1D9"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = currentFontSize.toFloat()
            setSingleLine(false)
            setMinLines(1)
            setMaxLines(5)
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHintTextColor(Color.parseColor("#484F58"))
        }
        inputScroll.addView(inputField)

        inputLine.addView(inputPrompt)
        inputLine.addView(inputScroll)
        baseLayout.addView(inputLine)

        terminalContainer.addView(baseLayout)
    }

    private fun setupInputHandling() {
        inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                executeCommand()
                true
            } else {
                false
            }
        }

        // Handle Enter key - we need a custom approach since TextView doesn't have a good Enter key handler
        inputField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                executeCommand()
                true
            } else {
                false
            }
        }

        // Make inputField focusable and handle text input
        inputField.isFocusable = true
        inputField.isFocusableInTouchMode = true
        inputField.requestFocus()

        inputField.setOnClickListener {
            // Ensure soft keyboard shows
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun executeCommand() {
        val command = inputField.text.toString().trim()
        if (command.isEmpty()) {
            appendOutput("\n")
            return
        }

        val prompt = when (currentSession) {
            SessionType.TERMUX -> "$ "
            SessionType.UBUNTU -> "(ubuntu)$ "
            SessionType.PYTHON, SessionType.UBUNTU -> ">>> "
        }

        appendOutput("$prompt$command\n")
        inputField.text = ""

        // Handle special commands
        when (command.lowercase()) {
            "clear", "cls" -> {
                outputBuffer.clear()
                outputView.text = ""
                return
            }
            "exit", "quit" -> {
                if (currentSession != SessionType.TERMUX) {
                    switchSession(SessionType.TERMUX)
                    return
                } else {
                    finish()
                    return
                }
            }
            "ubuntu" -> {
                switchSession(SessionType.UBUNTU)
                return
            }
            "python", "python3" -> {
                switchSession(SessionType.PYTHON)
                return
            }
        }

        // Send to process
        scope.launch {
            try {
                writer?.write("$command\n")
                writer?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("Error: ${e.message}\n")
                }
            }
        }
    }

    private fun switchSession(session: SessionType) {
        // Kill current session
        try {
            writer?.close()
            sessionProcess?.destroy()
        } catch (e: Exception) { }

        currentSession = session
        val label = when (session) {
            SessionType.TERMUX -> "Termux Shell"
            SessionType.UBUNTU -> "Ubuntu (proot)"
            SessionType.PYTHON -> "Python REPL"
        }
        appendOutput("\n--- 切换到 $label ---\n")
        inputPrompt.text = when (session) {
            SessionType.TERMUX -> "$ "
            SessionType.UBUNTU -> "(ubuntu)$ "
            SessionType.PYTHON -> ">>> "
        }

        startTerminalSession()
    }

    @SuppressLint("SdCardPath")
    private fun startTerminalSession() {
        scope.launch {
            try {
                val command = when (currentSession) {
                    SessionType.TERMUX -> buildTermuxCommand()
                    SessionType.UBUNTU -> buildUbuntuCommand()
                    SessionType.PYTHON -> buildPythonCommand()
                }

                appendOutput("Starting ${currentSession.name}...\n")

                val processBuilder = ProcessBuilder(*command)
                processBuilder.redirectErrorStream(true)
                sessionProcess = processBuilder.start()

                writer = BufferedWriter(OutputStreamWriter(sessionProcess!!.outputStream))

                // Read output
                val reader = BufferedReader(InputStreamReader(sessionProcess!!.inputStream))
                val buffer = CharArray(4096)

                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break

                    val text = String(buffer, 0, count)
                    withContext(Dispatchers.Main) {
                        appendOutput(text)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Terminal session error", e)
                withContext(Dispatchers.Main) {
                    appendOutput("\n终端错误: ${e.message}\n")
                    appendOutput("提示: 请确保 Termux 已安装并已运行过一次\n")
                    appendOutput("尝试切换到 Ubuntu 模式: 输入 'ubuntu'\n")
                }
            }
        }
    }

    @SuppressLint("SdCardPath")
    private fun buildTermuxCommand(): Array<String> {
        return arrayOf(
            "/data/data/com.termux/files/usr/bin/sh",
            "-c",
            "export HOME=/data/data/com.termux/files/home; " +
            "export TMPDIR=/data/data/com.termux/files/usr/tmp; " +
            "export PREFIX=/data/data/com.termux/files/usr; " +
            "export PATH=/data/data/com.termux/files/usr/bin:/usr/bin:/bin; " +
            "export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib; " +
            "exec sh"
        )
    }

    @SuppressLint("SdCardPath")
    private fun buildUbuntuCommand(): Array<String> {
        return arrayOf(
            "/data/data/com.termux/files/usr/bin/sh",
            "-c",
            "exec proot-distro login ubuntu --shared-tmp -- bash"
        )
    }

    @SuppressLint("SdCardPath")
    private fun buildPythonCommand(): Array<String> {
        return arrayOf(
            "/data/data/com.termux/files/usr/bin/sh",
            "-c",
            "export HOME=/data/data/com.termux/files/home; " +
            "export TMPDIR=/data/data/com.termux/files/usr/tmp; " +
            "export PREFIX=/data/data/com.termux/files/usr; " +
            "export PATH=/data/data/com.termux/files/usr/bin:/usr/bin:/bin; " +
            "exec python3 -i"
        )
    }

    private fun appendOutput(text: String) {
        outputBuffer.append(text)
        // Keep buffer manageable
        if (outputBuffer.length > 100000) {
            outputBuffer.delete(0, 50000)
        }
        outputView.text = outputBuffer.toString()

        // Auto-scroll to bottom
        outputScrollView.post {
            outputScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun adjustFontSize(delta: Int) {
        currentFontSize = (currentFontSize + delta).coerceIn(8, 28)
        outputView.textSize = currentFontSize.toFloat()
        inputPrompt.textSize = currentFontSize.toFloat()
        inputField.textSize = currentFontSize.toFloat()
    }

    override fun onResume() {
        super.onResume()
        inputField.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(inputField.windowToken, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            writer?.close()
            sessionProcess?.destroy()
        } catch (e: Exception) { }
        super.onDestroy()
    }
}
