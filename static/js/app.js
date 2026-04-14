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

    return { init, showToast, showDialog, showPromptDialog, showConfirmDialog, showInputDialog };
})();

window.AppManager = AppManager;
