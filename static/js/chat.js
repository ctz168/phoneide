/**
 * ChatManager - LLM chat interface and agent tool execution for PhoneIDE
 * Works with Flask backend on port 1239
 */
const ChatManager = (() => {
    'use strict';

    // ── State ──────────────────────────────────────────────────────
    let isProcessing = false;
    let messages = [];                // local cache of chat history
    let lastUserMessage = null;       // for re-send
    let settingsDialogEl = null;      // cached settings dialog

    // ── Constants ──────────────────────────────────────────────────
    const KNOWN_TOOLS = [
        'read_file', 'write_file', 'execute_code', 'search_files',
        'list_files', 'git_status', 'git_diff', 'terminal', 'install_package'
    ];

    const TOOL_ICONS = {
        read_file:     '📖',
        write_file:    '✏️',
        execute_code:  '▶️',
        search_files:  '🔍',
        list_files:    '📁',
        git_status:    '🔀',
        git_diff:      '📝',
        terminal:      '💻',
        install_package: '📦'
    };

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Escape HTML entities for safe insertion
     */
    function escapeHTML(str) {
        const div = document.createElement('div');
        div.textContent = str || '';
        return div.innerHTML;
    }

    /**
     * Escape string for use in HTML attribute values
     */
    function escapeAttr(str) {
        return (str || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;')
                          .replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /**
     * Format a Date or timestamp string into a short time display
     */
    function formatTime(time) {
        let d;
        if (time instanceof Date) {
            d = time;
        } else if (typeof time === 'number') {
            d = new Date(time);
        } else if (typeof time === 'string') {
            d = new Date(time);
            if (isNaN(d.getTime())) d = new Date();
        } else {
            d = new Date();
        }
        return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }

    /**
     * Truncate a string to a maximum length with ellipsis
     */
    function truncate(str, maxLen) {
        if (!str) return '';
        if (str.length <= maxLen) return str;
        return str.substring(0, maxLen) + '...';
    }

    /**
     * Generate a unique ID for message elements
     */
    function msgId() {
        return 'chat-msg-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
    }

    // ── Markdown-Lite Rendering ────────────────────────────────────

    /**
     * Render markdown-lite formatting:
     *  - Code blocks: ```lang\n...\n```
     *  - Inline code: `...`
     *  - Bold: **...** or __...__
     *  - Italic: *...* or _..._
     *  - Unordered lists: - item or * item
     *  - Ordered lists: 1. item
     *  - Line breaks: double newline
     *
     * Returns HTML string. IMPORTANT: input must already be HTML-escaped.
     */
    function renderMarkdownLite(text) {
        if (!text) return '';

        // HTML-escape the raw text first
        let html = escapeHTML(text);

        // Extract and protect fenced code blocks first
        // Replace ```lang\ncode\n``` with placeholder markers
        const codeBlocks = [];
        html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
            const idx = codeBlocks.length;
            codeBlocks.push({ lang: lang || '', code: code.replace(/\n$/, '') });
            return `\x00CODEBLOCK_${idx}\x00`;
        });

        // Also handle ```...``` without explicit newlines (inline code blocks)
        html = html.replace(/```([\s\S]*?)```/g, (_, code) => {
            const idx = codeBlocks.length;
            codeBlocks.push({ lang: '', code: code.replace(/^\n/, '').replace(/\n$/, '') });
            return `\x00CODEBLOCK_${idx}\x00`;
        });

        // Inline code: `...`
        const inlineCodes = [];
        html = html.replace(/`([^`\n]+)`/g, (_, code) => {
            const idx = inlineCodes.length;
            inlineCodes.push(code);
            return `\x00INLINE_${idx}\x00`;
        });

        // Bold: **...** or __...__
        html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/__(.+?)__/g, '<strong>$1</strong>');

        // Italic: *...* (but not inside already processed tags)
        html = html.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<em>$1</em>');

        // Restore inline code
        html = html.replace(/\x00INLINE_(\d+)\x00/g, (_, idx) => {
            const code = inlineCodes[parseInt(idx, 10)];
            return `<code>${code}</code>`;
        });

        // Restore code blocks with copy button
        html = html.replace(/\x00CODEBLOCK_(\d+)\x00/g, (_, idx) => {
            const block = codeBlocks[parseInt(idx, 10)];
            const escapedCode = block.code.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            const copyBtn = `<button class="code-copy-btn" data-code="${escapeAttr(block.code)}" title="Copy">📋</button>`;
            const langLabel = block.lang ? `<span class="code-lang">${escapeHTML(block.lang)}</span>` : '';
            return `<div class="code-block-wrapper">${langLabel}${copyBtn}<pre><code>${escapedCode}</code></pre></div>`;
        });

        // Unordered lists: lines starting with - or * followed by space
        html = html.replace(/^(\s*)([-*])\s+(.+)$/gm, '$1<li>$3</li>');
        html = html.replace(/((?:<li>.*<\/li>\n?)+)/g, '<ul>$1</ul>');

        // Ordered lists: lines starting with \d+.
        html = html.replace(/^(\s*)(\d+)\.\s+(.+)$/gm, '$1<li>$3</li>');

        // Paragraphs: double newlines
        html = html.replace(/\n{2,}/g, '</p><p>');

        // Single newlines -> <br>
        html = html.replace(/\n/g, '<br>');

        // Wrap in paragraphs if not already wrapped
        if (!html.startsWith('<')) {
            html = '<p>' + html + '</p>';
        }

        return html;
    }

    // ── Code Copy Handler ──────────────────────────────────────────

    /**
     * Bind click-to-copy on all .code-copy-btn elements within a container
     */
    function bindCopyButtons(container) {
        const btns = (container || document).querySelectorAll('.code-copy-btn');
        btns.forEach(btn => {
            if (btn._bound) return;
            btn._bound = true;
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const code = btn.dataset.code || '';
                navigator.clipboard.writeText(code).then(() => {
                    const original = btn.textContent;
                    btn.textContent = '✅';
                    setTimeout(() => { btn.textContent = original; }, 1500);
                }).catch(() => {
                    // Fallback for older browsers / non-HTTPS
                    const ta = document.createElement('textarea');
                    ta.value = code;
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    document.body.appendChild(ta);
                    ta.select();
                    try { document.execCommand('copy'); } catch (_) {}
                    document.body.removeChild(ta);
                    const original = btn.textContent;
                    btn.textContent = '✅';
                    setTimeout(() => { btn.textContent = original; }, 1500);
                });
            });
        });
    }

    // ── Message Rendering ──────────────────────────────────────────

    /**
     * Render a single message element and return it
     * @param {string} role - 'user', 'assistant', 'tool', 'error', 'system'
     * @param {string} content - message text
     * @param {object} [extra] - additional data (time, tool_calls, tool_result, args, etc.)
     * @returns {HTMLElement} the created message element
     */
    function createMessageEl(role, content, extra) {
        extra = extra || {};

        const div = document.createElement('div');
        div.className = 'chat-msg';
        div.id = extra.id || msgId();

        // Role-based class
        if (role === 'user') {
            div.classList.add('user');
        } else if (role === 'assistant') {
            div.classList.add('assistant');
        } else if (role === 'tool') {
            div.classList.add('tool');
        } else if (role === 'error') {
            div.classList.add('error');
        }

        // Role badge for non-user messages
        if (role === 'assistant') {
            const badge = document.createElement('div');
            badge.className = 'chat-role-badge';
            badge.textContent = '🤖 Assistant';
            div.appendChild(badge);
        }

        // Tool execution details
        if (role === 'tool') {
            const toolName = extra.tool || 'unknown';
            const icon = TOOL_ICONS[toolName] || '🔧';
            const argsStr = extra.args ? formatToolArgs(extra.args) : '';
            const ok = extra.ok !== false;

            const header = document.createElement('div');
            header.className = 'tool-header';
            header.innerHTML = `<span class="tool-name">${icon} ${escapeHTML(toolName)}</span>`
                + (ok ? '<span class="tool-status tool-ok">✓</span>' : '<span class="tool-status tool-fail">✗</span>');
            div.appendChild(header);

            if (argsStr) {
                const argsEl = document.createElement('div');
                argsEl.className = 'tool-args';
                argsEl.textContent = argsStr;
                div.appendChild(argsEl);
            }

            if (content) {
                const resultEl = document.createElement('div');
                resultEl.className = 'tool-result';
                resultEl.textContent = typeof content === 'string' ? content : JSON.stringify(content, null, 2);
                div.appendChild(resultEl);
            }

            // Timestamp
            if (extra.time) {
                const timeEl = document.createElement('div');
                timeEl.className = 'chat-time';
                timeEl.textContent = formatTime(extra.time);
                div.appendChild(timeEl);
            }

            return div;
        }

        // Error messages
        if (role === 'error') {
            const icon = document.createElement('span');
            icon.textContent = '⚠️ ';
            div.appendChild(icon);
            const textEl = document.createElement('span');
            textEl.innerHTML = renderMarkdownLite(content);
            div.appendChild(textEl);
        } else {
            // Regular text content with markdown-lite
            const textEl = document.createElement('div');
            textEl.className = 'chat-content';
            textEl.innerHTML = renderMarkdownLite(content);
            div.appendChild(textEl);
        }

        // Timestamp
        const timeStr = extra.time || (Date.now());
        const timeEl = document.createElement('div');
        timeEl.className = 'chat-time';
        timeEl.textContent = formatTime(timeStr);
        div.appendChild(timeEl);

        // Bind copy buttons inside this message
        bindCopyButtons(div);

        return div;
    }

    /**
     * Format tool args for display (truncate long values)
     */
    function formatToolArgs(args) {
        if (!args) return '';
        if (typeof args === 'string') {
            try { args = JSON.parse(args); } catch (_) { return truncate(args, 200); }
        }
        if (typeof args !== 'object') return String(args);
        const parts = [];
        for (const [key, val] of Object.entries(args)) {
            const valStr = typeof val === 'string' ? truncate(val, 80) : JSON.stringify(val);
            parts.push(`${key}: ${valStr}`);
        }
        return parts.join(', ');
    }

    /**
     * Render all messages into the chat container
     * @param {Array} msgs - array of message objects {role, content, time, ...}
     */
    function renderMessages(msgs) {
        const container = document.getElementById('chat-messages');
        if (!container) return;

        container.innerHTML = '';
        messages = Array.isArray(msgs) ? msgs : [];

        if (messages.length === 0) {
            container.innerHTML = '<div class="empty-state"><div class="emoji">🤖</div><div>Ask me anything about your code</div></div>';
            return;
        }

        for (const msg of messages) {
            const el = createMessageEl(msg.role, msg.content, msg);
            container.appendChild(el);
        }

        bindCopyButtons(container);
        scrollToBottom();
    }

    /**
     * Add a single message to the chat display (append)
     * @param {string} role - 'user', 'assistant', 'tool', 'error'
     * @param {string} content - message content
     * @param {object} [extra] - extra data
     */
    function addMessage(role, content, extra) {
        const container = document.getElementById('chat-messages');
        if (!container) return;

        // Remove empty state if present
        const emptyState = container.querySelector('.empty-state');
        if (emptyState) emptyState.remove();

        extra = extra || {};
        extra.time = extra.time || new Date();

        const el = createMessageEl(role, content, extra);
        container.appendChild(el);

        // Update local cache
        messages.push({ role, content, time: extra.time, ...extra });

        bindCopyButtons(container);
        scrollToBottom();

        return el;
    }

    // ── Typing Indicator ───────────────────────────────────────────

    /**
     * Show typing indicator
     * @returns {HTMLElement} the indicator element
     */
    function showTyping() {
        const container = document.getElementById('chat-messages');
        if (!container) return null;

        // Don't add duplicate
        if (container.querySelector('.chat-typing')) return null;

        const indicator = document.createElement('div');
        indicator.className = 'chat-typing';
        indicator.textContent = 'Thinking';
        container.appendChild(indicator);
        scrollToBottom();
        return indicator;
    }

    /**
     * Hide typing indicator
     */
    function hideTyping() {
        const container = document.getElementById('chat-messages');
        if (!container) return;
        const indicator = container.querySelector('.chat-typing');
        if (indicator) indicator.remove();
    }

    // ── Auto-Scroll ────────────────────────────────────────────────

    /**
     * Scroll chat messages container to bottom
     */
    function scrollToBottom() {
        const container = document.getElementById('chat-messages');
        if (container) {
            container.scrollTop = container.scrollHeight;
        }
    }

    // ── Send / Processing State ─────────────────────────────────────

    /**
     * Set the processing state and update UI
     * @param {boolean} processing - true while waiting for response
     */
    function setProcessing(processing) {
        isProcessing = processing;

        const sendBtn = document.getElementById('chat-send');
        const input = document.getElementById('chat-input');
        const statusEl = document.getElementById('chat-execute-status');

        if (sendBtn) {
            sendBtn.disabled = processing;
            sendBtn.textContent = processing ? 'Thinking...' : 'Send';
        }

        if (input) {
            input.disabled = processing;
            if (!processing) {
                input.focus();
            }
        }

        if (processing) {
            showTyping();
            if (statusEl) statusEl.textContent = '';
        } else {
            hideTyping();
        }
    }

    /**
     * Update the execute status bar with tool execution info
     * @param {string} text - status text to show
     */
    function setExecuteStatus(text) {
        const el = document.getElementById('chat-execute-status');
        if (el) {
            el.textContent = text || '';
        }
    }

    // ── API: Load History ──────────────────────────────────────────

    /**
     * Load chat history from the server and render it
     * @returns {Promise<Array>} messages array
     */
    async function loadHistory() {
        try {
            const resp = await fetch('/api/chat/history');
            if (!resp.ok) throw new Error(`Failed to load history: ${resp.statusText}`);

            const data = await resp.json();
            const msgs = data.messages || [];
            renderMessages(msgs);
            return msgs;
        } catch (err) {
            console.warn('ChatManager: loadHistory error:', err.message);
            // Show empty state
            renderMessages([]);
            return [];
        }
    }

    // ── API: Send Message ──────────────────────────────────────────

    /**
     * Send a message to the chat API
     * @param {string} [text] - message text (defaults to input field value)
     */
    async function sendMessage(text) {
        if (isProcessing) return;

        const input = document.getElementById('chat-input');
        const message = text || (input ? input.value : '').trim();

        if (!message) {
            showToast('Please enter a message', 'warning');
            return;
        }

        // Clear input
        if (input) input.value = '';
        lastUserMessage = message;

        // Add user message to display
        addMessage('user', message);
        setProcessing(true);

        try {
            const resp = await fetch('/api/chat/send', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message })
            });

            if (!resp.ok) {
                const errBody = await resp.text().catch(() => '');
                throw new Error(errBody || `Server error: ${resp.status} ${resp.statusText}`);
            }

            const data = await resp.json();

            // Update local messages cache from server history
            if (data.history) {
                messages = data.history;
            }

            // Display assistant response
            if (data.response) {
                hideTyping();
                addMessage('assistant', data.response.content || '', {
                    time: data.response.time,
                    tool_calls: data.response.tool_calls
                });
            }

            // Display tool execution results
            if (data.tool_results && data.tool_results.length > 0) {
                for (const tr of data.tool_results) {
                    setExecuteStatus(`Ran ${tr.tool || 'tool'}...`);
                    addMessage('tool', tr.result, {
                        tool: tr.tool,
                        args: tr.args,
                        ok: tr.ok !== false,
                        time: tr.time || new Date()
                    });
                }
                setExecuteStatus(`Executed ${data.tool_results.length} tool(s)`);
            }

            // Auto-resize input
            autoResizeInput();

        } catch (err) {
            hideTyping();
            addMessage('error', err.message);
            showToast('Chat error: ' + err.message, 'error');
        } finally {
            setProcessing(false);
            setExecuteStatus('');
        }
    }

    // ── API: Clear History ─────────────────────────────────────────

    /**
     * Clear chat history on server and in UI
     */
    async function clearHistory() {
        try {
            const resp = await fetch('/api/chat/clear', { method: 'POST' });
            if (!resp.ok) throw new Error(`Failed to clear: ${resp.statusText}`);
            await resp.json();

            messages = [];
            lastUserMessage = null;
            renderMessages([]);
            showToast('Chat history cleared', 'success');
        } catch (err) {
            showToast('Error clearing chat: ' + err.message, 'error');
        }
    }

    // ── Re-Send Last Message ───────────────────────────────────────

    /**
     * Re-send the last user message
     */
    async function resendLastMessage() {
        if (isProcessing) return;
        if (!lastUserMessage) {
            showToast('No previous message to resend', 'warning');
            return;
        }
        await sendMessage(lastUserMessage);
    }

    // ── LLM Settings ───────────────────────────────────────────────

    /**
     * Load current LLM config from the server
     * @returns {Promise<object>} config object
     */
    async function loadLLMConfig() {
        try {
            const resp = await fetch('/api/llm/config');
            if (!resp.ok) throw new Error(`Failed to load config: ${resp.statusText}`);
            return await resp.json();
        } catch (err) {
            console.warn('ChatManager: loadLLMConfig error:', err.message);
            return null;
        }
    }

    /**
     * Save LLM config to the server
     * @param {object} config - config fields to save
     * @returns {Promise<object>} saved config
     */
    async function saveLLMConfig(config) {
        try {
            const resp = await fetch('/api/llm/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(config)
            });
            if (!resp.ok) throw new Error(`Failed to save config: ${resp.statusText}`);
            return await resp.json();
        } catch (err) {
            showToast('Error saving LLM config: ' + err.message, 'error');
            return null;
        }
    }

    /**
     * Show the LLM settings dialog
     */
    async function showSettingsDialog() {
        // Remove existing dialog if open
        removeSettingsDialog();

        // Load current config
        const config = await loadLLMConfig() || {};

        const overlay = document.createElement('div');
        overlay.className = 'chat-settings-overlay';
        overlay.id = 'chat-settings-overlay';

        overlay.innerHTML = `
            <div class="chat-settings-dialog">
                <div class="chat-settings-header">
                    <span>⚙️ LLM Settings</span>
                    <button class="chat-settings-close" title="Close">✕</button>
                </div>
                <div class="chat-settings-body">
                    <label>
                        <span>Provider</span>
                        <select id="llm-provider">
                            <option value="openai"${config.provider === 'openai' ? ' selected' : ''}>OpenAI</option>
                            <option value="anthropic"${config.provider === 'anthropic' ? ' selected' : ''}>Anthropic</option>
                            <option value="ollama"${config.provider === 'ollama' ? ' selected' : ''}>Ollama</option>
                            <option value="custom"${config.provider === 'custom' ? ' selected' : ''}>Custom</option>
                        </select>
                    </label>
                    <label>
                        <span>API Key</span>
                        <input type="password" id="llm-api-key" placeholder="sk-..." value="${escapeAttr(config.api_key || '')}">
                        ${config.api_key_masked ? `<span class="hint">Current: ${escapeHTML(config.api_key_masked)}</span>` : ''}
                    </label>
                    <label>
                        <span>API Base URL</span>
                        <input type="text" id="llm-api-base" placeholder="https://api.openai.com/v1" value="${escapeAttr(config.api_base || '')}">
                    </label>
                    <label>
                        <span>Model</span>
                        <input type="text" id="llm-model" placeholder="gpt-4o-mini" value="${escapeAttr(config.model || '')}">
                    </label>
                    <label>
                        <span>Temperature</span>
                        <input type="number" id="llm-temperature" min="0" max="2" step="0.1" value="${config.temperature !== undefined ? config.temperature : '0.7'}">
                    </label>
                    <label>
                        <span>Max Tokens</span>
                        <input type="number" id="llm-max-tokens" min="256" max="128000" step="256" value="${config.max_tokens || '4096'}">
                    </label>
                    <label class="full-width">
                        <span>System Prompt</span>
                        <textarea id="llm-system-prompt" rows="4" placeholder="You are a helpful coding assistant...">${escapeHTML(config.system_prompt || '')}</textarea>
                    </label>
                </div>
                <div class="chat-settings-footer">
                    <button class="btn-cancel" id="llm-settings-cancel">Cancel</button>
                    <button class="btn-confirm" id="llm-settings-save">Save</button>
                </div>
            </div>
        `;

        document.body.appendChild(overlay);
        settingsDialogEl = overlay;

        // Inject inline styles for the settings dialog
        injectSettingsStyles();

        // Bind events
        overlay.querySelector('.chat-settings-close').addEventListener('click', removeSettingsDialog);
        overlay.querySelector('#llm-settings-cancel').addEventListener('click', removeSettingsDialog);
        overlay.querySelector('#llm-settings-save').addEventListener('click', async () => {
            const newConfig = {
                provider:     overlay.querySelector('#llm-provider').value,
                api_key:      overlay.querySelector('#llm-api-key').value,
                api_base:     overlay.querySelector('#llm-api-base').value.trim(),
                model:        overlay.querySelector('#llm-model').value.trim(),
                temperature:  parseFloat(overlay.querySelector('#llm-temperature').value) || 0.7,
                max_tokens:   parseInt(overlay.querySelector('#llm-max-tokens').value, 10) || 4096,
                system_prompt: overlay.querySelector('#llm-system-prompt').value.trim()
            };

            const result = await saveLLMConfig(newConfig);
            if (result) {
                showToast('LLM settings saved', 'success');
                removeSettingsDialog();
            }
        });

        // Close on overlay click (outside dialog)
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) removeSettingsDialog();
        });

        // Focus the first input
        const firstInput = overlay.querySelector('input, select, textarea');
        if (firstInput) setTimeout(() => firstInput.focus(), 100);
    }

    /**
     * Remove the settings dialog if present
     */
    function removeSettingsDialog() {
        if (settingsDialogEl) {
            settingsDialogEl.remove();
            settingsDialogEl = null;
        }
    }

    /**
     * Inject CSS styles for the settings dialog (only once)
     */
    let _settingsStylesInjected = false;
    function injectSettingsStyles() {
        if (_settingsStylesInjected) return;
        _settingsStylesInjected = true;

        const style = document.createElement('style');
        style.textContent = `
            .chat-settings-overlay {
                position: fixed;
                inset: 0;
                background: rgba(0, 0, 0, 0.6);
                z-index: 500;
                display: flex;
                align-items: center;
                justify-content: center;
                padding: 16px;
                animation: fadeIn 0.2s ease;
            }
            @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }

            .chat-settings-dialog {
                background: var(--bg-surface);
                border: 1px solid var(--border);
                border-radius: var(--radius);
                width: 100%;
                max-width: 380px;
                max-height: 85vh;
                display: flex;
                flex-direction: column;
                box-shadow: 0 16px 48px rgba(0,0,0,0.5);
                overflow: hidden;
            }
            .chat-settings-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
                padding: 14px 16px 8px;
                font-weight: 600;
                font-size: 15px;
                color: var(--text-primary);
                flex-shrink: 0;
            }
            .chat-settings-close {
                width: 28px;
                height: 28px;
                border: none;
                background: none;
                color: var(--text-muted);
                font-size: 16px;
                cursor: pointer;
                border-radius: var(--radius-sm);
                display: flex;
                align-items: center;
                justify-content: center;
            }
            .chat-settings-close:active { background: var(--bg-hover); }
            .chat-settings-body {
                padding: 8px 16px 14px;
                overflow-y: auto;
                flex: 1;
                display: flex;
                flex-direction: column;
                gap: 10px;
            }
            .chat-settings-body label {
                display: flex;
                flex-direction: column;
                gap: 4px;
                font-size: 12px;
                color: var(--text-secondary);
            }
            .chat-settings-body label.full-width { flex: 1; }
            .chat-settings-body label span {
                font-weight: 500;
                color: var(--text-primary);
                font-size: 12px;
            }
            .chat-settings-body label .hint {
                font-size: 10px;
                color: var(--text-muted);
                font-style: italic;
            }
            .chat-settings-body input,
            .chat-settings-body select,
            .chat-settings-body textarea {
                width: 100%;
                padding: 8px 10px;
                border: 1px solid var(--border);
                background: var(--bg-tertiary);
                color: var(--text-primary);
                border-radius: var(--radius-sm);
                font-size: 13px;
                font-family: var(--font-mono);
            }
            .chat-settings-body input:focus,
            .chat-settings-body select:focus,
            .chat-settings-body textarea:focus {
                border-color: var(--accent);
                outline: none;
            }
            .chat-settings-body textarea {
                resize: vertical;
                min-height: 80px;
            }
            .chat-settings-footer {
                display: flex;
                justify-content: flex-end;
                gap: 8px;
                padding: 8px 16px 14px;
                flex-shrink: 0;
            }
            .chat-settings-footer button {
                padding: 8px 16px;
                border: none;
                border-radius: var(--radius-sm);
                cursor: pointer;
                font-size: 13px;
                font-weight: 500;
            }
            .chat-settings-footer .btn-cancel {
                background: var(--bg-hover);
                color: var(--text-secondary);
            }
            .chat-settings-footer .btn-cancel:active { background: var(--bg-active); }
            .chat-settings-footer .btn-confirm {
                background: var(--accent);
                color: var(--bg-primary);
            }
            .chat-settings-footer .btn-confirm:active { background: var(--accent-hover); }

            /* Code block wrapper with copy button */
            .code-block-wrapper {
                position: relative;
                margin: 6px 0;
                border-radius: var(--radius-sm);
                overflow: hidden;
            }
            .code-block-wrapper pre {
                margin: 0 !important;
                border-radius: var(--radius-sm) !important;
            }
            .code-block-wrapper .code-lang {
                position: absolute;
                top: 4px;
                left: 8px;
                font-size: 10px;
                color: var(--text-muted);
                font-family: var(--font-mono);
                z-index: 1;
                pointer-events: none;
            }
            .code-copy-btn {
                position: absolute;
                top: 4px;
                right: 4px;
                border: none;
                background: var(--bg-active);
                color: var(--text-secondary);
                font-size: 12px;
                padding: 2px 6px;
                border-radius: var(--radius-sm);
                cursor: pointer;
                opacity: 0.7;
                z-index: 1;
                line-height: 1;
            }
            .code-copy-btn:hover { opacity: 1; }
            .code-copy-btn:active { transform: scale(0.9); }

            /* Tool message details */
            .tool-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 8px;
            }
            .tool-status {
                font-size: 12px;
                font-weight: bold;
            }
            .tool-ok { color: var(--green); }
            .tool-fail { color: var(--red); }
            .tool-args {
                font-family: var(--font-mono);
                font-size: 11px;
                color: var(--text-muted);
                margin-top: 4px;
                padding: 4px 6px;
                background: rgba(0,0,0,0.2);
                border-radius: var(--radius-sm);
                word-break: break-all;
            }

            /* Chat role badge */
            .chat-role-badge {
                font-size: 10px;
                color: var(--mauve);
                margin-bottom: 4px;
                font-weight: 500;
            }

            /* Re-send button */
            .chat-resend-btn {
                display: inline-flex;
                align-items: center;
                gap: 4px;
                border: none;
                background: none;
                color: var(--text-muted);
                font-size: 11px;
                cursor: pointer;
                padding: 2px 6px;
                border-radius: var(--radius-sm);
                margin-top: 4px;
            }
            .chat-resend-btn:hover { color: var(--accent); }
            .chat-resend-btn:active { background: var(--bg-hover); }
        `;
        document.head.appendChild(style);
    }

    // ── Input Auto-Resize ──────────────────────────────────────────

    /**
     * Auto-resize the chat textarea based on content
     */
    function autoResizeInput() {
        const input = document.getElementById('chat-input');
        if (!input) return;
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
    }

    // ── Wire Up Events ─────────────────────────────────────────────

    /**
     * Bind all DOM events for the chat interface
     */
    function wireEvents() {
        // Send button
        const sendBtn = document.getElementById('chat-send');
        if (sendBtn) {
            sendBtn.addEventListener('click', (e) => {
                e.preventDefault();
                sendMessage();
            });
        }

        // Chat input
        const input = document.getElementById('chat-input');
        if (input) {
            // Enter to send, Shift+Enter for newline
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
                    e.preventDefault();
                    sendMessage();
                }
            });

            // Auto-resize on input
            input.addEventListener('input', autoResizeInput);
        }

        // Clear button
        const clearBtn = document.getElementById('chat-clear');
        if (clearBtn) {
            clearBtn.addEventListener('click', (e) => {
                e.preventDefault();
                clearHistory();
            });
        }

        // Settings button in chat header — create if not present
        wireSettingsButton();

        // Delegate click events on chat messages for code copy, resend, etc.
        const msgContainer = document.getElementById('chat-messages');
        if (msgContainer) {
            msgContainer.addEventListener('click', (e) => {
                // Code copy buttons
                if (e.target.classList.contains('code-copy-btn')) {
                    // Handled by bindCopyButtons above
                    return;
                }
                // Re-send button (delegated)
                if (e.target.closest('.chat-resend-btn')) {
                    resendLastMessage();
                }
            });
        }
    }

    /**
     * Add a settings gear button to the chat sidebar header if not present
     */
    function wireSettingsButton() {
        const header = document.getElementById('sidebar-right-header');
        if (!header) return;

        // Check if settings button already exists
        if (header.querySelector('.chat-settings-btn')) return;

        // Find the button group div (contains clear and close buttons)
        const btnGroup = header.querySelector('div');
        if (!btnGroup) return;

        const settingsBtn = document.createElement('button');
        settingsBtn.className = 'chat-settings-btn';
        settingsBtn.title = 'LLM Settings';
        settingsBtn.textContent = '⚙️';
        settingsBtn.style.cssText = 'width:30px;height:30px;border:none;background:none;color:var(--text-secondary);font-size:16px;cursor:pointer;border-radius:var(--radius-sm);display:flex;align-items:center;justify-content:center;';
        settingsBtn.addEventListener('click', (e) => {
            e.preventDefault();
            showSettingsDialog();
        });
        settingsBtn.addEventListener('touchstart', () => {
            settingsBtn.style.background = 'var(--bg-hover)';
        });
        settingsBtn.addEventListener('touchend', () => {
            settingsBtn.style.background = 'none';
        });

        // Insert before the clear button
        const clearBtn = document.getElementById('chat-clear');
        if (clearBtn) {
            btnGroup.insertBefore(settingsBtn, clearBtn);
        } else {
            btnGroup.appendChild(settingsBtn);
        }
    }

    // ── Initialize ─────────────────────────────────────────────────

    /**
     * Initialize the ChatManager — load history and wire events
     */
    async function init() {
        wireEvents();
        await loadHistory();

        // Auto-resize input if present
        autoResizeInput();

        console.log('ChatManager: initialized');
    }

    // Auto-init when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // ── Public API ─────────────────────────────────────────────────
    return {
        renderMessages,
        addMessage,
        sendMessage,
        clearHistory,
        loadHistory,
        scrollToBottom,
        showSettingsDialog,
        removeSettingsDialog,
        loadLLMConfig,
        saveLLMConfig,
        resendLastMessage,
        showTyping,
        hideTyping,
        setProcessing,
        setExecuteStatus,
        renderMarkdownLite,
        bindCopyButtons,
        autoResizeInput,

        // Getters
        get isProcessing() { return isProcessing; },
        get messages() { return messages.slice(); },
        get lastUserMessage() { return lastUserMessage; }
    };
})();

// Expose globally
window.ChatManager = ChatManager;
