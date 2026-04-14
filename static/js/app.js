/* PhoneIDE - Main Application Entry */
// ── App Manager ──
const AppManager = (() => {
    let initialized = false;

    // ── Toast Notification ──
    function showToast(message, type = 'info', duration = 2500) {
        const toast = document.getElementById('toast');
        if (!toast) return;
        toast.textContent = message;
        toast.className = 'show';
        if (type) toast.classList.add(type);
        clearTimeout(toast._timer);
        toast._timer = setTimeout(() => {
            toast.className = 'hidden';
        }, duration);
    }
    window.showToast = showToast;

    // ── Dialog ──
    function showDialog(title, bodyHTML, buttons = []) {
        return new Promise((resolve) => {
            const overlay = document.getElementById('dialog-overlay');
            const dialogTitle = document.getElementById('dialog-title');
            const dialogBody = document.getElementById('dialog-body');
            const dialogButtons = document.getElementById('dialog-buttons');

            dialogTitle.textContent = title;
            dialogBody.innerHTML = bodyHTML;
            dialogButtons.innerHTML = '';

            buttons.forEach(btn => {
                const el = document.createElement('button');
                el.textContent = btn.text;
                el.className = btn.class || '';
                el.onclick = () => {
                    overlay.classList.add('hidden');
                    const input = dialogBody.querySelector('input, textarea, select');
                    resolve({ confirmed: btn.value === 'ok', value: input ? input.value : undefined });
                };
                dialogButtons.appendChild(el);
            });

            overlay.classList.remove('hidden');

            // Focus first input
            setTimeout(() => {
                const input = dialogBody.querySelector('input, textarea');
                if (input) input.focus();
            }, 100);

            // Close on overlay click
            overlay.onclick = (e) => {
                if (e.target === overlay) {
                    overlay.classList.add('hidden');
                    resolve({ confirmed: false });
                }
            };
        });
    }

    function showPromptDialog(title, placeholder = '', defaultValue = '') {
        return showDialog(title,
            `<input type="text" placeholder="${escapeHTML(placeholder)}" value="${escapeHTML(defaultValue)}" autocomplete="off">`,
            [
                { text: '取消', value: 'cancel', class: 'btn-cancel' },
                { text: '确定', value: 'ok', class: 'btn-confirm' },
            ]
        );
    }

    function showConfirmDialog(title, message) {
        return showDialog(title,
            `<p style="color:var(--text-secondary);font-size:13px;line-height:1.5;">${escapeHTML(message)}</p>`,
            [
                { text: '取消', value: 'cancel', class: 'btn-cancel' },
                { text: '确定', value: 'ok', class: 'btn-confirm' },
            ]
        );
    }

    function showInputDialog(title, fields) {
        // fields: [{name, label, type, placeholder, value, options}]
        let html = '';
        fields.forEach(f => {
            html += `<label style="display:block;font-size:12px;color:var(--text-secondary);margin-top:8px;">${escapeHTML(f.label)}</label>`;
            if (f.type === 'select' && f.options) {
                html += `<select name="${f.name}" style="width:100%;padding:8px;border:1px solid var(--border);background:var(--bg-tertiary);color:var(--text-primary);border-radius:var(--radius-sm);font-size:13px;margin-top:4px;">`;
                f.options.forEach(opt => {
                    const sel = opt.value === f.value ? ' selected' : '';
                    html += `<option value="${escapeHTML(opt.value)}"${sel}>${escapeHTML(opt.label || opt.value)}</option>`;
                });
                html += '</select>';
            } else if (f.type === 'textarea') {
                html += `<textarea name="${f.name}" placeholder="${escapeHTML(f.placeholder || '')}" rows="${f.rows || 3}" style="width:100%;padding:8px;border:1px solid var(--border);background:var(--bg-tertiary);color:var(--text-primary);border-radius:var(--radius-sm);font-size:13px;font-family:var(--font-mono);resize:vertical;margin-top:4px;">${escapeHTML(f.value || '')}</textarea>`;
            } else {
                html += `<input type="${f.type || 'text'}" name="${f.name}" placeholder="${escapeHTML(f.placeholder || '')}" value="${escapeHTML(f.value || '')}" style="width:100%;padding:8px;border:1px solid var(--border);background:var(--bg-tertiary);color:var(--text-primary);border-radius:var(--radius-sm);font-size:13px;font-family:var(--font-mono);margin-top:4px;" autocomplete="off">`;
            }
        });
        return showDialog(title, html, [
            { text: '取消', value: 'cancel', class: 'btn-cancel' },
            { text: '确定', value: 'ok', class: 'btn-confirm' },
        ]).then(result => {
            if (!result.confirmed) return null;
            const body = document.getElementById('dialog-body');
            const values = {};
            fields.forEach(f => {
                const el = body.querySelector(`[name="${f.name}"]`);
                values[f.name] = el ? el.value : '';
            });
            return values;
        });
    }

    window.showPromptDialog = showPromptDialog;
    window.showConfirmDialog = showConfirmDialog;
    window.showInputDialog = showInputDialog;
    window.showDialog = showDialog;

    // ── Utility Functions ──
    function escapeHTML(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }
    window.escapeHTML = escapeHTML;

    function escapeAttr(str) {
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }
    window.escapeAttr = escapeAttr;

    function debounce(fn, delay) {
        let timer;
        return function (...args) {
            clearTimeout(timer);
            timer = setTimeout(() => fn.apply(this, args), delay);
        };
    }
    window.debounce = debounce;

    function formatFileSize(bytes) {
        if (bytes === 0) return '';
        if (bytes < 1024) return bytes + 'B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'K';
        return (bytes / (1024 * 1024)).toFixed(1) + 'M';
    }
    window.formatFileSize = formatFileSize;

    function formatTime(isoString) {
        if (!isoString) return '';
        const d = new Date(isoString);
        return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    }
    window.formatTime = formatTime;

    // ── Sidebar Management ──
    const sidebar = {
        left: { el: null, open: false },
        right: { el: null, open: false },
        overlay: null,
        touchStartX: 0,
        touchStartY: 0,
        swiping: false,
    };

    function initSidebars() {
        sidebar.left.el = document.getElementById('sidebar-left');
        sidebar.right.el = document.getElementById('sidebar-right');
        sidebar.overlay = document.getElementById('overlay');

        // Left sidebar toggle
        document.getElementById('btn-menu').addEventListener('click', () => toggleSidebar('left'));
        document.getElementById('close-left').addEventListener('click', () => closeSidebar('left'));

        // Right sidebar toggle
        document.getElementById('btn-chat').addEventListener('click', () => toggleSidebar('right'));
        document.getElementById('close-right').addEventListener('click', () => closeSidebar('right'));

        // Overlay click closes sidebars
        sidebar.overlay.addEventListener('click', () => {
            closeSidebar('left');
            closeSidebar('right');
        });

        // Swipe gestures on main area
        const mainArea = document.getElementById('main-area');
        mainArea.addEventListener('touchstart', onSwipeStart, { passive: true });
        mainArea.addEventListener('touchend', onSwipeEnd, { passive: true });
    }

    function toggleSidebar(side) {
        if (sidebar[side].open) {
            closeSidebar(side);
        } else {
            openSidebar(side);
        }
    }

    function openSidebar(side) {
        // Close other sidebar first
        if (side === 'left' && sidebar.right.open) closeSidebar('right');
        if (side === 'right' && sidebar.left.open) closeSidebar('left');

        sidebar[side].el.classList.add('open');
        sidebar[side].open = true;
        sidebar.overlay.classList.remove('hidden');

        // Refresh editor when sidebar opens/closes (delayed for animation)
        setTimeout(() => {
            if (window.EditorManager) EditorManager.resize();
        }, 300);
    }

    function closeSidebar(side) {
        sidebar[side].el.classList.remove('open');
        sidebar[side].open = false;
        if (!sidebar.left.open && !sidebar.right.open) {
            sidebar.overlay.classList.add('hidden');
        }
        setTimeout(() => {
            if (window.EditorManager) EditorManager.resize();
        }, 300);
    }

    function onSwipeStart(e) {
        const touch = e.touches[0];
        sidebar.touchStartX = touch.clientX;
        sidebar.touchStartY = touch.clientY;
        sidebar.swiping = true;
    }

    function onSwipeEnd(e) {
        if (!sidebar.swiping) return;
        sidebar.swiping = false;

        const touch = e.changedTouches[0];
        const dx = touch.clientX - sidebar.touchStartX;
        const dy = touch.clientY - sidebar.touchStartY;
        const width = window.innerWidth;

        // Only handle horizontal swipes
        if (Math.abs(dx) < 50 || Math.abs(dy) > Math.abs(dx)) return;

        // Swipe from left edge -> open left sidebar
        if (dx > 0 && sidebar.touchStartX < 30 && !sidebar.left.open) {
            openSidebar('left');
            return;
        }

        // Swipe from right edge -> open right sidebar
        if (dx < 0 && sidebar.touchStartX > width - 30 && !sidebar.right.open) {
            openSidebar('right');
            return;
        }

        // Swipe left on open left sidebar -> close
        if (dx < -60 && sidebar.left.open) {
            closeSidebar('left');
            return;
        }

        // Swipe right on open right sidebar -> close
        if (dx > 60 && sidebar.right.open) {
            closeSidebar('right');
            return;
        }
    }

    // ── Left Tab Management ──
    function initTabs() {
        const tabs = document.querySelectorAll('#left-tabs .tab');
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                tabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                const target = tab.dataset.tab;
                document.querySelectorAll('#left-panels .panel').forEach(p => p.classList.remove('active'));
                document.getElementById(`panel-${target}`).classList.add('active');
            });
        });
    }

    // ── Keyboard Shortcuts ──
    function initKeyboard() {
        document.addEventListener('keydown', (e) => {
            // Ctrl/Cmd + S - Save
            if ((e.ctrlKey || e.metaKey) && e.key === 's') {
                e.preventDefault();
                if (window.FileManager) FileManager.saveFile();
            }
            // Ctrl/Cmd + Shift + P - Command palette (placeholder)
            // Escape - close sidebars
            if (e.key === 'Escape') {
                closeSidebar('left');
                closeSidebar('right');
                const dialog = document.getElementById('dialog-overlay');
                if (dialog) dialog.classList.add('hidden');
            }
        });
    }

    // ── Bottom Panel ──
    function initBottomPanel() {
        const panel = document.getElementById('bottom-panel');
        const closeBtn = document.getElementById('bottom-panel-close');
        const clearBtn = document.getElementById('bottom-panel-clear');

        if (closeBtn) {
            closeBtn.addEventListener('click', () => {
                if (window.TerminalManager) TerminalManager.hidePanel();
            });
        }
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                if (window.TerminalManager) TerminalManager.clearOutput();
            });
        }

        // Toggle bottom panel button in toolbar (add if not present)
        addBottomToggle();
    }

    function addBottomToggle() {
        const toolbar = document.getElementById('toolbar-actions');
        if (!toolbar) return;

        // Add toggle button before chat button
        const toggleBtn = document.createElement('button');
        toggleBtn.id = 'btn-toggle-output';
        toggleBtn.title = '输出面板';
        toggleBtn.textContent = '🖥';
        toggleBtn.style.cssText = 'color:var(--teal);';
        toggleBtn.addEventListener('click', () => {
            if (window.TerminalManager) TerminalManager.togglePanel();
        });

        const chatBtn = document.getElementById('btn-chat');
        toolbar.insertBefore(toggleBtn, chatBtn);
    }

    // ── Editor Toolbar ──
    function initEditorToolbar() {
        const searchBtn = document.getElementById('editor-search-btn');
        const searchInput = document.getElementById('editor-search');
        const replaceInput = document.getElementById('editor-replace');

        if (searchBtn) {
            searchBtn.addEventListener('click', () => {
                if (window.EditorManager) {
                    const q = searchInput.value;
                    if (q) {
                        EditorManager.search(q);
                    } else {
                        searchInput.style.display = searchInput.style.display === 'none' ? '' : 'none';
                        if (searchInput.style.display !== 'none') {
                            searchInput.focus();
                        }
                    }
                }
            });
        }

        // Keyboard shortcuts in search
        if (searchInput) {
            searchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    if (window.EditorManager) EditorManager.search(searchInput.value);
                }
                if (e.key === 'Escape') {
                    searchInput.style.display = 'none';
                    searchInput.value = '';
                }
            });
        }
    }

    // ── Toolbar Buttons ──
    function initToolbar() {
        // Undo
        document.getElementById('btn-undo').addEventListener('click', () => {
            if (window.EditorManager) EditorManager.undo();
        });
        // Redo
        document.getElementById('btn-redo').addEventListener('click', () => {
            if (window.EditorManager) EditorManager.redo();
        });
        // Save
        document.getElementById('btn-save').addEventListener('click', () => {
            if (window.FileManager) FileManager.saveFile();
        });
        // Run
        document.getElementById('btn-run').addEventListener('click', () => {
            if (window.TerminalManager) {
                TerminalManager.showPanel();
                const filePath = window.EditorManager ? EditorManager.getCurrentFile() : '';
                const compiler = document.getElementById('compiler-select');
                const compilerVal = compiler ? compiler.value : 'python3';
                if (filePath) {
                    TerminalManager.execute(filePath, compilerVal);
                } else {
                    const code = window.EditorManager ? EditorManager.getContent() : '';
                    TerminalManager.executeCode(code, compilerVal);
                }
            }
        });
        // Stop
        document.getElementById('btn-stop').addEventListener('click', () => {
            if (window.TerminalManager) TerminalManager.stop();
        });
    }

    // ── Auto Save ──
    function initAutoSave() {
        let saveTimer = null;
        document.addEventListener('editor:change', () => {
            clearTimeout(saveTimer);
            saveTimer = setTimeout(() => {
                if (window.EditorManager && EditorManager.isDirty() && window.FileManager) {
                    // Only auto-save if file was previously saved
                    const currentFile = EditorManager.getCurrentFile();
                    if (currentFile) {
                        FileManager.saveFile().then(() => {
                            showToast('已自动保存', 'success', 1000);
                        }).catch(() => {});
                    }
                }
            }, 5000);
        });
    }

    // ── Window Resize ──
    function initResize() {
        window.addEventListener('resize', debounce(() => {
            if (window.EditorManager) EditorManager.resize();
        }, 200));

        // Handle visual viewport changes (mobile keyboard)
        if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', debounce(() => {
                if (window.EditorManager) EditorManager.resize();
            }, 100));
        }
    }

    // ── Theme Management ──
    let currentTheme = 'dark';
    const themes = [
        { id: 'dark', name: 'Dark (Dracula)', color: '#1e1e2e' },
        { id: 'claude', name: 'Claude (Warm)', color: '#FAF9F6' },
    ];

    function initTheme() {
        const toolbar = document.getElementById('toolbar-actions');
        if (!toolbar) return;

        // Create theme button
        const themeBtn = document.createElement('button');
        themeBtn.id = 'btn-theme';
        themeBtn.title = '切换主题';
        themeBtn.textContent = '🎨';

        // Create theme menu
        const menu = document.createElement('div');
        menu.className = 'theme-menu';
        menu.id = 'theme-menu';
        themes.forEach(t => {
            const btn = document.createElement('button');
            btn.dataset.theme = t.id;
            btn.innerHTML = `<span class="theme-dot" style="background:${t.color};${t.id === 'claude' ? 'border-color:#D4C5B0;' : ''}"></span>${t.name}`;
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                setTheme(t.id);
                menu.classList.remove('show');
            });
            menu.appendChild(btn);
        });

        themeBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            menu.classList.toggle('show');
        });

        document.addEventListener('click', () => {
            menu.classList.remove('show');
        });

        toolbar.insertBefore(themeBtn, toolbar.firstChild);
        toolbar.parentElement.appendChild(menu);
    }

    function setTheme(themeId) {
        currentTheme = themeId;
        document.documentElement.setAttribute('data-theme', themeId === 'dark' ? '' : themeId);
        if (themeId === 'dark') document.documentElement.removeAttribute('data-theme');

        // Update active state in menu
        document.querySelectorAll('#theme-menu button').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.theme === themeId);
        });

        // Save theme to config
        fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ theme: themeId }),
        }).then(r => r.json()).catch(() => {});

        // Refresh CodeMirror
        if (window.EditorManager && EditorManager.getEditor) {
            const ed = EditorManager.getEditor();
            if (ed) ed.refresh();
        }
    }

    async function loadTheme() {
        try {
            const resp = await fetch('/api/config');
            if (resp.ok) {
                const config = await resp.json();
                if (config.theme && config.theme !== 'dark') {
                    document.documentElement.setAttribute('data-theme', config.theme);
                    currentTheme = config.theme;
                }
            }
        } catch (e) {
            // Use default dark theme
        }
    }

    // ── Server Management ──
    let serverStatusTimer = null;
    let logViewerEventSource = null;
    let logViewerOpen = false;

    function initServerManagement() {
        // Wire up server management bar buttons
        const restartBtn = document.getElementById('btn-server-restart');
        const logsBtn = document.getElementById('btn-server-logs');
        const updatesBtn = document.getElementById('btn-check-updates');

        if (restartBtn) {
            restartBtn.addEventListener('click', () => restartServer());
        }
        if (logsBtn) {
            logsBtn.addEventListener('click', () => toggleLogViewer());
        }
        if (updatesBtn) {
            updatesBtn.addEventListener('click', () => checkUpdates());
        }

        // Log viewer panel buttons
        const logCloseBtn = document.getElementById('log-viewer-close');
        const logClearBtn = document.getElementById('log-viewer-clear');
        const logRefreshBtn = document.getElementById('log-viewer-refresh');

        if (logCloseBtn) {
            logCloseBtn.addEventListener('click', () => toggleLogViewer());
        }
        if (logClearBtn) {
            logClearBtn.addEventListener('click', () => {
                const content = document.getElementById('log-viewer-content');
                if (content) content.textContent = '';
            });
        }
        if (logRefreshBtn) {
            logRefreshBtn.addEventListener('click', () => {
                const content = document.getElementById('log-viewer-content');
                if (content) content.textContent = '';
                connectLogViewerSSE();
            });
        }

        // Update dialog buttons
        const updateCheckBtn = document.getElementById('update-check-btn');
        const updateApplyBtn = document.getElementById('update-apply-btn');
        const updateCloseBtn = document.getElementById('update-close-btn');

        if (updateCheckBtn) {
            updateCheckBtn.addEventListener('click', () => checkUpdates());
        }
        if (updateApplyBtn) {
            updateApplyBtn.addEventListener('click', () => applyUpdate());
        }
        if (updateCloseBtn) {
            updateCloseBtn.addEventListener('click', () => {
                document.getElementById('update-dialog-overlay').classList.add('hidden');
            });
        }

        // Close update dialog on overlay click
        const updateOverlay = document.getElementById('update-dialog-overlay');
        if (updateOverlay) {
            updateOverlay.addEventListener('click', (e) => {
                if (e.target === updateOverlay) {
                    updateOverlay.classList.add('hidden');
                }
            });
        }

        // Start polling server status
        pollServerStatus();
        serverStatusTimer = setInterval(pollServerStatus, 10000);

        // Close log viewer + update dialog on Escape
        const origKeyHandler = document.onkeydown;
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                if (logViewerOpen) toggleLogViewer();
                const updateDialog = document.getElementById('update-dialog-overlay');
                if (updateDialog && !updateDialog.classList.contains('hidden')) {
                    updateDialog.classList.add('hidden');
                }
            }
        });
    }

    /**
     * Poll /api/server/status and update the indicator
     */
    async function pollServerStatus() {
        const dot = document.getElementById('server-status-dot');
        const text = document.getElementById('server-status-text');
        if (!dot || !text) return;

        try {
            const resp = await fetch('/api/server/status');
            if (!resp.ok) throw new Error('Server unreachable');

            const data = await resp.json();
            const running = data.status === 'running' || data.running === true;

            dot.className = 'status-dot ' + (running ? 'running' : 'stopped');
            dot.title = running ? 'Server running' : 'Server stopped';
            text.textContent = running
                ? (data.uptime ? `Running (${formatUptime(data.uptime)})` : 'Running')
                : 'Stopped';
        } catch (err) {
            dot.className = 'status-dot stopped';
            dot.title = 'Server unreachable';
            text.textContent = 'Unreachable';
        }
    }

    /**
     * Format uptime seconds into human-readable string
     */
    function formatUptime(seconds) {
        if (!seconds || seconds < 0) return '';
        if (seconds < 60) return Math.round(seconds) + 's';
        if (seconds < 3600) return Math.round(seconds / 60) + 'm';
        const h = Math.floor(seconds / 3600);
        const m = Math.round((seconds % 3600) / 60);
        return h + 'h' + (m > 0 ? m + 'm' : '');
    }

    /**
     * Restart the server via API, then poll until back online
     */
    async function restartServer() {
        const dot = document.getElementById('server-status-dot');
        const text = document.getElementById('server-status-text');

        if (dot) {
            dot.className = 'status-dot checking';
            text.textContent = 'Restarting...';
        }

        showToast('Restarting server...', 'info', 2000);

        try {
            const resp = await fetch('/api/server/restart', { method: 'POST' });
            if (!resp.ok) {
                const errText = await resp.text().catch(() => 'Unknown error');
                throw new Error(errText);
            }
        } catch (err) {
            showToast('Restart failed: ' + err.message, 'error', 3000);
            if (dot) {
                dot.className = 'status-dot stopped';
                text.textContent = 'Error';
            }
            return;
        }

        // Poll health until back online
        let attempts = 0;
        const maxAttempts = 30; // 30 seconds

        const check = setInterval(async () => {
            attempts++;
            try {
                const resp = await fetch('/api/server/status');
                if (resp.ok) {
                    const data = await resp.json();
                    const running = data.status === 'running' || data.running === true;
                    if (running) {
                        clearInterval(check);
                        if (dot) {
                            dot.className = 'status-dot running';
                            text.textContent = 'Running';
                        }
                        showToast('Server restarted successfully', 'success', 2000);
                        pollServerStatus();
                    }
                }
            } catch (_) {
                // Still waiting
            }

            if (attempts >= maxAttempts) {
                clearInterval(check);
                if (dot) {
                    dot.className = 'status-dot stopped';
                    text.textContent = 'Timeout';
                }
                showToast('Server restart timed out', 'error', 3000);
            }
        }, 1000);
    }

    /**
     * Toggle the log viewer panel
     */
    function toggleLogViewer() {
        const panel = document.getElementById('log-viewer-panel');
        if (!panel) return;

        logViewerOpen = !logViewerOpen;

        if (logViewerOpen) {
            panel.classList.remove('hidden');
            connectLogViewerSSE();
        } else {
            panel.classList.add('hidden');
            disconnectLogViewerSSE();
        }
    }

    /**
     * Connect to the SSE log stream endpoint
     */
    function connectLogViewerSSE() {
        disconnectLogViewerSSE();

        const content = document.getElementById('log-viewer-content');
        if (!content) return;

        try {
            logViewerEventSource = new EventSource('/api/server/logs/stream');

            logViewerEventSource.addEventListener('message', (e) => {
                const line = e.data || '';
                if (!line.trim()) return;

                // Auto-scroll if already at bottom
                const atBottom = content.scrollHeight - content.scrollTop - content.clientHeight < 60;

                const lineEl = document.createElement('div');
                lineEl.className = 'log-line';

                // Colorize log levels
                if (line.includes(' ERROR ') || line.includes('[ERROR]')) {
                    lineEl.style.color = 'var(--red, #ff5555)';
                } else if (line.includes(' WARNING ') || line.includes('[WARN]')) {
                    lineEl.style.color = 'var(--yellow, #f1fa8c)';
                } else if (line.includes(' INFO ') || line.includes('[INFO]')) {
                    lineEl.style.color = 'var(--text-secondary, #aaa)';
                } else if (line.includes(' DEBUG ') || line.includes('[DEBUG]')) {
                    lineEl.style.color = 'var(--text-muted, #666)';
                }

                lineEl.textContent = line;
                content.appendChild(lineEl);

                // Keep max 2000 lines
                while (content.children.length > 2000) {
                    content.removeChild(content.firstChild);
                }

                if (atBottom) {
                    content.scrollTop = content.scrollHeight;
                }
            });

            logViewerEventSource.onerror = () => {
                // EventSource auto-reconnects, but we can show status
                console.warn('Log SSE connection error, will retry...');
            };

        } catch (err) {
            console.warn('Failed to connect log SSE:', err.message);
            // Fallback: no real-time logs
            const lineEl = document.createElement('div');
            lineEl.style.color = 'var(--text-muted, #666)';
            lineEl.style.fontStyle = 'italic';
            lineEl.textContent = 'Log streaming unavailable: ' + err.message;
            content.appendChild(lineEl);
        }
    }

    /**
     * Disconnect the log viewer SSE connection
     */
    function disconnectLogViewerSSE() {
        if (logViewerEventSource) {
            logViewerEventSource.close();
            logViewerEventSource = null;
        }
    }

    /**
     * Check for updates via API and show update dialog
     */
    async function checkUpdates() {
        const overlay = document.getElementById('update-dialog-overlay');
        const statusEl = document.getElementById('update-status');
        const infoEl = document.getElementById('update-info');
        const versionEl = document.getElementById('update-current-version');
        const applyBtn = document.getElementById('update-apply-btn');
        const checkBtn = document.getElementById('update-check-btn');

        if (!overlay) return;

        // Show dialog
        overlay.classList.remove('hidden');
        if (statusEl) statusEl.textContent = 'Checking for updates...';
        if (infoEl) { infoEl.classList.add('hidden'); infoEl.textContent = ''; }
        if (applyBtn) applyBtn.classList.add('hidden');
        if (checkBtn) checkBtn.disabled = true;

        try {
            const resp = await fetch('/api/update/check', { method: 'POST' });
            if (!resp.ok) {
                const errText = await resp.text().catch(() => 'Unknown error');
                throw new Error(errText);
            }

            const data = await resp.json();

            // Show current version
            if (versionEl) {
                versionEl.textContent = 'Current version: ' + (data.current_version || data.version || 'unknown');
            }

            if (data.update_available) {
                // Update available
                if (statusEl) statusEl.textContent = 'Update available!';

                let info = '';
                if (data.new_version) info += 'New version: ' + data.new_version + '\n';
                if (data.commit) info += 'Commit: ' + data.commit + '\n';
                if (data.commit_message) info += 'Message: ' + data.commit_message + '\n';
                if (data.commit_date) info += 'Date: ' + data.commit_date + '\n';
                if (data.changelog) info += '\n' + data.changelog + '\n';

                if (infoEl && info) {
                    infoEl.textContent = info;
                    infoEl.classList.remove('hidden');
                }
                if (applyBtn) applyBtn.classList.remove('hidden');
            } else {
                if (statusEl) statusEl.textContent = 'You are up to date!';
                if (versionEl) {
                    versionEl.textContent = 'Current version: ' + (data.current_version || data.version || 'latest');
                }
            }
        } catch (err) {
            if (statusEl) statusEl.textContent = 'Error: ' + err.message;
            if (versionEl) versionEl.textContent = 'Version check failed';
        } finally {
            if (checkBtn) checkBtn.disabled = false;
        }
    }

    /**
     * Apply the pending update via API
     */
    async function applyUpdate() {
        const statusEl = document.getElementById('update-status');
        const applyBtn = document.getElementById('update-apply-btn');
        const checkBtn = document.getElementById('update-check-btn');

        if (!statusEl) return;

        if (applyBtn) applyBtn.disabled = true;
        if (checkBtn) checkBtn.disabled = true;

        statusEl.innerHTML = 'Updating...\n<div class="update-progress-bar"><div class="update-progress-fill" id="update-progress"></div></div>';

        const progressEl = document.getElementById('update-progress');

        try {
            const resp = await fetch('/api/update/apply', { method: 'POST' });
            if (!resp.ok) {
                const errText = await resp.text().catch(() => 'Unknown error');
                throw new Error(errText);
            }

            const data = await resp.json();

            if (progressEl) progressEl.style.width = '100%';
            statusEl.innerHTML = '✅ Update applied! The page will reload in a few seconds...';

            showToast('Update applied, reloading...', 'success', 3000);

            // Reload page after delay
            setTimeout(() => {
                window.location.reload();
            }, 3000);

        } catch (err) {
            statusEl.textContent = 'Update failed: ' + err.message;
            if (progressEl) progressEl.style.width = '0%';
            showToast('Update failed: ' + err.message, 'error', 3000);
        } finally {
            if (applyBtn) applyBtn.disabled = false;
            if (checkBtn) checkBtn.disabled = false;
        }
    }

    // ── Prevent unwanted behaviors ──
    function initMobileFixes() {
        // Prevent pull-to-refresh
        document.body.addEventListener('touchmove', (e) => {
            if (e.target.closest('.sidebar') || e.target.closest('#output-content') ||
                e.target.closest('#chat-messages') || e.target.closest('#file-tree') ||
                e.target.closest('#search-results') || e.target.closest('#git-changes-list') ||
                e.target.closest('#git-log-list')) {
                return;
            }
        }, { passive: true });

        // Prevent double-tap zoom
        let lastTouchEnd = 0;
        document.addEventListener('touchend', (e) => {
            const now = Date.now();
            if (now - lastTouchEnd <= 300) {
                e.preventDefault();
            }
            lastTouchEnd = now;
        }, { passive: false });

        // Prevent context menu on long press (except for our custom handling)
        document.addEventListener('contextmenu', (e) => {
            if (e.target.closest('.file-item') || e.target.closest('.search-result-item') ||
                e.target.closest('.git-change-item')) {
                e.preventDefault();
            }
        });
    }

    // ── Initialize Everything ──
    async function init() {
        if (initialized) return;
        initialized = true;

        console.log('[PhoneIDE] Initializing...');

        // Init UI components
        initSidebars();
        initTabs();
        initKeyboard();
        initBottomPanel();
        initEditorToolbar();
        initToolbar();
        initAutoSave();
        initResize();
        initMobileFixes();
        initTheme();
        initServerManagement();
        await loadTheme();

        // Init modules (order matters)
        try {
            // Editor first
            if (window.EditorManager) await EditorManager.init();

            // Load config
            const configResp = await fetch('/api/config');
            if (configResp.ok) {
                const config = await configResp.json();
                if (config.workspace) {
                    document.getElementById('workspace-path').value = config.workspace;
                }
                if (config.font_size && window.EditorManager) {
                    EditorManager.setFontSize(config.font_size);
                }
                // Theme already loaded in loadTheme() above
            }

            // Load compilers
            if (window.TerminalManager) await TerminalManager.loadCompilers();

            // Load file tree
            if (window.FileManager) await FileManager.loadFileList('');

            // Load git status
            if (window.GitManager) await GitManager.refresh();

            // Load chat history
            if (window.ChatManager) await ChatManager.loadHistory();

            // Load venv info
            if (window.TerminalManager) await TerminalManager.loadVenvInfo();

            showToast('PhoneIDE 就绪', 'success', 1500);
            console.log('[PhoneIDE] Ready!');
        } catch (err) {
            console.error('[PhoneIDE] Init error:', err);
            showToast('初始化失败: ' + err.message, 'error', 3000);
        }
    }

    // ── Boot ──
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return {
        init, showToast, showDialog, showPromptDialog, showConfirmDialog, showInputDialog,
        restartServer, toggleLogViewer, checkUpdates, applyUpdate, pollServerStatus
    };
})();

window.AppManager = AppManager;
