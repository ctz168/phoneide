/**
 * TerminalManager - Code execution and output display for PhoneIDE
 * Works with Flask backend on port 1239
 */
const TerminalManager = (() => {
    'use strict';

    // ── State ──────────────────────────────────────────────────────
    let currentProcId = null;
    let isRunning = false;
    let eventSource = null;         // SSE connection
    let pollTimer = null;           // polling fallback timer
    let pollSince = 0;              // last line index seen
    let compilers = [];             // cached compiler list
    let panelHeight = 250;          // current panel height in px
    let isDragging = false;         // resize drag state
    let dragStartY = 0;            // touch/mouse Y at drag start
    let dragStartHeight = 0;        // panel height at drag start

    // ── Constants ──────────────────────────────────────────────────
    const MIN_PANEL_HEIGHT = 100;
    const MAX_PANEL_HEIGHT = window.innerHeight ? Math.floor(window.innerHeight * 0.8) : 500;
    const POLL_INTERVAL = 500;      // ms between poll requests
    const MAX_OUTPUT_LINES = 5000;  // max lines before trimming

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Escape HTML entities for safe insertion into output
     */
    function escapeHTML(str) {
        const div = document.createElement('div');
        div.textContent = str || '';
        return div.innerHTML;
    }

    /**
     * Get current timestamp string for log lines
     */
    function timestamp() {
        const now = new Date();
        return now.toLocaleTimeString();
    }

    /**
     * Trim old output lines if we exceed the maximum
     */
    function trimOutput() {
        const container = document.getElementById('output-content');
        if (!container) return;

        const lines = container.querySelectorAll('.output-line');
        if (lines.length > MAX_OUTPUT_LINES) {
            const removeCount = lines.length - MAX_OUTPUT_LINES;
            for (let i = 0; i < removeCount; i++) {
                lines[i].remove();
            }
        }
    }

    // ── API: Compilers ─────────────────────────────────────────────

    /**
     * Load available compilers from the backend and populate the select dropdown
     * @returns {Promise<Array>} list of compiler objects
     */
    async function loadCompilers() {
        try {
            const resp = await fetch('/api/compilers');
            if (!resp.ok) throw new Error(`Failed to load compilers: ${resp.statusText}`);
            const data = await resp.json();

            compilers = Array.isArray(data) ? data : (data.compilers || []);

            // Populate the compiler select
            const select = document.getElementById('compiler-select');
            if (select) {
                let html = '';
                for (const compiler of compilers) {
                    const name = compiler.name || compiler.label || compiler.id || compiler;
                    const value = compiler.id || compiler.value || compiler.name || compiler;
                    const selected = compiler.default ? ' selected' : '';
                    html += `<option value="${escapeHTML(value)}"${selected}>${escapeHTML(name)}</option>`;
                }
                // If no compilers loaded, add a default option
                if (compilers.length === 0) {
                    html = '<option value="auto">Auto-detect</option>';
                }
                select.innerHTML = html;
            }

            return compilers;
        } catch (err) {
            showToast(`Failed to load compilers: ${err.message}`, 'error');
            return [];
        }
    }

    /**
     * Get the currently selected compiler
     * @returns {string} compiler identifier
     */
    function getSelectedCompiler() {
        const select = document.getElementById('compiler-select');
        return select ? select.value : 'auto';
    }

    // ── API: Execute ───────────────────────────────────────────────

    /**
     * Execute a file with the given compiler and arguments
     * @param {string} file_path - path to the file to execute
     * @param {string} [compiler] - compiler to use (defaults to selected)
     * @param {string} [args] - command-line arguments
     * @returns {Promise<object>} execution result
     */
    async function execute(file_path, compiler, args) {
        if (isRunning) {
            showToast('A process is already running', 'warning');
            return { error: 'Process already running' };
        }

        if (!file_path) {
            showToast('No file specified for execution', 'warning');
            return { error: 'No file specified' };
        }

        compiler = compiler || getSelectedCompiler();

        // Ensure panel is visible
        showPanel();

        try {
            appendOutput(`$ Running ${file_path} [${compiler}]...`, 'status');

            const body = { file_path, compiler };
            if (args) body.args = args;

            const resp = await fetch('/api/run/execute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });

            if (!resp.ok) throw new Error(`Execution failed: ${resp.statusText}`);

            const data = await resp.json();

            currentProcId = data.proc_id || data.process_id || data.id || null;
            pollSince = 0;

            if (currentProcId) {
                setRunningState(true);
                streamOutput(currentProcId);
            } else {
                // No process ID — output may be included directly
                if (data.output) {
                    appendOutput(data.output, 'stdout');
                }
                if (data.stderr) {
                    appendOutput(data.stderr, 'stderr');
                }
                if (data.error) {
                    appendOutput(data.error, 'error');
                }
                if (data.exit_code !== undefined) {
                    const code = data.exit_code;
                    const type = code === 0 ? 'info' : 'error';
                    appendOutput(`Process exited with code ${code}`, type);
                }
            }

            return data;
        } catch (err) {
            appendOutput(`Error: ${err.message}`, 'error');
            showToast(`Execution error: ${err.message}`, 'error');
            return { error: err.message };
        }
    }

    /**
     * Execute code directly (without saving to a file first)
     * @param {string} code - source code to execute
     * @param {string} [compiler] - compiler/language to use
     * @returns {Promise<object>} execution result
     */
    async function executeCode(code, compiler) {
        if (isRunning) {
            showToast('A process is already running', 'warning');
            return { error: 'Process already running' };
        }

        if (!code || !code.trim()) {
            showToast('No code to execute', 'warning');
            return { error: 'No code provided' };
        }

        compiler = compiler || getSelectedCompiler();

        showPanel();

        try {
            appendOutput(`$ Executing code [${compiler}]...`, 'status');

            const body = { code, compiler };

            const resp = await fetch('/api/run/execute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });

            if (!resp.ok) throw new Error(`Execution failed: ${resp.statusText}`);

            const data = await resp.json();

            currentProcId = data.proc_id || data.process_id || data.id || null;
            pollSince = 0;

            if (currentProcId) {
                setRunningState(true);
                streamOutput(currentProcId);
            } else {
                if (data.output) appendOutput(data.output, 'stdout');
                if (data.stderr) appendOutput(data.stderr, 'stderr');
                if (data.error) appendOutput(data.error, 'error');
                if (data.exit_code !== undefined) {
                    const code2 = data.exit_code;
                    appendOutput(`Process exited with code ${code2}`, code2 === 0 ? 'info' : 'error');
                }
            }

            return data;
        } catch (err) {
            appendOutput(`Error: ${err.message}`, 'error');
            showToast(`Execution error: ${err.message}`, 'error');
            return { error: err.message };
        }
    }

    // ── API: Stop ──────────────────────────────────────────────────

    /**
     * Stop the currently running process
     * @param {string} [procId] - process ID (defaults to current)
     * @returns {Promise<object>} stop result
     */
    async function stop(procId) {
        procId = procId || currentProcId;

        if (!procId) {
            showToast('No process running', 'warning');
            return { error: 'No process to stop' };
        }

        try {
            appendOutput(`Stopping process ${procId}...`, 'status');

            const resp = await fetch('/api/run/stop', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ proc_id: procId })
            });

            if (!resp.ok) throw new Error(`Stop failed: ${resp.statusText}`);

            const data = await resp.json();
            appendOutput('Process stopped', 'status');
            cleanupProcess();
            showToast('Process stopped', 'info');

            return data;
        } catch (err) {
            appendOutput(`Stop error: ${err.message}`, 'error');
            showToast(`Stop error: ${err.message}`, 'error');
            return { error: err.message };
        }
    }

    // ── Output Streaming ───────────────────────────────────────────

    /**
     * Stream output using Server-Sent Events (SSE)
     * Falls back to polling if SSE is not available or fails
     * @param {string} procId - process ID to stream output for
     */
    function streamOutput(procId) {
        // Close any existing SSE connection
        closeEventSource();
        stopPolling();

        try {
            const url = `/api/run/output/stream?proc_id=${encodeURIComponent(procId)}`;
            eventSource = new EventSource(url);

            eventSource.onopen = () => {
                // SSE connection established
            };

            eventSource.addEventListener('stdout', (e) => {
                appendOutput(e.data, 'stdout');
            });

            eventSource.addEventListener('stderr', (e) => {
                appendOutput(e.data, 'stderr');
            });

            eventSource.addEventListener('error', (e) => {
                if (e.data) {
                    appendOutput(e.data, 'error');
                }
            });

            eventSource.addEventListener('status', (e) => {
                appendOutput(e.data, 'status');
            });

            eventSource.addEventListener('exit', (e) => {
                const exitCode = e.data;
                const type = parseInt(exitCode, 10) === 0 ? 'info' : 'error';
                appendOutput(`Process exited with code ${exitCode}`, type);
                cleanupProcess();
            });

            eventSource.addEventListener('done', (e) => {
                appendOutput(e.data || 'Done.', 'info');
                cleanupProcess();
            });

            eventSource.onerror = () => {
                // SSE failed — fall back to polling
                closeEventSource();
                if (isRunning && currentProcId) {
                    startPolling(currentProcId);
                }
            };

        } catch (err) {
            // EventSource not supported or failed to connect
            closeEventSource();
            if (isRunning && currentProcId) {
                startPolling(currentProcId);
            }
        }
    }

    /**
     * Close the SSE EventSource connection
     */
    function closeEventSource() {
        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }
    }

    // ── Output Polling (Fallback) ──────────────────────────────────

    /**
     * Start polling for output (fallback when SSE is unavailable)
     * @param {string} procId - process ID to poll
     */
    function startPolling(procId) {
        stopPolling();
        pollOutput(procId);
    }

    /**
     * Poll the output endpoint for new lines
     * @param {string} procId - process ID
     */
    async function pollOutput(procId) {
        if (!procId || !isRunning) return;

        try {
            const url = `/api/run/output?proc_id=${encodeURIComponent(procId)}&since=${pollSince}`;
            const resp = await fetch(url);

            if (!resp.ok) {
                // Process may have ended
                if (resp.status === 404 || resp.status === 410) {
                    appendOutput('Process completed', 'info');
                    cleanupProcess();
                    return;
                }
                throw new Error(`Poll failed: ${resp.statusText}`);
            }

            const data = await resp.json();

            // Process output lines
            const lines = data.lines || data.output || [];
            if (Array.isArray(lines)) {
                for (const line of lines) {
                    const text = typeof line === 'string' ? line : (line.text || line.content || '');
                    const type = typeof line === 'object' ? (line.type || line.stream || 'stdout') : 'stdout';
                    appendOutput(text, type);
                    pollSince++;
                }
            } else if (typeof lines === 'string' && lines.trim()) {
                appendOutput(lines, 'stdout');
                pollSince++;
            }

            // Check if process has exited
            if (data.exited || data.done || data.finished) {
                const exitCode = data.exit_code !== undefined ? data.exit_code : 0;
                const type = parseInt(exitCode, 10) === 0 ? 'info' : 'error';
                appendOutput(`Process exited with code ${exitCode}`, type);
                cleanupProcess();
                return;
            }

            // Continue polling if still running
            if (isRunning && currentProcId) {
                pollTimer = setTimeout(() => pollOutput(procId), POLL_INTERVAL);
            }

        } catch (err) {
            // On error, retry a few times then give up
            console.warn('Output poll error:', err.message);
            if (isRunning && currentProcId) {
                pollTimer = setTimeout(() => pollOutput(procId), POLL_INTERVAL * 2);
            }
        }
    }

    /**
     * Stop the polling timer
     */
    function stopPolling() {
        if (pollTimer) {
            clearTimeout(pollTimer);
            pollTimer = null;
        }
    }

    // ── Process State Management ───────────────────────────────────

    /**
     * Set the running state and update UI buttons
     * @param {boolean} running - true if a process is running
     */
    function setRunningState(running) {
        isRunning = running;

        const runBtn = document.getElementById('btn-run');
        const stopBtn = document.getElementById('btn-stop');

        if (runBtn) {
            runBtn.style.display = running ? 'none' : '';
        }
        if (stopBtn) {
            stopBtn.style.display = running ? '' : 'none';
        }
    }

    /**
     * Clean up after a process finishes
     */
    function cleanupProcess() {
        closeEventSource();
        stopPolling();
        currentProcId = null;
        pollSince = 0;
        setRunningState(false);
    }

    // ── Output Display ─────────────────────────────────────────────

    /**
     * Append a line of text to the output panel
     * @param {string} text - the text to append
     * @param {string} [type='stdout'] - type class: stdout, stderr, error, status, info
     */
    function appendOutput(text, type) {
        const container = document.getElementById('output-content');
        if (!container) return;

        type = type || 'stdout';

        const line = document.createElement('div');
        line.className = `output-line ${type}`;
        line.textContent = text || '';
        container.appendChild(line);

        // Trim if too many lines
        trimOutput();

        // Auto-scroll to bottom
        container.scrollTop = container.scrollHeight;
    }

    /**
     * Clear all output from the output panel
     */
    function clearOutput() {
        const container = document.getElementById('output-content');
        if (container) {
            container.innerHTML = '';
        }
        pollSince = 0;
    }

    // ── Panel Management ───────────────────────────────────────────

    /**
     * Show the bottom panel
     */
    function showPanel() {
        const panel = document.getElementById('bottom-panel');
        if (panel) {
            panel.style.display = '';
            panel.classList.add('visible');
            panel.style.height = panelHeight + 'px';
        }
    }

    /**
     * Hide the bottom panel
     */
    function hidePanel() {
        const panel = document.getElementById('bottom-panel');
        if (panel) {
            panel.style.display = 'none';
            panel.classList.remove('visible');
        }
    }

    /**
     * Toggle the bottom panel visibility
     */
    function togglePanel() {
        const panel = document.getElementById('bottom-panel');
        if (!panel) return;

        if (panel.style.display === 'none' || !panel.classList.contains('visible')) {
            showPanel();
        } else {
            hidePanel();
        }
    }

    /**
     * Set the panel height and persist it
     * @param {number} height - new height in pixels
     */
    function setPanelHeight(height) {
        height = Math.max(MIN_PANEL_HEIGHT, Math.min(MAX_PANEL_HEIGHT, height));
        panelHeight = height;

        const panel = document.getElementById('bottom-panel');
        if (panel) {
            panel.style.height = panelHeight + 'px';
        }
    }

    // ── Resize Handling ────────────────────────────────────────────

    /**
     * Initialize touch/mouse drag resize for the bottom panel
     */
    function initResize() {
        const handle = document.getElementById('bottom-panel-resize');
        if (!handle) return;

        // ── Touch events ──
        handle.addEventListener('touchstart', (e) => {
            e.preventDefault();
            isDragging = true;
            dragStartY = e.touches[0].clientY;
            dragStartHeight = panelHeight;
            handle.classList.add('active');
            document.body.classList.add('panel-resizing');
        }, { passive: false });

        document.addEventListener('touchmove', (e) => {
            if (!isDragging) return;
            e.preventDefault();
            const currentY = e.touches[0].clientY;
            const delta = dragStartY - currentY; // Dragging up increases height
            setPanelHeight(dragStartHeight + delta);
        }, { passive: false });

        document.addEventListener('touchend', () => {
            if (isDragging) {
                isDragging = false;
                const handle = document.getElementById('bottom-panel-resize');
                if (handle) handle.classList.remove('active');
                document.body.classList.remove('panel-resizing');
            }
        });

        // ── Mouse events (desktop) ──
        handle.addEventListener('mousedown', (e) => {
            e.preventDefault();
            isDragging = true;
            dragStartY = e.clientY;
            dragStartHeight = panelHeight;
            handle.classList.add('active');
            document.body.classList.add('panel-resizing');
        });

        document.addEventListener('mousemove', (e) => {
            if (!isDragging) return;
            e.preventDefault();
            const currentY = e.clientY;
            const delta = dragStartY - currentY;
            setPanelHeight(dragStartHeight + delta);
        });

        document.addEventListener('mouseup', () => {
            if (isDragging) {
                isDragging = false;
                const handle = document.getElementById('bottom-panel-resize');
                if (handle) handle.classList.remove('active');
                document.body.classList.remove('panel-resizing');
            }
        });
    }

    // ── Wire Up ────────────────────────────────────────────────────

    function wireEvents() {
        // Close panel button
        const closeBtn = document.getElementById('bottom-panel-close');
        if (closeBtn) {
            closeBtn.addEventListener('click', (e) => {
                e.preventDefault();
                hidePanel();
            });
        }

        // Clear output button
        const clearBtn = document.getElementById('bottom-panel-clear');
        if (clearBtn) {
            clearBtn.addEventListener('click', (e) => {
                e.preventDefault();
                clearOutput();
            });
        }

        // Run button
        const runBtn = document.getElementById('btn-run');
        if (runBtn) {
            runBtn.addEventListener('click', (e) => {
                e.preventDefault();

                // Determine what to run: current file or ask user
                const filePath = window.FileManager ? window.FileManager.currentFilePath : null;

                if (filePath) {
                    execute(filePath);
                } else {
                    showToast('No file open to run', 'warning');
                }
            });
        }

        // Stop button
        const stopBtn = document.getElementById('btn-stop');
        if (stopBtn) {
            stopBtn.addEventListener('click', (e) => {
                e.preventDefault();
                stop();
            });
            // Initially hidden (no process running)
            stopBtn.style.display = 'none';
        }

        // Initialize resize handle
        initResize();

        // ── Shell Input Bar ──
        initShellInput();

        // ── Terminal Extra Keys ──
        initExtraKeys();
    }

    // ── Shell Input Bar ──────────────────────────────────────────

    let shellHistory = [];
    let shellHistoryIndex = -1;

    function initShellInput() {
        const shellInput = document.getElementById('shell-input');
        if (!shellInput) return;

        shellInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                const cmd = shellInput.value.trim();
                if (!cmd) return;

                // Add to history
                shellHistory.push(cmd);
                shellHistoryIndex = shellHistory.length;

                // Show the command in output
                appendOutput(`$ ${cmd}`, 'status');

                // Execute via API
                executeCode(cmd, 'bash');
                shellInput.value = '';
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                if (shellHistoryIndex > 0) {
                    shellHistoryIndex--;
                    shellInput.value = shellHistory[shellHistoryIndex] || '';
                }
            } else if (e.key === 'ArrowDown') {
                e.preventDefault();
                if (shellHistoryIndex < shellHistory.length - 1) {
                    shellHistoryIndex++;
                    shellInput.value = shellHistory[shellHistoryIndex] || '';
                } else {
                    shellHistoryIndex = shellHistory.length;
                    shellInput.value = '';
                }
            } else if (e.key === 'Tab') {
                e.preventDefault();
                // Simple tab completion - not full, but inserts a tab character
                const start = shellInput.selectionStart;
                const end = shellInput.selectionEnd;
                shellInput.value = shellInput.value.substring(0, start) + '\t' + shellInput.value.substring(end);
                shellInput.selectionStart = shellInput.selectionEnd = start + 1;
            } else if (e.key === 'c' && e.ctrlKey) {
                e.preventDefault();
                appendOutput('^C', 'status');
                stop();
            } else if (e.key === 'l' && e.ctrlKey) {
                e.preventDefault();
                clearOutput();
            }
        });

        // Focus shell input when clicking on output area
        const outputContent = document.getElementById('output-content');
        if (outputContent) {
            outputContent.addEventListener('click', () => {
                shellInput.focus();
            });
        }
    }

    // ── Terminal Extra Keys ──────────────────────────────────────

    let ctrlActive = false;

    function initExtraKeys() {
        const keysBar = document.getElementById('terminal-extra-keys');
        if (!keysBar) return;

        const keyMap = {
            'esc': '\x1b',
            'tab': '\t',
            'up': '\x1b[A',
            'down': '\x1b[B',
            'left': '\x1b[D',
            'right': '\x1b[C',
            'home': '\x1b[H',
            'end': '\x1b[F',
            'pgup': '\x1b[5~',
            'pgdn': '\x1b[6~',
            'pipe': '|',
            'slash': '/',
            'tilde': '~',
            'minus': '-',
            'enter': '\r',
        };

        keysBar.querySelectorAll('.tkey').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.preventDefault();
                const key = btn.dataset.key;
                const shellInput = document.getElementById('shell-input');
                if (!shellInput) return;

                if (key === 'ctrl') {
                    ctrlActive = !ctrlActive;
                    btn.classList.toggle('active', ctrlActive);
                    return;
                }

                // If CTRL is active and key is a single letter, send Ctrl+key
                if (ctrlActive && key.length === 1 && key >= 'a' && key <= 'z') {
                    // Insert the control character into the shell input
                    const ctrlChar = String.fromCharCode(key.charCodeAt(0) - 96);
                    const pos = shellInput.selectionStart;
                    shellInput.value = shellInput.value.substring(0, pos) + ctrlChar + shellInput.value.substring(shellInput.selectionEnd);
                    shellInput.selectionStart = shellInput.selectionEnd = pos + 1;
                    ctrlActive = false;
                    const ctrlBtn = keysBar.querySelector('[data-key="ctrl"]');
                    if (ctrlBtn) ctrlBtn.classList.remove('active');
                    return;
                }

                // For escape sequences that should trigger shell input behaviors
                if (key === 'up' || key === 'down') {
                    // Simulate arrow key for history navigation
                    const event = new KeyboardEvent('keydown', {
                        key: key === 'up' ? 'ArrowUp' : 'ArrowDown',
                        bubbles: true
                    });
                    shellInput.dispatchEvent(event);
                    return;
                }

                if (key === 'enter') {
                    const event = new KeyboardEvent('keydown', {
                        key: 'Enter',
                        bubbles: true
                    });
                    shellInput.dispatchEvent(event);
                    return;
                }

                // For other keys, insert the character
                const char = keyMap[key];
                if (char) {
                    const pos = shellInput.selectionStart;
                    shellInput.value = shellInput.value.substring(0, pos) + char + shellInput.value.substring(shellInput.selectionEnd);
                    shellInput.selectionStart = shellInput.selectionEnd = pos + char.length;
                    shellInput.focus();
                }
            });
        });
    }

    // ── Initialize ─────────────────────────────────────────────────

    function init() {
        wireEvents();
        loadCompilers();

        // Ensure initial panel state
        setRunningState(false);
    }

    // Auto-init when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // ── API: Venv ─────────────────────────────────────────────────

    /**
     * Load venv info from backend and update UI
     */
    async function loadVenvInfo() {
        try {
            // Get venv list
            const resp = await fetch('/api/venv/list');
            if (!resp.ok) throw new Error(`Failed to load venv: ${resp.statusText}`);
            const data = await resp.json();

            const currentVenvEl = document.getElementById('current-venv');
            if (currentVenvEl) {
                if (data.current) {
                    const name = data.current.split('/').pop();
                    currentVenvEl.textContent = name;
                } else {
                    currentVenvEl.textContent = '未设置';
                }
            }

            return data;
        } catch (err) {
            console.warn('Failed to load venv info:', err.message);
            return null;
        }
    }

    /**
     * Create a virtual environment
     */
    async function createVenv(path) {
        if (!path) {
            if (window.showPromptDialog) {
                path = await new Promise(resolve => {
                    window.showPromptDialog('创建虚拟环境', '输入路径 (留空使用默认 .venv):', '.venv', resolve);
                });
            } else {
                path = prompt('Enter venv path:', '.venv');
            }
        }
        if (!path) return;

        showPanel();
        appendOutput('$ Creating virtual environment...', 'status');

        try {
            const resp = await fetch('/api/venv/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ path })
            });
            if (!resp.ok) throw new Error(`Failed to create venv: ${resp.statusText}`);

            const data = await resp.json();
            if (data.proc_id) {
                currentProcId = data.proc_id;
                pollSince = 0;
                setRunningState(true);
                streamOutput(data.proc_id);
            } else {
                appendOutput('Virtual environment created.', 'info');
                showToast('虚拟环境已创建', 'success');
            }

            // Refresh venv info
            await loadVenvInfo();
            return data;
        } catch (err) {
            appendOutput(`Error: ${err.message}`, 'error');
            showToast(`创建失败: ${err.message}`, 'error');
            return { error: err.message };
        }
    }

    /**
     * Install a Python package
     */
    async function installPackage(packageName) {
        if (!packageName) {
            if (window.showPromptDialog) {
                packageName = await new Promise(resolve => {
                    window.showPromptDialog('安装包', '输入包名:', '', resolve);
                });
            } else {
                packageName = prompt('Enter package name:');
            }
        }
        if (!packageName) return;

        showPanel();
        appendOutput(`$ pip install ${packageName}...`, 'status');

        try {
            const resp = await fetch('/api/run/execute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    code: `import subprocess; result = subprocess.run(['pip', 'install', '${packageName}'], capture_output=True, text=True); print(result.stdout); print(result.stderr)`,
                    compiler: 'python3'
                })
            });
            if (!resp.ok) throw new Error(`Failed: ${resp.statusText}`);

            const data = await resp.json();
            if (data.proc_id) {
                currentProcId = data.proc_id;
                pollSince = 0;
                setRunningState(true);
                streamOutput(data.proc_id);
            }
            return data;
        } catch (err) {
            appendOutput(`Error: ${err.message}`, 'error');
            showToast(`安装失败: ${err.message}`, 'error');
            return { error: err.message };
        }
    }

    // ── Public API ─────────────────────────────────────────────────
    return {
        execute,
        executeCode,
        stop,
        togglePanel,
        clearOutput,
        streamOutput,
        appendOutput,
        pollOutput,
        loadCompilers,
        getSelectedCompiler,
        showPanel,
        hidePanel,
        loadVenvInfo,
        createVenv,
        installPackage,

        // Getters
        get currentProcId() { return currentProcId; },
        get isRunning() { return isRunning; },
        get compilers() { return compilers; },
        get panelHeight() { return panelHeight; },
        set panelHeight(v) { setPanelHeight(v); }
    };
})();

// Also expose as window.TerminalManager for external access
window.TerminalManager = TerminalManager;
