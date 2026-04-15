/**
 * GitManager - Git operations for PhoneIDE
 * Works with Flask backend on port 1239
 */
const GitManager = (() => {
    'use strict';

    // ── State ──────────────────────────────────────────────────────
    let currentBranch = '';
    let statusData = null;
    let logData = [];
    let branchData = [];

    // ── API: Status ────────────────────────────────────────────────

    /**
     * Refresh git status and update UI
     */
    async function refreshStatus() {
        try {
            const resp = await fetch('/api/git/status');
            if (!resp.ok) throw new Error(`Failed to get status: ${resp.statusText}`);
            const data = await resp.json();
            statusData = data;
            renderChangesList(data);
            updateStatusBar(data);
            return data;
        } catch (err) {
            showToast(`Git status error: ${err.message}`, 'error');
            return null;
        }
    }

    // ── API: Log ───────────────────────────────────────────────────

    /**
     * Refresh commit log and render
     */
    async function refreshLog() {
        try {
            const resp = await fetch('/api/git/log');
            if (!resp.ok) throw new Error(`Failed to get log: ${resp.statusText}`);
            const data = await resp.json();
            logData = Array.isArray(data) ? data : (data.commits || []);
            renderLogList(logData);
            return logData;
        } catch (err) {
            showToast(`Git log error: ${err.message}`, 'error');
            return [];
        }
    }

    // ── API: Branches ──────────────────────────────────────────────

    /**
     * Refresh branch info
     */
    async function refreshBranches() {
        try {
            const resp = await fetch('/api/git/branch');
            if (!resp.ok) throw new Error(`Failed to get branches: ${resp.statusText}`);
            const data = await resp.json();
            branchData = Array.isArray(data) ? data : (data.branches || []);
            currentBranch = data.current || data.current_branch || '';
            updateBranchDisplay();
            return data;
        } catch (err) {
            showToast(`Git branch error: ${err.message}`, 'error');
            return [];
        }
    }

    // ── API: Clone ─────────────────────────────────────────────────

    /**
     * Clone a repository
     */
    async function clone(url) {
        if (!url) {
            url = await promptDialog('Clone Repository', 'Enter repository URL:', 'https://github.com/user/repo.git');
            if (!url) return;
        }

        showToast('Cloning repository...', 'info');

        try {
            const resp = await fetch('/api/git/clone', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url })
            });
            if (!resp.ok) throw new Error(`Clone failed: ${resp.statusText}`);
            const data = await resp.json();

            showToast('Repository cloned successfully', 'success');

            // Refresh file list
            if (window.FileManager) {
                await window.FileManager.refresh();
            }
            await refresh();

            return data;
        } catch (err) {
            showToast(`Clone error: ${err.message}`, 'error');
        }
    }

    // ── API: Pull ──────────────────────────────────────────────────

    /**
     * Pull from remote
     */
    async function pull() {
        showToast('Pulling changes...', 'info');

        try {
            const resp = await fetch('/api/git/pull', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            if (!resp.ok) throw new Error(`Pull failed: ${resp.statusText}`);
            const data = await resp.json();

            showToast('Pull successful', 'success');
            await refresh();

            // Refresh file list
            if (window.FileManager) {
                await window.FileManager.refresh();
            }

            return data;
        } catch (err) {
            showToast(`Pull error: ${err.message}`, 'error');
        }
    }

    // ── API: Push ──────────────────────────────────────────────────

    /**
     * Push to remote
     */
    async function push(setUpstream) {
        showToast('Pushing changes...', 'info');

        try {
            const body = {};
            if (setUpstream !== undefined) {
                body.set_upstream = setUpstream;
            }

            const resp = await fetch('/api/git/push', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            if (!resp.ok) throw new Error(`Push failed: ${resp.statusText}`);
            const data = await resp.json();

            showToast('Push successful', 'success');
            await refresh();

            return data;
        } catch (err) {
            // If push fails because there's no upstream, offer to set it
            if (err.message.includes('no upstream') || err.message.includes('403') || err.message.includes('500')) {
                const shouldSetUp = await confirmDialog(
                    'Push Failed',
                    'No upstream branch set. Push and set upstream?'
                );
                if (shouldSetUp) {
                    return push(true);
                }
            } else {
                showToast(`Push error: ${err.message}`, 'error');
            }
        }
    }

    // ── API: Sync (pull + push) ────────────────────────────────────

    /**
     * Pull then push
     */
    async function sync() {
        showToast('Syncing...', 'info');
        await pull();
        await push();
        showToast('Sync complete', 'success');
    }

    // ── API: Add ───────────────────────────────────────────────────

    /**
     * Stage files for commit
     * @param {string|string[]} paths - file path(s) to add
     */
    async function addFiles(paths) {
        if (!paths) {
            showToast('No files specified to add', 'warning');
            return;
        }

        // Normalize to array
        if (typeof paths === 'string') paths = [paths];

        try {
            const resp = await fetch('/api/git/add', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ paths })
            });
            if (!resp.ok) throw new Error(`Git add failed: ${resp.statusText}`);
            const data = await resp.json();

            showToast(`${paths.length} file(s) staged`, 'success');
            await refreshStatus();
            return data;
        } catch (err) {
            showToast(`Git add error: ${err.message}`, 'error');
        }
    }

    /**
     * Stage all changes
     */
    async function addAll() {
        try {
            const resp = await fetch('/api/git/add', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ paths: ['.'] })
            });
            if (!resp.ok) throw new Error(`Git add all failed: ${resp.statusText}`);
            const data = await resp.json();

            showToast('All changes staged', 'success');
            await refreshStatus();
            return data;
        } catch (err) {
            showToast(`Git add error: ${err.message}`, 'error');
        }
    }

    // ── API: Commit ────────────────────────────────────────────────

    /**
     * Commit staged changes
     * @param {string} message - commit message
     */
    async function commit(message) {
        if (!message) {
            const msgEl = document.getElementById('git-commit-msg');
            message = msgEl ? msgEl.value.trim() : '';
        }

        if (!message) {
            message = await promptDialog('Commit', 'Enter commit message:', 'Update');
            if (!message) return;
        }

        try {
            const resp = await fetch('/api/git/commit', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message })
            });
            if (!resp.ok) throw new Error(`Commit failed: ${resp.statusText}`);
            const data = await resp.json();

            // Clear commit message input
            const msgEl = document.getElementById('git-commit-msg');
            if (msgEl) msgEl.value = '';

            showToast('Committed successfully', 'success');
            await refresh();

            return data;
        } catch (err) {
            showToast(`Commit error: ${err.message}`, 'error');
        }
    }

    // ── API: Checkout ──────────────────────────────────────────────

    /**
     * Checkout a branch
     * @param {string} branch - branch name
     */
    async function checkout(branch) {
        if (!branch) {
            if (!branchData.length) {
                showToast('No branches available', 'warning');
                return;
            }
            const options = branchData.map(b => {
                const name = typeof b === 'string' ? b : b.name || b;
                const isCurrent = name.includes('*') || name === currentBranch;
                return { label: name.replace(/^\* /, ''), value: name.replace(/^\* /, '') };
            });

            const chosen = await choiceDialog('Checkout Branch', 'Select a branch:', options);
            if (!chosen) return;
            branch = chosen;
        }

        showToast(`Checking out ${branch}...`, 'info');

        try {
            const resp = await fetch('/api/git/checkout', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ branch })
            });
            if (!resp.ok) throw new Error(`Checkout failed: ${resp.statusText}`);
            const data = await resp.json();

            showToast(`Switched to ${branch}`, 'success');
            await refresh();

            // Refresh file list
            if (window.FileManager) {
                await window.FileManager.refresh();
            }

            return data;
        } catch (err) {
            showToast(`Checkout error: ${err.message}`, 'error');
        }
    }

    // ── API: Stash ─────────────────────────────────────────────────

    /**
     * Stash operations: push, pop, apply, list, drop
     * @param {string} action - stash action (push|pop|apply|list|drop)
     * @param {object} options - additional options
     */
    async function stash(action, options = {}) {
        if (!action) {
            const actions = [
                { label: 'Stash Changes', value: 'push' },
                { label: 'Pop Stash', value: 'pop' },
                { label: 'Apply Stash', value: 'apply' },
                { label: 'List Stashes', value: 'list' }
            ];
            action = await choiceDialog('Stash', 'Select action:', actions);
            if (!action) return;
        }

        try {
            const resp = await fetch('/api/git/stash', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action, ...options })
            });
            if (!resp.ok) throw new Error(`Stash ${action} failed: ${resp.statusText}`);
            const data = await resp.json();

            if (action === 'list') {
                // Display stash list
                const stashList = Array.isArray(data) ? data : (data.stashes || []);
                const msg = stashList.length ? stashList.map(s => typeof s === 'string' ? s : JSON.stringify(s)).join('\n') : 'No stashes';
                showToast(msg, 'info');
            } else {
                showToast(`Stash ${action} successful`, 'success');
            }

            await refreshStatus();
            return data;
        } catch (err) {
            showToast(`Stash error: ${err.message}`, 'error');
        }
    }

    // ── API: Diff ──────────────────────────────────────────────────

    /**
     * Get diff for a file
     * @param {string} filepath - optional; if omitted shows all changes
     */
    async function diff(filepath) {
        try {
            const url = filepath
                ? `/api/git/diff?path=${encodeURIComponent(filepath)}`
                : '/api/git/diff';
            const resp = await fetch(url);
            if (!resp.ok) throw new Error(`Diff failed: ${resp.statusText}`);
            const data = await resp.json();

            const diffText = data.diff || data.content || '';
            if (window.EditorManager && typeof window.EditorManager.showDiff === 'function') {
                window.EditorManager.showDiff(diffText, filepath || 'All changes');
            } else if (window.EditorManager && typeof window.EditorManager.setContent === 'function') {
                window.EditorManager.setContent(diffText, filepath || 'diff');
            } else {
                showToast(`Diff:\n${diffText.substring(0, 500)}`, 'info');
            }

            return diffText;
        } catch (err) {
            showToast(`Diff error: ${err.message}`, 'error');
        }
    }

    // ── Refresh All ────────────────────────────────────────────────

    /**
     * Refresh status + log + branches
     */
    async function refresh() {
        await Promise.all([
            refreshStatus(),
            refreshLog(),
            refreshBranches()
        ]);
    }

    // ── Rendering ──────────────────────────────────────────────────

    /**
     * Render the git changes list (modified, added, deleted, untracked files)
     */
    function renderChangesList(data) {
        const el = document.getElementById('git-changes-list');
        if (!el) return;

        let changes = [];

        // Normalize different possible response formats
        if (data.staged && Array.isArray(data.staged)) {
            changes.push(...data.staged.map(f => ({ ...f, category: 'staged' })));
        }
        if (data.modified && Array.isArray(data.modified)) {
            changes.push(...data.modified.map(f => ({ ...f, category: 'modified' })));
        }
        if (data.untracked && Array.isArray(data.untracked)) {
            changes.push(...data.untracked.map(f => ({ ...f, category: 'untracked' })));
        }
        if (data.deleted && Array.isArray(data.deleted)) {
            changes.push(...data.deleted.map(f => ({ ...f, category: 'deleted' })));
        }
        if (data.renamed && Array.isArray(data.renamed)) {
            changes.push(...data.renamed.map(f => ({ ...f, category: 'renamed' })));
        }

        // Also handle flat array format: [{path, status, ...}, ...]
        if (changes.length === 0 && Array.isArray(data.changes)) {
            changes = data.changes;
        }
        if (changes.length === 0 && Array.isArray(data.files)) {
            changes = data.files;
        }
        if (changes.length === 0 && Array.isArray(data)) {
            changes = data;
        }

        if (changes.length === 0) {
            el.innerHTML = '<div class="git-no-changes">No changes</div>';
            return;
        }

        let html = '';
        for (const change of changes) {
            const path = change.path || change.file || change.name || '';
            const status = change.status || change.category || '?';
            const icon = getStatusIcon(status);
            const escapedPath = escapeHTML(path);

            html += `
                <div class="git-change-item" data-path="${escapeAttr(path)}" data-status="${escapeAttr(status)}">
                    <span class="git-change-icon">${icon}</span>
                    <span class="git-change-path">${escapedPath}</span>
                    <span class="git-change-status">${escapeHTML(status)}</span>
                </div>`;
        }

        el.innerHTML = html;

        // Bind click events on change items
        el.querySelectorAll('.git-change-item').forEach(item => {
            item.addEventListener('click', () => {
                const path = item.dataset.path;
                diff(path);
            });

            // Long-press context menu
            let timer = null;
            item.addEventListener('touchstart', (e) => {
                timer = setTimeout(() => {
                    e.preventDefault();
                    showChangeContextMenu(e.touches[0].clientX, e.touches[0].clientY, item.dataset.path, item.dataset.status);
                }, 500);
            }, { passive: false });
            item.addEventListener('touchend', () => clearTimeout(timer));
            item.addEventListener('touchmove', () => clearTimeout(timer));
            item.addEventListener('contextmenu', (e) => {
                e.preventDefault();
                showChangeContextMenu(e.clientX, e.clientY, item.dataset.path, item.dataset.status);
            });
        });
    }

    /**
     * Render commit log
     */
    function renderLogList(commits) {
        const el = document.getElementById('git-log-list');
        if (!el) return;

        if (!commits || commits.length === 0) {
            el.innerHTML = '<div class="git-no-log">No commits yet</div>';
            return;
        }

        let html = '';
        for (const commit of commits) {
            const hash = commit.hash || commit.oid || commit.id || '';
            const shortHash = hash.substring(0, 7);
            const message = commit.message || commit.msg || '';
            const author = commit.author || '';
            const date = commit.date || commit.timestamp || '';

            html += `
                <div class="git-log-item" data-hash="${escapeAttr(hash)}">
                    <div class="git-log-header">
                        <span class="git-log-hash">${escapeHTML(shortHash)}</span>
                        <span class="git-log-author">${escapeHTML(author)}</span>
                    </div>
                    <div class="git-log-message">${escapeHTML(message.split('\n')[0])}</div>
                    <div class="git-log-date">${escapeHTML(date)}</div>
                </div>`;
        }

        el.innerHTML = html;
    }

    /**
     * Update the branch display
     */
    function updateBranchDisplay() {
        const branchEl = document.getElementById('git-current-branch');
        if (branchEl) {
            branchEl.textContent = currentBranch || 'no branch';
            branchEl.title = currentBranch || 'no branch';
        }
    }

    /**
     * Update the status count badge
     */
    function updateStatusBar(data) {
        const countEl = document.getElementById('git-status-count');
        if (!countEl) return;

        let count = 0;
        if (data.staged) count += data.staged.length;
        if (data.modified) count += data.modified.length;
        if (data.untracked) count += data.untracked.length;
        if (data.deleted) count += data.deleted.length;
        if (data.changes) count += data.changes.length;
        if (data.files) count += data.files.length;

        countEl.textContent = count > 0 ? `${count} change${count !== 1 ? 's' : ''}` : 'clean';
        countEl.className = count > 0 ? 'git-dirty' : 'git-clean';
    }

    // ── Context Menu for Changes ───────────────────────────────────

    function showChangeContextMenu(x, y, path, status) {
        removeChangeContextMenu();

        const menu = document.createElement('div');
        menu.className = 'context-menu visible';
        menu.style.left = `${Math.min(x, window.innerWidth - 200)}px`;
        menu.style.top = `${Math.min(y, window.innerHeight - 200)}px`;

        const items = [];

        items.push({ label: 'View Diff', action: () => diff(path) });
        items.push({ label: 'Stage File', action: () => addFiles(path) });
        items.push({ label: 'Open File', action: () => {
            if (window.FileManager) window.FileManager.openFile(path);
        }});

        menu.innerHTML = items.map(item =>
            `<button class="context-menu-item">${escapeHTML(item.label)}</button>`
        ).join('');

        const buttons = menu.querySelectorAll('.context-menu-item');
        items.forEach((item, i) => {
            buttons[i].addEventListener('click', () => {
                item.action();
                removeChangeContextMenu();
            });
        });

        document.body.appendChild(menu);

        setTimeout(() => {
            document.addEventListener('click', dismissChangeContextMenu, { once: true });
            document.addEventListener('touchstart', dismissChangeContextMenu, { once: true });
        }, 10);
    }

    function dismissChangeContextMenu(e) {
        if (!e.target.closest('.context-menu')) {
            removeChangeContextMenu();
        }
    }

    function removeChangeContextMenu() {
        document.querySelectorAll('.context-menu').forEach(m => m.remove());
    }

    // ── UI Helpers ─────────────────────────────────────────────────

    function getStatusIcon(status) {
        const s = (status || '').toLowerCase();
        if (s.includes('added') || s.includes('new') || s.includes('a ')) return '🟢';
        if (s.includes('modified') || s.includes('m ') || s.includes('changed')) return '🟡';
        if (s.includes('deleted') || s.includes('d ')) return '🔴';
        if (s.includes('renamed') || s.includes('r ')) return '🔵';
        if (s.includes('untracked') || s.includes('?')) return '⚪';
        if (s.includes('staged') || s === 'staged') return '🟢';
        return '⚪';
    }

    function escapeHTML(str) {
        const div = document.createElement('div');
        div.textContent = str || '';
        return div.innerHTML;
    }

    function escapeAttr(str) {
        return (str || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /**
     * Simple prompt dialog (replaces window.prompt for mobile)
     */
    function promptDialog(title, label, defaultValue) {
        return new Promise((resolve) => {
            if (window.showPromptDialog) {
                window.showPromptDialog(title, label, defaultValue, resolve);
                return;
            }
            const result = window.prompt(`${title}\n${label}`, defaultValue);
            resolve(result);
        });
    }

    /**
     * Simple confirm dialog
     */
    function confirmDialog(title, message) {
        return new Promise((resolve) => {
            if (window.showConfirmDialog) {
                window.showConfirmDialog(title, message, resolve);
                return;
            }
            const result = window.confirm(`${title}\n${message}`);
            resolve(result);
        });
    }

    /**
     * Choice dialog — shows a list of options
     * Returns a promise resolving to the chosen value or null.
     */
    function choiceDialog(title, label, options) {
        return new Promise((resolve) => {
            if (window.showChoiceDialog) {
                window.showChoiceDialog(title, label, options, resolve);
                return;
            }
            // Fallback: join options and let user type one
            const optStr = options.map(o => o.value || o).join(', ');
            const result = window.prompt(`${title}\n${label}\n\nOptions: ${optStr}`, '');
            if (!result) return resolve(null);

            // Match input to an option
            const match = options.find(o => {
                const val = o.value || o;
                return val.toLowerCase() === result.trim().toLowerCase();
            });
            resolve(match ? (match.value || match) : result.trim());
        });
    }

    // ── Wire Up Buttons ────────────────────────────────────────────

    function wireButtons() {
        const buttonMap = {
            'git-clone': () => clone(),
            'git-pull': () => pull(),
            'git-push': () => push(),
            'git-sync': () => sync(),
            'git-refresh': () => refresh(),
            'git-commit-btn': () => commit(),
            'git-add-all-btn': () => addAll(),
            'git-stash-btn': () => stash(),
            'git-checkout-btn': () => checkout(),
        };

        for (const [id, handler] of Object.entries(buttonMap)) {
            const btn = document.getElementById(id);
            if (btn) {
                btn.addEventListener('click', (e) => {
                    e.preventDefault();
                    handler();
                });
            }
        }

        // Commit on Ctrl/Cmd+Enter in message input
        const msgEl = document.getElementById('git-commit-msg');
        if (msgEl) {
            msgEl.addEventListener('keydown', (e) => {
                if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                    e.preventDefault();
                    commit();
                }
            });
        }
    }

    // ── Initialize ─────────────────────────────────────────────────

    function init() {
        wireButtons();
        // Initial refresh
        refresh();
    }

    // Auto-init when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // ── Public API ─────────────────────────────────────────────────
    return {
        refreshStatus,
        refreshLog,
        refreshBranches,
        refresh,
        clone,
        pull,
        push,
        sync,
        addFiles,
        addAll,
        commit,
        checkout,
        stash,
        diff,

        // Getters
        get currentBranch() { return currentBranch; },
        get status() { return statusData; },
        get log() { return logData; },
        get branches() { return branchData; }
    };
})();
