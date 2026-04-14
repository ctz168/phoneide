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
    private lateinit var inputField: EditText
    private lateinit var btnClose: View
    private lateinit var btnFontSizeUp: View
    private lateinit var btnFontSizeDown: View
    private var btnSessionSwitch: View? = null

    private var currentFontSize = 13
    private var sessionProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var outputBuffer = StringBuilder()

    // Session types
    private enum class SessionType {
        UBUNTU,        // proot Ubuntu shell (default, always available)
        PYTHON,        // Python REPL inside proot
    }

    private var currentSession = SessionType.UBUNTU
    private lateinit var processManager: ProcessManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        processManager = ProcessManager(applicationContext)
        processManager.initialize()

        initViews()
        setupInputHandling()
        startTerminalSession()
    }

    private fun initViews() {
        terminalContainer = findViewById(R.id.terminal_container)
        btnClose = findViewById(R.id.btn_close_terminal)
        btnFontSizeUp = findViewById(R.id.btn_font_size_up)
        btnFontSizeDown = findViewById(R.id.btn_font_size_down)

        // Session switch button is optional
        btnSessionSwitch = findViewById<View>(R.id.btn_session_switch)

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
            text = "(ubuntu)$ "
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
            hint = "输入命令..."
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = currentFontSize.toFloat()
            setSingleLine(false)
            setMinLines(1)
            setMaxLines(5)
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(0, 0, 0, 0)
            // Handle Enter key via setOnEditorActionListener instead of setOnKeyListener
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_NULL || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    executeCommand()
                    true
                } else {
                    false
                }
            }
        }
        inputScroll.addView(inputField)

        inputLine.addView(inputPrompt)
        inputLine.addView(inputScroll)
        baseLayout.addView(inputLine)

        terminalContainer.addView(baseLayout)
    }

    private fun setupInputHandling() {
        // Enter key is handled inside buildTerminalUI via setOnEditorActionListener
        // Also add setOnKeyListener as fallback for hardware keyboards
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

    private fun executeCommand() {
        val command = inputField.text.toString().trim()
        if (command.isEmpty()) {
            appendOutput("\n")
            return
        }

        val prompt = when (currentSession) {
            SessionType.UBUNTU -> "(ubuntu)$ "
            SessionType.PYTHON -> ">>> "
        }

        appendOutput("$prompt$command\n")
        inputField.setText("")

        // Handle special commands
        when (command.lowercase()) {
            "clear", "cls" -> {
                outputBuffer.clear()
                outputView.text = ""
                return
            }
            "exit", "quit" -> {
                finish()
                return
            }
            "python", "python3" -> {
                switchSession(SessionType.PYTHON)
                return
            }
            "bash", "sh", "ubuntu" -> {
                switchSession(SessionType.UBUNTU)
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
            SessionType.UBUNTU -> "Ubuntu (proot)"
            SessionType.PYTHON -> "Python REPL"
        }
        appendOutput("\n--- 切换到 $label ---\n")
        inputPrompt.text = when (session) {
            SessionType.UBUNTU -> "(ubuntu)$ "
            SessionType.PYTHON -> ">>> "
        }

        startTerminalSession()
    }

    @SuppressLint("SdCardPath")
    private fun startTerminalSession() {
        scope.launch {
            try {
                // Pre-check: verify proot binary exists
                val prootBin = processManager.getProotBin()
                if (!java.io.File(prootBin).exists()) {
                    withContext(Dispatchers.Main) {
                        appendOutput("错误: proot 二进制文件未找到\n")
                        appendOutput("路径: $prootBin\n")
                        appendOutput("请先完成初始设置\n")
                    }
                    return@launch
                }

                // Pre-check: verify rootfs exists
                if (!processManager.isRootfsReady()) {
                    withContext(Dispatchers.Main) {
                        appendOutput("错误: Ubuntu rootfs 未安装\n")
                        appendOutput("请先完成初始设置流程\n")
                        appendOutput("或返回主界面点击设置\n")
                    }
                    return@launch
                }

                val label = when (currentSession) {
                    SessionType.UBUNTU -> "Ubuntu proot shell"
                    SessionType.PYTHON -> "Python REPL"
                }
                appendOutput("Starting $label...\n")

                when (currentSession) {
                    SessionType.UBUNTU -> {
                        // Use ProcessManager to start a login shell via proot
                        sessionProcess = processManager.startLoginShell(
                            columns = 80,
                            rows = 24
                        )
                    }
                    SessionType.PYTHON -> {
                        // Start Python REPL inside proot
                        sessionProcess = processManager.startProotProcess(
                            "python3 -i 2>&1"
                        )
                    }
                }

                if (sessionProcess == null) {
                    appendOutput("Failed to start session. Check if proot binaries are installed.\n")
                    return@launch
                }

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

                withContext(Dispatchers.Main) {
                    appendOutput("\n--- Session ended ---\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Terminal session error", e)
                withContext(Dispatchers.Main) {
                    appendOutput("\n终端错误: ${e.message}\n")
                    appendOutput("提示: 请确保 proot 二进制文件已正确安装\n")
                }
            }
        }
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
