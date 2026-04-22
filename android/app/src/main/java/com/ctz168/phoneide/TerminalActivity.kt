package com.ctz168.phoneide

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.io.*
import java.util.regex.Pattern

/**
 * TerminalActivity - Full-screen terminal emulator using proot shell.
 *
 * Uses FrameLayout for terminal_container (matching XML declaration),
 * a real shell process with stdin/stdout/stderr piping, ANSI escape
 * stripping, command history navigation, and auto-scroll.
 */
class TerminalActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TerminalActivity"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var terminalContainer: FrameLayout
    private lateinit var outputScrollView: ScrollView
    private lateinit var outputView: TextView
    private lateinit var extraKeysBar: LinearLayout
    private lateinit var inputLine: LinearLayout
    private lateinit var inputPrompt: TextView
    private lateinit var inputField: EditText
    private lateinit var btnClose: View
    private lateinit var btnFontSizeUp: View
    private lateinit var btnFontSizeDown: View

    // Modifier key states
    private var ctrlPressed = false
    private var altPressed = false
    private var shiftPressed = false

    private var currentFontSize = 13
    private var sessionProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var outputBuffer = StringBuilder()
    private var commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    // ANSI escape code pattern - matches CSI sequences like ESC[38;5;220m
    private val ansiPattern = Pattern.compile("\\x1B\\[[0-9;]*[A-Za-z]")

    // Also strip OSC sequences (ESC]...BEL or ESC]...\x1B\\)
    private val oscPattern = Pattern.compile("\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)")

    private val processManager by lazy {
        ProcessManager(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Match status bar to terminal background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.statusBarColor = android.graphics.Color.parseColor("#1A1814")
        }
        setContentView(R.layout.activity_terminal)

        initViews()
        setupInputHandling()
        startProotShell()
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

        // Base layout inside the FrameLayout container - use FrameLayout.LayoutParams
        // since terminalContainer is a FrameLayout
        val baseLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1814"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Output area with ScrollView
        outputScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = true
            scrollBarStyle = ScrollView.SCROLLBARS_INSIDE_OVERLAY
        }

        outputView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#6BC96B"))
            setBackgroundColor(Color.parseColor("#1A1814"))
            textSize = currentFontSize.toFloat()
            setPadding(12, 12, 12, 12)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            gravity = Gravity.BOTTOM
        }
        outputScrollView.addView(outputView)
        baseLayout.addView(outputScrollView)

        // Extra keys bar (Ctrl, Alt, Shift, arrows, etc.)
        extraKeysBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1C18"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(4, 4, 4, 4)
        }

        // Create extra key buttons
        val extraKeys = listOf(
            "ESC" to { sendRawKey("\u001b") },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "TAB" to { sendRawKey("\t") },
            "←" to { moveCursorLeft() },
            "↑" to { navigateHistory(-1) },
            "↓" to { navigateHistory(1) },
            "→" to { moveCursorRight() },
            "HOME" to { moveCursorHome() },
            "END" to { moveCursorEnd() }
        )

        extraKeys.forEach { (label, action) ->
            val btn = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#F5F0EB"))
                setBackgroundColor(Color.parseColor("#353230"))
                typeface = Typeface.MONOSPACE
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(8, 6, 8, 6)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = 2
                    marginEnd = 2
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            extraKeysBar.addView(btn)
        }
        baseLayout.addView(extraKeysBar)

        // Input area at bottom
        inputLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#252220"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        inputPrompt = TextView(context).apply {
            text = "root@phoneide:~# "
            setTextColor(Color.parseColor("#C4A97D"))
            typeface = Typeface.MONOSPACE
            textSize = currentFontSize.toFloat()
            setPadding(4, 0, 4, 0)
        }

        val inputScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        // MUST be EditText for proper soft keyboard input handling
        inputField = EditText(context).apply {
            setTextColor(Color.parseColor("#F5F0EB"))
            setBackgroundColor(Color.TRANSPARENT)
            typeface = Typeface.MONOSPACE
            textSize = currentFontSize.toFloat()
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setHintTextColor(Color.parseColor("#7D7068"))
            hint = "Enter command..."
        }
        inputScroll.addView(inputField)

        inputLine.addView(inputPrompt)
        inputLine.addView(inputScroll)
        baseLayout.addView(inputLine)

        terminalContainer.addView(baseLayout)

        // Handle keyboard (IME) insets to keep input field visible above keyboard.
        // On Android 11+ (targetSdk 30+), adjustResize dispatches WindowInsets
        // instead of actually resizing the window, so we must handle them manually.
        ViewCompat.setOnApplyWindowInsetsListener(terminalContainer) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // Also account for navigation bar on edge-to-edge devices
            val navHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomPadding = maxOf(imeHeight, navHeight)
            baseLayout.setPadding(0, 0, 0, bottomPadding)
            insets
        }
    }

    private fun setupInputHandling() {
        // Handle IME action (Send button on keyboard)
        inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendCommand()
                true
            } else {
                false
            }
        }

        // Handle hardware keyboard and DPAD events
        inputField.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN -> {
                    sendCommand()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    navigateHistory(-1)
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN -> {
                    navigateHistory(1)
                    true
                }
                else -> false
            }
        }

        // Show soft keyboard and request focus
        inputField.requestFocus()
        inputField.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun sendCommand() {
        val command = inputField.text.toString()
        inputField.setText("")

        if (command.isNotEmpty()) {
            commandHistory.add(command)
            historyIndex = commandHistory.size

            // Echo the command to output so user can see what was typed
            appendOutput("${inputPrompt.text}${command}\n")
        }

        // Return focus to input field and keep soft keyboard open.
        // postDelayed ensures the keyboard stays visible even when
        // shell output is being appended asynchronously.
        inputField.postDelayed({
            inputField.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 50)

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

    private fun navigateHistory(direction: Int) {
        val newIndex = historyIndex + direction
        if (newIndex in 0 until commandHistory.size) {
            historyIndex = newIndex
            inputField.setText(commandHistory[historyIndex])
            inputField.setSelection(inputField.text?.length ?: 0)
        } else if (newIndex == commandHistory.size) {
            // Past the end -> clear to empty
            historyIndex = newIndex
            inputField.setText("")
        }
    }

    @SuppressLint("SdCardPath")
    private fun startProotShell() {
        scope.launch {
            try {
                // Ensure all directories and resolv.conf are ready
                processManager.ensureDirsReady()

                appendOutput("Starting Ubuntu shell via proot...\n")

                // Calculate approximate terminal dimensions from screen width
                val metrics = resources.displayMetrics
                val approxColumns = (metrics.widthPixels / (currentFontSize * 7)).toInt().coerceAtLeast(40)
                val approxRows = 24

                // Build the login command with proper terminal size
                val cmd = processManager.buildLoginCommand(
                    columns = approxColumns,
                    rows = approxRows
                )

                val pb = ProcessBuilder(cmd)
                pb.environment().clear()
                pb.environment().putAll(processManager.prootEnv())
                // Merge stderr into stdout so we read everything from one stream
                pb.redirectErrorStream(true)

                sessionProcess = pb.start()
                writer = BufferedWriter(OutputStreamWriter(sessionProcess!!.outputStream))

                // No startup messages — suppress proot warnings and boot noise.
                // The prompt itself signals the shell is ready.

                // Read all output from the merged stdout+stderr stream
                val reader = BufferedReader(InputStreamReader(sessionProcess!!.inputStream))
                val buffer = CharArray(4096)

                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break

                    val text = String(buffer, 0, count)
                    withContext(Dispatchers.Main) {
                        appendOutput(stripAnsi(text))
                    }
                }

                withContext(Dispatchers.Main) {
                    appendOutput("\n--- Shell exited ---\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Terminal error", e)
                withContext(Dispatchers.Main) {
                    appendOutput("\nTerminal error: ${e.message}\n")
                    appendOutput("Make sure rootfs is set up correctly.\n")
                }
            }
        }
    }

    /**
     * Strip ANSI and OSC escape sequences from raw terminal output.
     * This removes color codes, cursor movement, etc. while preserving
     * printable text and newlines.
     */
    private fun stripAnsi(text: String): String {
        // First strip OSC sequences (title setting, etc.)
        var cleaned = oscPattern.matcher(text).replaceAll("")
        // Then strip CSI sequences (colors, cursor movement, etc.)
        cleaned = ansiPattern.matcher(cleaned).replaceAll("")
        // Filter out proot warnings (harmless but noisy)
        cleaned = cleaned.lines()
            .filter { line ->
                !line.contains("proot warning") &&
                !line.contains("can't sanitize")
            }
            .joinToString("\n")
        return cleaned
    }

    private fun appendOutput(text: String) {
        outputBuffer.append(text)
        // Keep buffer manageable (max 200K chars, trim oldest 100K when exceeded)
        if (outputBuffer.length > 200000) {
            outputBuffer.delete(0, 100000)
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

    // ========================
    // Extra Keys Functions
    // ========================

    private fun sendRawKey(key: String) {
        scope.launch {
            try {
                writer?.write(key)
                writer?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send key", e)
            }
        }
    }

    // Cursor movement functions (local input field)
    private fun moveCursorLeft() {
        val pos = inputField.selectionStart
        if (pos > 0) {
            inputField.setSelection(pos - 1)
        }
    }

    private fun moveCursorRight() {
        val pos = inputField.selectionStart
        val len = inputField.text?.length ?: 0
        if (pos < len) {
            inputField.setSelection(pos + 1)
        }
    }

    private fun moveCursorHome() {
        inputField.setSelection(0)
    }

    private fun moveCursorEnd() {
        val len = inputField.text?.length ?: 0
        inputField.setSelection(len)
    }

    private fun toggleCtrl() {
        ctrlPressed = !ctrlPressed
        updateModifierButtonStates()
    }

    private fun toggleAlt() {
        altPressed = !altPressed
        updateModifierButtonStates()
    }

    private fun resetModifiers() {
        ctrlPressed = false
        altPressed = false
        shiftPressed = false
        updateModifierButtonStates()
    }

    private fun updateModifierButtonStates() {
        // Update CTRL button appearance (index 1 in extraKeysBar)
        val ctrlBtn = extraKeysBar.getChildAt(1) as? TextView
        ctrlBtn?.let {
            if (ctrlPressed) {
                it.setBackgroundColor(Color.parseColor("#C4A97D"))
                it.setTextColor(Color.parseColor("#1A1814"))
            } else {
                it.setBackgroundColor(Color.parseColor("#353230"))
                it.setTextColor(Color.parseColor("#F5F0EB"))
            }
        }

        // Update ALT button appearance (index 2 in extraKeysBar)
        val altBtn = extraKeysBar.getChildAt(2) as? TextView
        altBtn?.let {
            if (altPressed) {
                it.setBackgroundColor(Color.parseColor("#C4A97D"))
                it.setTextColor(Color.parseColor("#1A1814"))
            } else {
                it.setBackgroundColor(Color.parseColor("#353230"))
                it.setTextColor(Color.parseColor("#F5F0EB"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inputField.requestFocus()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Recalculate terminal column width on rotation
        // The layout (ScrollView + input) will auto-resize via weights,
        // but we update the prompt style to reflect the new orientation.
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.d(TAG, "Switched to landscape orientation")
        } else {
            Log.d(TAG, "Switched to portrait orientation")
        }
        // Ensure the terminal container insets are recalculated
        terminalContainer.requestLayout()
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
        } catch (_: Exception) { }
        super.onDestroy()
    }
}
