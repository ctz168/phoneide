#!/usr/bin/env python3
"""
PhoneIDE - Mobile-Optimized Web IDE for Termux/Ubuntu
Lightweight Python server on port 1239
"""

import os
import sys
import json
import subprocess
import threading
import time
import signal
import re
import shutil
import traceback
import uuid
import stat
from pathlib import Path
from datetime import datetime
from functools import wraps
from flask import Flask, jsonify, request, send_from_directory, send_file, Response
from flask_cors import CORS

# ==================== Config ====================
# Get absolute path of the server script directory
SERVER_DIR = os.path.dirname(os.path.abspath(__file__))

app = Flask(__name__, static_folder=os.path.join(SERVER_DIR, 'static'), static_url_path='')
CORS(app)

WORKSPACE = os.environ.get('PHONEIDE_WORKSPACE', os.path.expanduser('~/phoneide_workspace'))
PORT = int(os.environ.get('PHONEIDE_PORT', 1239))
HOST = os.environ.get('PHONEIDE_HOST', '0.0.0.0')
APP_VERSION = os.environ.get('PHONEIDE_VERSION', '3.0.39')

# Config file
CONFIG_DIR = os.path.expanduser('~/.phoneide')
CONFIG_FILE = os.path.join(CONFIG_DIR, 'config.json')
LLM_CONFIG_FILE = os.path.join(CONFIG_DIR, 'llm_config.json')

# Chat history
CHAT_HISTORY_FILE = os.path.join(CONFIG_DIR, 'chat_history.json')

os.makedirs(CONFIG_DIR, exist_ok=True)
os.makedirs(WORKSPACE, exist_ok=True)

# ==================== Config Management ====================
def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, 'r') as f:
            return json.load(f)
    return {
        'workspace': WORKSPACE,
        'venv_path': '',
        'compiler': 'python3',
        'theme': 'claude',
        'font_size': 14,
        'tab_size': 4,
        'show_line_numbers': True,
    }

def save_config(config):
    with open(CONFIG_FILE, 'w') as f:
        json.dump(config, f, indent=2, ensure_ascii=False)

def load_llm_config():
    if os.path.exists(LLM_CONFIG_FILE):
        with open(LLM_CONFIG_FILE, 'r') as f:
            return json.load(f)
    return {
        'provider': 'openai',
        'api_key': '',
        'api_base': '',
        'model': 'gpt-4o-mini',
        'temperature': 0.7,
        'max_tokens': 4096,
        'system_prompt': 'You are a helpful coding assistant integrated in PhoneIDE.',
    }

def save_llm_config(config):
    with open(LLM_CONFIG_FILE, 'w') as f:
        json.dump(config, f, indent=2, ensure_ascii=False)

def load_chat_history():
    if os.path.exists(CHAT_HISTORY_FILE):
        with open(CHAT_HISTORY_FILE, 'r') as f:
            return json.load(f)
    return []

def save_chat_history(history):
    # Keep last 200 messages
    history = history[-200:]
    with open(CHAT_HISTORY_FILE, 'w') as f:
        json.dump(history, f, indent=2, ensure_ascii=False)

# ==================== Process Management ====================
running_processes = {}
process_outputs = {}

def run_process(cmd, cwd=None, timeout=300, proc_id=None):
    """Run a subprocess and capture output"""
    if not proc_id:
        proc_id = str(uuid.uuid4())[:8]

    process_outputs[proc_id] = []
    running_processes[proc_id] = {
        'process': None,
        'cwd': cwd,
        'running': False,
        'start_time': None,
    }

    def execute():
        try:
            env = os.environ.copy()
            config = load_config()
            if config.get('venv_path') and os.path.exists(config['venv_path']):
                venv_bin = os.path.join(config['venv_path'], 'bin')
                if os.path.exists(venv_bin):
                    env['PATH'] = venv_bin + ':' + env.get('PATH', '')
                    env['VIRTUAL_ENV'] = config['venv_path']

            running_processes[proc_id]['running'] = True
            running_processes[proc_id]['start_time'] = time.time()

            proc = subprocess.Popen(
                cmd,
                shell=True,
                cwd=cwd or config.get('workspace', WORKSPACE),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                env=env,
                text=True,
                bufsize=1,
            )
            running_processes[proc_id]['process'] = proc

            for line in iter(proc.stdout.readline, ''):
                if not running_processes[proc_id]['running']:
                    break
                output = line.rstrip('\n')
                process_outputs[proc_id].append({
                    'type': 'stdout',
                    'text': output,
                    'time': datetime.now().isoformat(),
                })

            proc.wait(timeout=5)
            code = proc.returncode

            process_outputs[proc_id].append({
                'type': 'status',
                'text': f'Process exited with code {code}',
                'exit_code': code,
                'time': datetime.now().isoformat(),
            })
        except subprocess.TimeoutExpired:
            process_outputs[proc_id].append({
                'type': 'error',
                'text': 'Process timed out',
                'time': datetime.now().isoformat(),
            })
        except Exception as e:
            process_outputs[proc_id].append({
                'type': 'error',
                'text': str(e),
                'time': datetime.now().isoformat(),
            })
        finally:
            running_processes[proc_id]['running'] = False

    t = threading.Thread(target=execute, daemon=True)
    t.start()
    return proc_id

def stop_process(proc_id):
    if proc_id in running_processes:
        proc = running_processes[proc_id]
        if proc['process'] and proc['running']:
            proc['running'] = False
            try:
                proc['process'].terminate()
                proc['process'].wait(timeout=3)
            except:
                try:
                    proc['process'].kill()
                except:
                    pass
            return True
    return False

# ==================== File Type Detection ====================
def get_file_type(filename):
    ext = os.path.splitext(filename)[1].lower()
    type_map = {
        '.py': 'python', '.js': 'javascript', '.ts': 'typescript',
        '.jsx': 'javascript', '.tsx': 'typescript',
        '.html': 'html', '.htm': 'html', '.css': 'css', '.scss': 'scss',
        '.json': 'json', '.xml': 'xml', '.yaml': 'yaml', '.yml': 'yaml',
        '.md': 'markdown', '.txt': 'text', '.sh': 'shell', '.bash': 'shell',
        '.c': 'c', '.cpp': 'cpp', '.h': 'c', '.hpp': 'cpp',
        '.java': 'java', '.kt': 'kotlin', '.swift': 'swift',
        '.go': 'go', '.rs': 'rust', '.rb': 'ruby', '.php': 'php',
        '.sql': 'sql', '.r': 'r', '.lua': 'lua', '.vim': 'vim',
        '.dockerfile': 'dockerfile', '.toml': 'toml', '.ini': 'ini',
        '.cfg': 'ini', '.conf': 'ini', '.log': 'text',
        '.env': 'shell', '.gitignore': 'text', '.editorconfig': 'ini',
    }
    # Check special filenames
    if filename == 'Dockerfile':
        return 'dockerfile'
    if filename == 'Makefile':
        return 'makefile'
    return type_map.get(ext, 'text')

def get_icon_for_file(filename):
    if os.path.isdir(filename) if isinstance(filename, str) else False:
        return 'folder'
    ext = os.path.splitext(filename)[1].lower()
    icon_map = {
        '.py': '🐍', '.js': '📜', '.ts': '📘', '.html': '🌐',
        '.css': '🎨', '.json': '📋', '.md': '📝', '.txt': '📄',
        '.sh': '⚡', '.yml': '⚙️', '.yaml': '⚙️', '.toml': '⚙️',
        '.gitignore': '🚫', '.env': '🔒',
        '.c': '🔧', '.cpp': '🔧', '.h': '🔧',
        '.java': '☕', '.go': '🐹', '.rs': '🦀', '.rb': '💎',
        '.sql': '🗃️', '.xml': '📰', '.svg': '🖼️',
        '.png': '🖼️', '.jpg': '🖼️', '.jpeg': '🖼️', '.gif': '🖼️',
    }
    if filename == 'Dockerfile':
        return '🐳'
    if filename == 'Makefile':
        return '🔨'
    if filename == 'README.md':
        return '📖'
    if filename.startswith('.'):
        return '⚙️'
    return icon_map.get(ext, '📄')

# ==================== Error Handler ====================
def handle_error(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        try:
            return f(*args, **kwargs)
        except Exception as e:
            traceback.print_exc()
            return jsonify({'error': str(e)}), 500
    return wrapper

# ==================== Routes ====================

# Serve frontend
@app.route('/')
def index():
    return send_from_directory(app.static_folder, 'index.html')

@app.route('/<path:path>')
def static_files(path):
    return send_from_directory(app.static_folder, path)

# ---- Health Check API ----
@app.route('/api/health', methods=['GET'])
def health_check():
    return jsonify({'status': 'ok', 'version': '1.0.0', 'port': PORT})

# ---- Config APIs ----
@app.route('/api/config', methods=['GET'])
@handle_error
def get_config():
    return jsonify(load_config())

@app.route('/api/config', methods=['POST'])
@handle_error
def update_config():
    data = request.json
    config = load_config()
    config.update(data)
    save_config(config)
    if config.get('workspace'):
        os.makedirs(config['workspace'], exist_ok=True)
    return jsonify({'ok': True})

@app.route('/api/llm/config', methods=['GET'])
@handle_error
def get_llm_config():
    cfg = load_llm_config()
    # Mask API key
    if cfg.get('api_key'):
        cfg['api_key_masked'] = cfg['api_key'][:8] + '...' + cfg['api_key'][-4:] if len(cfg['api_key']) > 12 else '***'
    else:
        cfg['api_key_masked'] = ''
    return jsonify(cfg)

@app.route('/api/llm/config', methods=['POST'])
@handle_error
def update_llm_config():
    config = request.json
    save_llm_config(config)
    return jsonify({'ok': True})

# ---- File APIs ----
@app.route('/api/files/list', methods=['GET'])
@handle_error
def list_files():
    path = request.args.get('path', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    target = os.path.join(base, path) if path else base
    target = os.path.realpath(target)

    # Security: must be under workspace
    if not target.startswith(os.path.realpath(base)):
        return jsonify({'error': 'Access denied'}), 403

    if not os.path.exists(target):
        return jsonify({'error': 'Path not found'}), 404

    items = []
    if os.path.isdir(target):
        try:
            for entry in sorted(os.listdir(target)):
                full = os.path.join(target, entry)
                try:
                    st = os.stat(full)
                    is_dir = os.path.isdir(full)
                    items.append({
                        'name': entry,
                        'path': os.path.relpath(full, base),
                        'is_dir': is_dir,
                        'size': st.st_size if not is_dir else 0,
                        'modified': datetime.fromtimestamp(st.st_mtime).isoformat(),
                        'icon': get_icon_for_file(entry),
                    })
                except (PermissionError, OSError):
                    pass
        except PermissionError:
            return jsonify({'error': 'Permission denied'}), 403
    else:
        items.append({
            'name': os.path.basename(target),
            'path': os.path.relpath(target, base),
            'is_dir': False,
            'size': os.path.getsize(target),
            'modified': datetime.fromtimestamp(os.path.getmtime(target)).isoformat(),
            'icon': get_icon_for_file(os.path.basename(target)),
        })

    return jsonify({'items': items, 'path': path, 'base': base})

@app.route('/api/files/read', methods=['GET'])
@handle_error
def read_file():
    path = request.args.get('path', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    target = os.path.realpath(os.path.join(base, path))

    if not target.startswith(os.path.realpath(base)):
        return jsonify({'error': 'Access denied'}), 403

    if not os.path.isfile(target):
        return jsonify({'error': 'File not found'}), 404

    # Limit file size (10MB)
    size = os.path.getsize(target)
    if size > 10 * 1024 * 1024:
        return jsonify({'error': 'File too large (>10MB)', 'size': size}), 413

    try:
        # Try to detect encoding
        with open(target, 'rb') as f:
            raw = f.read()

        encodings = ['utf-8', 'utf-8-sig', 'gbk', 'gb2312', 'latin-1']
        content = None
        used_encoding = 'utf-8'
        for enc in encodings:
            try:
                content = raw.decode(enc)
                used_encoding = enc
                break
            except (UnicodeDecodeError, LookupError):
                continue

        if content is None:
            content = raw.decode('utf-8', errors='replace')
            used_encoding = 'utf-8'

        return jsonify({
            'content': content,
            'path': path,
            'encoding': used_encoding,
            'type': get_file_type(os.path.basename(target)),
            'size': size,
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/files/save', methods=['POST'])
@handle_error
def save_file():
    data = request.json
    path = data.get('path', '')
    content = data.get('content', '')
    create = data.get('create', False)
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    target = os.path.realpath(os.path.join(base, path))
    if not target.startswith(os.path.realpath(base)):
        return jsonify({'error': 'Access denied'}), 403

    if not os.path.exists(target) and not create:
        # Auto-create file if it doesn't exist (IDE behavior)
        os.makedirs(os.path.dirname(target), exist_ok=True)

    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, 'w', encoding='utf-8') as f:
        f.write(content)

    return jsonify({'ok': True, 'path': path, 'saved_at': datetime.now().isoformat()})

@app.route('/api/files/create', methods=['POST'])
@handle_error
def create_file():
    data = request.json
    path = data.get('path', '')
    # Support both 'is_dir' (boolean) and 'type' (string 'file'|'directory')
    is_dir = data.get('is_dir', False)
    if not is_dir and data.get('type') == 'directory':
        is_dir = True
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    target = os.path.realpath(os.path.join(base, path))
    if not target.startswith(os.path.realpath(base)):
        return jsonify({'error': 'Access denied'}), 403

    if is_dir:
        os.makedirs(target, exist_ok=True)
    else:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if not os.path.exists(target):
            Path(target).touch()

    return jsonify({'ok': True, 'path': path})

@app.route('/api/files/delete', methods=['POST'])
@handle_error
def delete_file():
    data = request.json
    path = data.get('path', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    target = os.path.realpath(os.path.join(base, path))
    if not target.startswith(os.path.realpath(base)):
        return jsonify({'error': 'Access denied'}), 403

    if not os.path.exists(target):
        return jsonify({'error': 'Not found'}), 404

    if os.path.isdir(target):
        shutil.rmtree(target)
    else:
        os.remove(target)

    return jsonify({'ok': True})

@app.route('/api/files/rename', methods=['POST'])
@handle_error
def rename_file():
    data = request.json
    old_path = data.get('old_path', '')
    new_path = data.get('new_path', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    old_target = os.path.realpath(os.path.join(base, old_path))
    new_target = os.path.realpath(os.path.join(base, new_path))

    if not old_target.startswith(os.path.realpath(base)):
        return jsonify({'error': 'Access denied'}), 403
    if not new_target.startswith(os.path.realpath(base)):
        return jsonify({'error': 'Access denied'}), 403

    if not os.path.exists(old_target):
        return jsonify({'error': 'Source not found'}), 404

    os.makedirs(os.path.dirname(new_target), exist_ok=True)
    os.rename(old_target, new_target)

    return jsonify({'ok': True})

@app.route('/api/files/open_folder', methods=['POST'])
@handle_error
def open_folder():
    data = request.json
    path = data.get('path', WORKSPACE)
    if path and os.path.isdir(path):
        config = load_config()
        config['workspace'] = path
        save_config(config)
        return jsonify({'ok': True, 'workspace': path})
    return jsonify({'error': 'Invalid folder path'}), 400

# ---- Run/Execute APIs ----
@app.route('/api/run/execute', methods=['POST'])
@handle_error
def execute_code():
    data = request.json
    code = data.get('code', '')
    file_path = data.get('file_path', '')
    compiler = data.get('compiler', 'python3')
    args = data.get('args', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    if file_path:
        target = os.path.realpath(os.path.join(base, file_path))
        if not target.startswith(os.path.realpath(base)):
            return jsonify({'error': 'Access denied'}), 403
        cmd = f'{compiler} {shlex_quote(target)} {args}'
    else:
        # Write temp file
        tmp_file = os.path.join(base, '.phoneide_tmp.py')
        with open(tmp_file, 'w') as f:
            f.write(code)
        cmd = f'{compiler} {shlex_quote(tmp_file)} {args}'

    proc_id = run_process(cmd, cwd=base)
    return jsonify({'ok': True, 'proc_id': proc_id})

def shlex_quote(s):
    """Simple shell quoting"""
    return "'" + s.replace("'", "'\\''") + "'"

@app.route('/api/run/stop', methods=['POST'])
@handle_error
def stop_execution():
    data = request.json
    proc_id = data.get('proc_id', '')
    if proc_id and proc_id in running_processes:
        stopped = stop_process(proc_id)
        return jsonify({'ok': stopped})
    return jsonify({'ok': False})

@app.route('/api/run/processes', methods=['GET'])
@handle_error
def list_processes():
    """List all running and recent processes"""
    processes = []
    for pid, info in running_processes.items():
        start = info.get('start_time')
        running = info.get('running', False)
        uptime = ''
        if start and running:
            elapsed = time.time() - start
            mins, secs = divmod(int(elapsed), 60)
            hours, mins = divmod(mins, 60)
            if hours > 0:
                uptime = f'{hours}h {mins}m {secs}s'
            elif mins > 0:
                uptime = f'{mins}m {secs}s'
            else:
                uptime = f'{secs}s'
        processes.append({
            'id': pid,
            'running': running,
            'cwd': info.get('cwd', ''),
            'uptime': uptime,
            'start_time': start,
        })
    return jsonify({'processes': processes})

@app.route('/api/run/output', methods=['GET'])
@handle_error
def get_output():
    proc_id = request.args.get('proc_id', '')
    since = int(request.args.get('since', 0))

    if proc_id and proc_id in process_outputs:
        outputs = process_outputs[proc_id][since:]
        is_running = running_processes.get(proc_id, {}).get('running', False)
        return jsonify({
            'outputs': outputs,
            'since': len(process_outputs[proc_id]),
            'running': is_running,
        })
    return jsonify({'outputs': [], 'since': 0, 'running': False})

@app.route('/api/run/output/stream', methods=['GET'])
def stream_output():
    """SSE endpoint for real-time output"""
    proc_id = request.args.get('proc_id', '')

    def generate():
        idx = 0
        while True:
            if proc_id and proc_id in process_outputs:
                outputs = process_outputs[proc_id]
                if idx < len(outputs):
                    for item in outputs[idx:]:
                        yield f"data: {json.dumps(item)}\n\n"
                    idx = len(outputs)

                is_running = running_processes.get(proc_id, {}).get('running', False)
                if not is_running and idx > 0:
                    yield f"data: {json.dumps({'type': 'done'})}\n\n"
                    break

            time.sleep(0.1)

    return Response(generate(), mimetype='text/event-stream',
                    headers={'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no'})

# ---- Compiler/Venv APIs ----
@app.route('/api/compilers', methods=['GET'])
@handle_error
def list_compilers():
    compilers = []
    checks = [
        ('python3', 'Python 3', 'python3 --version'),
        ('python', 'Python', 'python --version'),
        ('node', 'Node.js', 'node --version'),
        ('gcc', 'GCC C', 'gcc --version | head -1'),
        ('g++', 'G++ C++', 'g++ --version | head -1'),
        ('go', 'Go', 'go version'),
        ('rustc', 'Rust', 'rustc --version'),
        ('ruby', 'Ruby', 'ruby --version'),
        ('lua', 'Lua', 'lua -v'),
        ('bash', 'Bash', 'bash --version | head -1'),
    ]
    for cmd, name, version_cmd in checks:
        try:
            result = subprocess.run(version_cmd, shell=True, capture_output=True, text=True, timeout=5)
            if result.returncode == 0:
                version = result.stdout.strip().split('\n')[0]
                compilers.append({'id': cmd, 'name': name, 'version': version})
        except:
            pass
    return jsonify({'compilers': compilers})

@app.route('/api/venv/create', methods=['POST'])
@handle_error
def create_venv():
    data = request.json
    path = data.get('path', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    if not path:
        path = os.path.join(base, '.venv')

    target = os.path.realpath(path)
    proc_id = run_process(f'python3 -m venv {shlex_quote(target)}', cwd=base)
    if proc_id:
        config['venv_path'] = target
        save_config(config)
    return jsonify({'ok': True, 'proc_id': proc_id, 'venv_path': target})

@app.route('/api/venv/list', methods=['GET'])
@handle_error
def list_venvs():
    config = load_config()
    base = config.get('workspace', WORKSPACE)
    venvs = []
    # Search for common venv directories
    for root, dirs, files in os.walk(base):
        # Skip hidden dirs except .venv
        dirs[:] = [d for d in dirs if not d.startswith('.') or d == '.venv']
        # Limit depth
        depth = root[len(base):].count(os.sep)
        if depth > 2:
            continue
        if 'pyvenv.cfg' in files:
            rel = os.path.relpath(root, base)
            venvs.append({
                'path': rel,
                'full_path': root,
                'active': config.get('venv_path') == root,
                'name': os.path.basename(root),
            })
    return jsonify({'venvs': venvs, 'current': config.get('venv_path', '')})

@app.route('/api/venv/activate', methods=['POST'])
@handle_error
def activate_venv():
    data = request.json
    path = data.get('path', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    target = os.path.realpath(os.path.join(base, path))
    if os.path.exists(os.path.join(target, 'pyvenv.cfg')):
        config['venv_path'] = target
        save_config(config)
        return jsonify({'ok': True, 'venv_path': target})
    return jsonify({'error': 'Invalid venv directory'}), 400

@app.route('/api/venv/packages', methods=['GET'])
@handle_error
def list_packages():
    config = load_config()
    venv_path = config.get('venv_path', '')
    if venv_path and os.path.exists(venv_path):
        pip = os.path.join(venv_path, 'bin', 'pip')
        if not os.path.exists(pip):
            pip = 'pip3'
        result = subprocess.run(f'{pip} list --format=json', shell=True, capture_output=True, text=True, timeout=30)
        if result.returncode == 0:
            try:
                packages = json.loads(result.stdout)
                return jsonify({'packages': packages})
            except:
                pass
    return jsonify({'packages': []})

# ---- Git APIs ----
def git_cmd(args, cwd=None, timeout=60):
    config = load_config()
    base = cwd or config.get('workspace', WORKSPACE)
    cmd = f'git -C {shlex_quote(base)} {args}'
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return {'ok': result.returncode == 0, 'stdout': result.stdout, 'stderr': result.stderr, 'code': result.returncode}
    except subprocess.TimeoutExpired:
        return {'ok': False, 'stdout': '', 'stderr': 'Command timed out', 'code': -1}
    except Exception as e:
        return {'ok': False, 'stdout': '', 'stderr': str(e), 'code': -1}

@app.route('/api/git/status', methods=['GET'])
@handle_error
def git_status():
    r = git_cmd('status --porcelain -b')
    if not r['ok']:
        return jsonify({'error': r['stderr']}), 500
    lines = r['stdout'].strip().split('\n') if r['stdout'].strip() else []
    branch = ''
    changed = []
    staged = []
    untracked = []
    for line in lines:
        if line.startswith('##'):
            branch = line[2:].strip().split('...')[0]
            continue
        if len(line) >= 2:
            status = line[:2]
            filepath = line[3:]
            if status[0] == '?' and status[1] == '?':
                untracked.append({'path': filepath, 'status': 'untracked'})
            elif status[0] != ' ':
                staged.append({'path': filepath, 'status': 'staged', 'change': status[0]})
            else:
                changed.append({'path': filepath, 'status': 'modified', 'change': status[1]})
    return jsonify({
        'branch': branch,
        'changed': changed,
        'staged': staged,
        'untracked': untracked,
    })

@app.route('/api/git/log', methods=['GET'])
@handle_error
def git_log():
    count = request.args.get('count', 20)
    r = git_cmd(f'log --oneline --decorate -n {count} --format="%H|%an|%ae|%at|%s"')
    if not r['ok']:
        return jsonify({'commits': [], 'error': r['stderr']})
    commits = []
    for line in r['stdout'].strip().split('\n'):
        if line and '|' in line:
            parts = line.split('|', 4)
            if len(parts) == 5:
                commits.append({
                    'hash': parts[0][:8],
                    'full_hash': parts[0],
                    'author': parts[1],
                    'email': parts[2],
                    'date': datetime.fromtimestamp(int(parts[3])).isoformat(),
                    'message': parts[4],
                })
    return jsonify({'commits': commits})

@app.route('/api/git/branch', methods=['GET'])
@handle_error
def git_branch():
    r = git_cmd('branch -a')
    if not r['ok']:
        return jsonify({'branches': [], 'error': r['stderr']})
    branches = []
    for line in r['stdout'].strip().split('\n'):
        if line:
            active = line.startswith('*')
            name = line.lstrip('* ').strip()
            branches.append({'name': name, 'active': active})
    return jsonify({'branches': branches})

@app.route('/api/git/checkout', methods=['POST'])
@handle_error
def git_checkout():
    data = request.json
    branch = data.get('branch', '')
    if not branch:
        return jsonify({'error': 'Branch name required'}), 400
    r = git_cmd(f'checkout {shlex_quote(branch)}')
    if not r['ok']:
        return jsonify({'error': r['stderr']}), 500
    return jsonify({'ok': True})

@app.route('/api/git/add', methods=['POST'])
@handle_error
def git_add():
    try:
        data = request.json or {}
    except:
        data = {}
    paths = data.get('paths', [])
    if not paths:
        r = git_cmd('add -A')
    else:
        files = ' '.join(shlex_quote(p) for p in paths)
        r = git_cmd(f'add {files}')
    return jsonify({'ok': r['ok'], 'stderr': r['stderr']})

@app.route('/api/git/commit', methods=['POST'])
@handle_error
def git_commit():
    try:
        data = request.json or {}
    except:
        data = {}
    message = data.get('message', '')
    if not message:
        return jsonify({'error': 'Commit message required'}), 400
    r = git_cmd(f'commit -m {shlex_quote(message)}')
    if not r['ok']:
        return jsonify({'error': r['stderr']}), 500
    return jsonify({'ok': True})

@app.route('/api/git/push', methods=['POST'])
@handle_error
def git_push():
    data = request.json
    remote = data.get('remote', 'origin')
    branch = data.get('branch', '')
    set_upstream = data.get('set_upstream', False)
    cmd = f'push {remote} {branch}'
    if set_upstream:
        cmd = f'push -u {remote} {branch}'
    r = git_cmd(cmd, timeout=120)
    return jsonify({'ok': r['ok'], 'stdout': r['stdout'], 'stderr': r['stderr']})

@app.route('/api/git/pull', methods=['POST'])
@handle_error
def git_pull():
    data = request.json
    remote = data.get('remote', 'origin')
    branch = data.get('branch', '')
    r = git_cmd(f'pull {remote} {branch}', timeout=120)
    return jsonify({'ok': r['ok'], 'stdout': r['stdout'], 'stderr': r['stderr']})

@app.route('/api/git/clone', methods=['POST'])
@handle_error
def git_clone():
    data = request.json
    url = data.get('url', '')
    path = data.get('path', '')
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    if not url:
        return jsonify({'error': 'URL required'}), 400

    if path:
        target = os.path.join(base, path)
    else:
        # Extract repo name from URL
        name = url.rstrip('/').split('/')[-1]
        if name.endswith('.git'):
            name = name[:-4]
        target = os.path.join(base, name)

    r = git_cmd(f'clone {shlex_quote(url)} {shlex_quote(target)}', cwd=base, timeout=300)
    if r['ok']:
        return jsonify({'ok': True, 'path': os.path.relpath(target, base)})
    return jsonify({'error': r['stderr']}), 500

@app.route('/api/git/remote', methods=['GET'])
@handle_error
def git_remote():
    r = git_cmd('remote -v')
    if not r['ok']:
        return jsonify({'remotes': []})
    remotes = []
    for line in r['stdout'].strip().split('\n'):
        if line:
            parts = line.split('\t')
            if len(parts) == 2:
                name, url = parts
                url = url.split(' ')[0]
                remotes.append({'name': name.strip(), 'url': url})
    return jsonify({'remotes': remotes})

@app.route('/api/git/diff', methods=['GET'])
@handle_error
def git_diff():
    staged = request.args.get('staged', 'false').lower() == 'true'
    filepath = request.args.get('path', '')
    cmd = 'diff --cached' if staged else 'diff'
    if filepath:
        cmd += f' -- {shlex_quote(filepath)}'
    r = git_cmd(cmd)
    return jsonify({'ok': r['ok'], 'diff': r['stdout'], 'stderr': r['stderr']})

@app.route('/api/git/stash', methods=['POST'])
@handle_error
def git_stash():
    data = request.json
    action = data.get('action', 'push')
    r = git_cmd(f'stash {action}')
    return jsonify({'ok': r['ok'], 'stdout': r['stdout'], 'stderr': r['stderr']})

@app.route('/api/git/reset', methods=['POST'])
@handle_error
def git_reset():
    data = request.json
    mode = data.get('mode', 'soft')
    r = git_cmd(f'reset {mode} HEAD')
    return jsonify({'ok': r['ok'], 'stderr': r['stderr']})

# ---- Search APIs ----
@app.route('/api/search', methods=['POST'])
@handle_error
def search_files():
    data = request.json
    query = data.get('query', '')
    pattern = data.get('pattern', '')
    file_pattern = data.get('file_pattern', '*')
    case_sensitive = data.get('case_sensitive', False)
    use_regex = data.get('use_regex', False)
    max_results = data.get('max_results', 500)
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    # Validate base
    real_base = os.path.realpath(base)
    if not os.path.isdir(real_base):
        return jsonify({'results': [], 'total': 0})

    results = []
    search_text = pattern if pattern else query

    try:
        flags = 0 if case_sensitive else re.IGNORECASE
        if use_regex:
            regex = re.compile(search_text, flags)
        else:
            regex = re.compile(re.escape(search_text), flags)

        for root, dirs, files in os.walk(real_base):
            # Skip common ignore dirs
            dirs[:] = [d for d in dirs if d not in {'.git', '__pycache__', 'node_modules', '.venv', 'venv', '.idea', '.vscode'}]
            if len(results) >= max_results:
                break

            for fname in files:
                if len(results) >= max_results:
                    break
                # Filter by file pattern
                if file_pattern != '*' and not fnmatch.fnmatch(fname, file_pattern):
                    continue
                fpath = os.path.join(root, fname)
                try:
                    with open(fpath, 'r', encoding='utf-8', errors='ignore') as f:
                        for i, line in enumerate(f, 1):
                            if regex.search(line):
                                rel = os.path.relpath(fpath, real_base)
                                results.append({
                                    'file': rel,
                                    'line': i,
                                    'col': line.lower().find(search_text.lower()) if not case_sensitive else line.find(search_text),
                                    'text': line.rstrip()[:500],
                                    'match': regex.search(line).group() if regex.search(line) else '',
                                })
                                if len(results) >= max_results:
                                    break
                except (PermissionError, OSError):
                    continue
    except re.error as e:
        return jsonify({'error': f'Invalid regex: {str(e)}'}), 400

    return jsonify({'results': results, 'total': len(results)})

import fnmatch

@app.route('/api/search/replace', methods=['POST'])
@handle_error
def replace_in_files():
    data = request.json
    search = data.get('search', '')
    replace = data.get('replace', '')
    file_path = data.get('file_path', '')
    case_sensitive = data.get('case_sensitive', False)
    use_regex = data.get('use_regex', False)
    config = load_config()
    base = config.get('workspace', WORKSPACE)

    if not search:
        return jsonify({'error': 'Search text required'}), 400

    real_base = os.path.realpath(base)
    target = os.path.realpath(os.path.join(base, file_path))

    if not target.startswith(real_base):
        return jsonify({'error': 'Access denied'}), 403

    if not os.path.isfile(target):
        return jsonify({'error': 'File not found'}), 404

    try:
        with open(target, 'r', encoding='utf-8') as f:
            content = f.read()

        flags = 0 if case_sensitive else re.IGNORECASE
        if use_regex:
            new_content = re.sub(search, replace, content, flags=flags)
        else:
            new_content = re.sub(re.escape(search), replace.replace('\\', '\\\\'), content, flags=flags)

        if new_content == content:
            return jsonify({'ok': True, 'replacements': 0})

        with open(target, 'w', encoding='utf-8') as f:
            f.write(new_content)

        return jsonify({'ok': True, 'replacements': len(re.findall(search if use_regex else re.escape(search), content, flags=flags))})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

# ---- LLM Chat APIs ----
import urllib.request
import urllib.error
import collections

# ==================== Agent Engine ====================
SERVER_START_TIME = time.time()
SERVER_VERSION = '2.0.0'
_log_buffer = collections.deque(maxlen=10000)
_log_lock = threading.Lock()

def _log_write(line):
    """Write a line to the in-memory ring buffer."""
    with _log_lock:
        _log_buffer.append({'time': datetime.now().isoformat(), 'text': line})

# ==================== System Prompt ====================
DEFAULT_SYSTEM_PROMPT = f"""You are PhoneIDE AI Agent, a powerful coding assistant integrated in a mobile IDE.
You have access to tools that let you read/write files, execute code, search projects, manage git, and more.

## Available Tools
You have 15 tools available. When you need to perform an action, call the appropriate tool using function calling.
For multi-step tasks, think step by step and use tools in sequence.

## Important Rules
1. Always use absolute paths when referencing files
2. Before writing a file, read it first to understand existing content
3. When modifying code, use edit_file for targeted changes instead of rewriting entire files
4. After executing commands, check the output for errors before proceeding
5. For large files, use offset_line and limit_lines to read specific sections
6. When searching, use specific patterns rather than broad terms
7. If a tool fails, analyze the error and try a different approach
8. Always explain what you're doing and why before taking action
9. Respect the workspace boundary - all file operations are scoped to the workspace
10. When running shell commands, be cautious with destructive operations

## Workspace
Current workspace: {WORKSPACE}
Server directory: {SERVER_DIR}
"""

# ==================== Tool Definitions ====================
AGENT_TOOLS = [
    {
        'type': 'function',
        'function': {
            'name': 'read_file',
            'description': (
                'Read the content of a file. Supports automatic encoding detection (UTF-8, GBK, Latin-1, etc.). '
                'Returns file content with line numbers for easy reference. For large files, use offset_line and '
                'limit_lines to read specific sections. Files larger than 10MB will be rejected. '
                'Binary files will return an error.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'path': {
                        'type': 'string',
                        'description': 'Absolute path to the file to read',
                    },
                    'offset_line': {
                        'type': 'integer',
                        'description': 'Start reading from this line number (1-based). Default: 1',
                        'default': 1,
                    },
                    'limit_lines': {
                        'type': 'integer',
                        'description': 'Maximum number of lines to read. Default: read entire file',
                    },
                },
                'required': ['path'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'write_file',
            'description': (
                'Write content to a file, creating it if it does not exist. Parent directories are automatically '
                'created. Overwrites existing files entirely. For targeted modifications, prefer edit_file instead. '
                'Content is written as UTF-8 text.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'path': {
                        'type': 'string',
                        'description': 'Absolute path to the file to write',
                    },
                    'content': {
                        'type': 'string',
                        'description': 'Full content to write to the file',
                    },
                    'create_dirs': {
                        'type': 'boolean',
                        'description': 'Automatically create parent directories. Default: true',
                        'default': True,
                    },
                },
                'required': ['path', 'content'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'edit_file',
            'description': (
                'Search and replace text within a file. Performs exact string matching of old_text and replaces '
                'it with new_text. If old_text appears multiple times, all occurrences will be replaced - be '
                'specific with surrounding context to avoid unintended changes. Returns the number of replacements made.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'path': {
                        'type': 'string',
                        'description': 'Absolute path to the file to edit',
                    },
                    'old_text': {
                        'type': 'string',
                        'description': 'Exact text to search for (must match precisely including whitespace)',
                    },
                    'new_text': {
                        'type': 'string',
                        'description': 'Replacement text',
                    },
                },
                'required': ['path', 'old_text', 'new_text'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'list_directory',
            'description': (
                'List files and directories at a given path. Returns file names, types (file/directory), sizes, '
                'and modification times. Automatically detects file types by extension. Hidden files are excluded '
                'by default unless show_hidden is true.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'path': {
                        'type': 'string',
                        'description': 'Absolute path to the directory to list. Default: workspace root',
                        'default': WORKSPACE,
                    },
                    'show_hidden': {
                        'type': 'boolean',
                        'description': 'Include hidden files (starting with dot). Default: false',
                        'default': False,
                    },
                },
                'required': [],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'search_files',
            'description': (
                'Search for text patterns across files in the workspace. Supports both literal text and regex '
                'patterns. Skips common ignore directories (.git, node_modules, __pycache__, etc.). Returns '
                'file paths, line numbers, and matching line content. Use specific patterns for better performance.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'pattern': {
                        'type': 'string',
                        'description': 'Text or regex pattern to search for',
                    },
                    'path': {
                        'type': 'string',
                        'description': 'Root directory to search in. Default: workspace root',
                        'default': WORKSPACE,
                    },
                    'include': {
                        'type': 'string',
                        'description': 'File glob pattern to filter files (e.g. "*.py", "*.{js,ts}"). Default: all files',
                    },
                    'max_results': {
                        'type': 'integer',
                        'description': 'Maximum number of results to return. Default: 50',
                        'default': 50,
                    },
                },
                'required': ['pattern'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'run_command',
            'description': (
                'Execute a shell command and return its output (stdout + stderr combined). Has a configurable '
                'timeout to prevent hanging. WARNING: This can execute arbitrary commands - be careful with '
                'destructive operations like rm -rf, format, etc. Commands run in the workspace directory by default. '
                'Output is captured and returned, limited to 30000 characters.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'command': {
                        'type': 'string',
                        'description': 'Shell command to execute',
                    },
                    'timeout': {
                        'type': 'integer',
                        'description': 'Timeout in seconds. Default: 120',
                        'default': 120,
                    },
                    'cwd': {
                        'type': 'string',
                        'description': 'Working directory for the command. Default: workspace root',
                    },
                },
                'required': ['command'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'git_status',
            'description': (
                'Show the current git repository status including branch name, staged changes, modified files, '
                'and untracked files. Useful for understanding what has changed before committing.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'repo_path': {
                        'type': 'string',
                        'description': 'Path to the git repository. Default: workspace root',
                        'default': WORKSPACE,
                    },
                },
                'required': [],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'git_diff',
            'description': (
                'Show git diff output. Can show staged changes, unstaged changes, or changes to a specific file. '
                'Returns the unified diff format.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'repo_path': {
                        'type': 'string',
                        'description': 'Path to the git repository. Default: workspace root',
                    },
                    'staged': {
                        'type': 'boolean',
                        'description': 'Show staged changes (git diff --cached). Default: false',
                        'default': False,
                    },
                    'file_path': {
                        'type': 'string',
                        'description': 'Specific file to show diff for. If omitted, shows all changes.',
                    },
                },
                'required': [],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'git_commit',
            'description': (
                'Stage all changes and create a git commit. By default stages all changes (git add -A) before '
                'committing. Use add_all=false to only commit previously staged changes.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'message': {
                        'type': 'string',
                        'description': 'Commit message',
                    },
                    'repo_path': {
                        'type': 'string',
                        'description': 'Path to the git repository. Default: workspace root',
                    },
                    'add_all': {
                        'type': 'boolean',
                        'description': 'Stage all changes before committing (git add -A). Default: true',
                        'default': True,
                    },
                },
                'required': ['message'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'install_package',
            'description': (
                'Install a package using pip or npm. Automatically detects the package manager based on package '
                'name format. If a virtual environment is configured, uses the venv pip. Supports version '
                'specifiers (e.g. "flask==2.3.0", "numpy>=1.24").'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'package_name': {
                        'type': 'string',
                        'description': 'Package name to install (e.g. "flask", "numpy>=1.24", "express")',
                    },
                    'manager': {
                        'type': 'string',
                        'description': 'Package manager: "pip", "npm", or "auto-detect". Default: auto-detect',
                        'default': 'auto',
                    },
                },
                'required': ['package_name'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'list_packages',
            'description': (
                'List installed packages. Returns package names and versions. For pip, uses the virtual environment '
                'pip if one is configured.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'manager': {
                        'type': 'string',
                        'description': 'Package manager: "pip" or "npm". Default: "pip"',
                        'default': 'pip',
                    },
                },
                'required': [],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'grep_code',
            'description': (
                'Advanced code search with context lines. Searches for a regex pattern across files and returns '
                'matching lines with surrounding context. Useful for understanding function usage, variable '
                'definitions, and code structure. Supports include/exclude file filters.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'pattern': {
                        'type': 'string',
                        'description': 'Regex pattern to search for',
                    },
                    'path': {
                        'type': 'string',
                        'description': 'Root directory to search in. Default: workspace root',
                    },
                    'context_lines': {
                        'type': 'integer',
                        'description': 'Number of context lines before and after each match. Default: 2',
                        'default': 2,
                    },
                    'include': {
                        'type': 'string',
                        'description': 'File glob pattern to include (e.g. "*.py"). Default: all files',
                    },
                    'exclude': {
                        'type': 'string',
                        'description': 'File glob pattern to exclude (e.g. "*.min.js"). Default: none',
                    },
                },
                'required': ['pattern'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'file_info',
            'description': (
                'Get detailed metadata about a file or directory. Returns file size, last modified time, '
                'file type (regular file, directory, symlink), and permissions (in octal and rwx format).'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'path': {
                        'type': 'string',
                        'description': 'Absolute path to the file or directory',
                    },
                },
                'required': ['path'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'create_directory',
            'description': (
                'Create a new directory, including any necessary parent directories (equivalent to mkdir -p). '
                'If the directory already exists, the operation succeeds silently.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'path': {
                        'type': 'string',
                        'description': 'Absolute path of the directory to create',
                    },
                },
                'required': ['path'],
            },
        },
    },
    {
        'type': 'function',
        'function': {
            'name': 'delete_path',
            'description': (
                'Delete a file or directory. WARNING: This is a destructive and irreversible operation. '
                'For directories, use recursive=true to delete all contents. The workspace root itself '
                'cannot be deleted for safety.'
            ),
            'parameters': {
                'type': 'object',
                'properties': {
                    'path': {
                        'type': 'string',
                        'description': 'Absolute path to delete',
                    },
                    'recursive': {
                        'type': 'boolean',
                        'description': 'For directories, delete all contents recursively. Default: false',
                        'default': False,
                    },
                },
                'required': ['path'],
            },
        },
    },
]

# ==================== Security Helpers ====================
def _validate_path(path):
    """Ensure path stays within WORKSPACE. Returns resolved absolute path or raises ValueError."""
    real_workspace = os.path.realpath(WORKSPACE)
    real_path = os.path.realpath(path)
    if not real_path.startswith(real_workspace + os.sep) and real_path != real_workspace:
        raise ValueError(f'Access denied: path "{path}" is outside workspace')
    return real_path

def _truncate(text, limit=30000):
    """Truncate text to limit characters, appending [truncated] marker if needed."""
    if len(text) > limit:
        return text[:limit] + '\n\n[truncated: output too long, showed first ' + str(limit) + ' of ' + str(len(text)) + ' characters]'
    return text

# ==================== Tool Execution ====================
def _tool_read_file(args):
    path = _validate_path(args['path'])
    if not os.path.isfile(path):
        return f'Error: File not found: {path}'
    size = os.path.getsize(path)
    if size > 10 * 1024 * 1024:
        return f'Error: File too large ({size} bytes, max 10MB)'
    offset = args.get('offset_line', 1) - 1  # convert to 0-based
    limit = args.get('limit_lines')
    try:
        with open(path, 'rb') as f:
            raw = f.read()
        encodings = ['utf-8', 'utf-8-sig', 'gbk', 'gb2312', 'latin-1']
        content = None
        used_enc = 'utf-8'
        for enc in encodings:
            try:
                content = raw.decode(enc)
                used_enc = enc
                break
            except (UnicodeDecodeError, LookupError):
                continue
        if content is None:
            content = raw.decode('utf-8', errors='replace')
        lines = content.split('\n')
        end = (offset + limit) if limit else None
        selected = lines[offset:end]
        header = f'File: {path} (encoding: {used_enc}, size: {size} bytes, total lines: {len(lines)})'
        numbered = []
        for i, line in enumerate(selected, start=offset + 1):
            numbered.append(f'  {i:>6}\t{line}')
        result = header + '\n' + '\n'.join(numbered)
        if end and end < len(lines):
            result += f'\n\n[showing lines {offset+1}-{end} of {len(lines)}]'
        return _truncate(result)
    except Exception as e:
        return f'Error reading file: {str(e)}'

def _tool_write_file(args):
    path = _validate_path(args['path'])
    content = args['content']
    create_dirs = args.get('create_dirs', True)
    if create_dirs:
        parent = os.path.dirname(path)
        if parent:
            os.makedirs(parent, exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    return f'File written successfully: {path} ({os.path.getsize(path)} bytes)'

def _tool_edit_file(args):
    path = _validate_path(args['path'])
    old_text = args['old_text']
    new_text = args['new_text']
    if not os.path.isfile(path):
        return f'Error: File not found: {path}'
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    count = content.count(old_text)
    if count == 0:
        return f'Error: old_text not found in file. Make sure the text matches exactly (including whitespace).'
    if count > 1:
        return f'Warning: old_text found {count} times. All occurrences will be replaced. Use more context to be specific.\nReplacements made: {count}'
    new_content = content.replace(old_text, new_text)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(new_content)
    return f'Edited file: {path} ({count} replacement(s) made)'

def _tool_list_directory(args):
    path = _validate_path(args.get('path', WORKSPACE))
    show_hidden = args.get('show_hidden', False)
    if not os.path.isdir(path):
        return f'Error: Directory not found: {path}'
    items = []
    for entry in sorted(os.listdir(path)):
        if not show_hidden and entry.startswith('.'):
            continue
        full = os.path.join(path, entry)
        try:
            st = os.stat(full)
            is_dir = os.path.isdir(full)
            ftype = 'dir' if is_dir else get_file_type(entry)
            perm = oct(st.st_mode)[-3:]
            mod_time = datetime.fromtimestamp(st.st_mtime).strftime('%Y-%m-%d %H:%M:%S')
            sz = st.st_size
            items.append(f'  {"[DIR]" if is_dir else "[FILE]"} {perm} {mod_time} {sz:>10}  {entry}  ({ftype})')
        except (PermissionError, OSError):
            items.append(f'  [??]  {entry}  (permission denied)')
    header = f'Directory: {path} ({len(items)} entries)'
    return header + '\n' + '\n'.join(items) if items else header + '\n  (empty directory)'

def _tool_search_files(args):
    pattern = args['pattern']
    search_path = _validate_path(args.get('path', WORKSPACE))
    include = args.get('include', None)
    max_results = args.get('max_results', 50)
    try:
        regex = re.compile(pattern, re.IGNORECASE)
    except re.error as e:
        return f'Error: Invalid regex pattern: {e}'
    results = []
    skip_dirs = {'.git', '__pycache__', 'node_modules', '.venv', 'venv', '.idea', '.vscode', '.svn'}
    for root, dirs, files in os.walk(search_path):
        dirs[:] = [d for d in dirs if d not in skip_dirs and not d.startswith('.')]
        for fname in files:
            if len(results) >= max_results:
                break
            if include and not fnmatch.fnmatch(fname, include):
                continue
            fpath = os.path.join(root, fname)
            try:
                with open(fpath, 'r', encoding='utf-8', errors='ignore') as f:
                    for i, line in enumerate(f, 1):
                        if regex.search(line):
                            rel = os.path.relpath(fpath, search_path)
                            results.append(f'{rel}:{i}: {line.rstrip()[:300]}')
                            if len(results) >= max_results:
                                break
            except (PermissionError, OSError):
                continue
        if len(results) >= max_results:
            break
    if not results:
        return f'No matches found for pattern "{pattern}"'
    header = f'Search results for "{pattern}" ({len(results)} matches):'
    return header + '\n' + '\n'.join(results)

def _tool_run_command(args):
    command = args['command']
    timeout = args.get('timeout', 120)
    cwd = args.get('cwd', WORKSPACE)
    try:
        cwd = _validate_path(cwd)
    except ValueError:
        cwd = WORKSPACE
    config = load_config()
    env = os.environ.copy()
    venv_path = config.get('venv_path', '')
    if venv_path and os.path.exists(venv_path):
        venv_bin = os.path.join(venv_path, 'bin')
        if os.path.exists(venv_bin):
            env['PATH'] = venv_bin + ':' + env.get('PATH', '')
            env['VIRTUAL_ENV'] = venv_path
    try:
        result = subprocess.run(
            command, shell=True, cwd=cwd, capture_output=True, text=True,
            timeout=timeout, env=env,
        )
        output = ''
        if result.stdout:
            output += result.stdout
        if result.stderr:
            output += ('\n' if output else '') + result.stderr
        exit_info = f'\n[Exit code: {result.returncode}]'
        return _truncate((output or '(no output)') + exit_info)
    except subprocess.TimeoutExpired:
        return f'Error: Command timed out after {timeout} seconds'
    except Exception as e:
        return f'Error executing command: {str(e)}'

def _tool_git_status(args):
    repo_path = args.get('repo_path', WORKSPACE)
    r = git_cmd('status --porcelain -b', cwd=repo_path)
    if not r['ok']:
        return f'Error: {r["stderr"]}'
    return r['stdout'] or 'Clean working tree (no changes)'

def _tool_git_diff(args):
    repo_path = args.get('repo_path', WORKSPACE)
    staged = args.get('staged', False)
    file_path = args.get('file_path', '')
    cmd = 'diff --cached' if staged else 'diff'
    if file_path:
        cmd += f' -- {shlex_quote(file_path)}'
    r = git_cmd(cmd, cwd=repo_path)
    return r['stdout'] or 'No changes to display'

def _tool_git_commit(args):
    message = args['message']
    repo_path = args.get('repo_path', WORKSPACE)
    add_all = args.get('add_all', True)
    if add_all:
        git_cmd('add -A', cwd=repo_path)
    r = git_cmd(f'commit -m {shlex_quote(message)}', cwd=repo_path)
    if r['ok']:
        return f'Commit successful: "{message}"'
    return f'Error: {r["stderr"]}'

def _tool_install_package(args):
    package_name = args['package_name']
    manager = args.get('manager', 'auto')
    config = load_config()
    if manager == 'auto':
        manager = 'npm' if package_name.startswith('@') or not re.search(r'[a-zA-Z]-[a-zA-Z]', package_name) and os.path.exists(os.path.join(WORKSPACE, 'package.json')) else 'pip'
    if manager == 'npm':
        cmd = f'npm install {shlex_quote(package_name)}'
    else:
        venv = config.get('venv_path', '')
        pip = os.path.join(venv, 'bin', 'pip') if venv and os.path.exists(os.path.join(venv, 'bin', 'pip')) else 'pip3'
        cmd = f'{pip} install {shlex_quote(package_name)}'
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=300, cwd=WORKSPACE)
    output = r.stdout or ''
    if r.stderr:
        output += ('\n' if output else '') + r.stderr
    if r.returncode == 0:
        return _truncate(f'Package installed successfully: {package_name}\n{output}')
    return _truncate(f'Error installing {package_name} (exit code {r.returncode}):\n{output}')

def _tool_list_packages(args):
    manager = args.get('manager', 'pip')
    config = load_config()
    if manager == 'npm':
        r = subprocess.run('npm list --depth=0 2>/dev/null', shell=True, capture_output=True, text=True, timeout=30, cwd=WORKSPACE)
        return r.stdout or 'No packages found'
    venv = config.get('venv_path', '')
    pip = os.path.join(venv, 'bin', 'pip') if venv and os.path.exists(os.path.join(venv, 'bin', 'pip')) else 'pip3'
    r = subprocess.run(f'{pip} list --format=json', shell=True, capture_output=True, text=True, timeout=30)
    if r.returncode == 0:
        try:
            pkgs = json.loads(r.stdout)
            lines = [f'  {p["name"]}=={p["version"]}' for p in pkgs]
            return f'Installed packages ({len(lines)}):\n' + '\n'.join(lines)
        except Exception:
            pass
    return r.stdout or r.stderr or 'No packages found'

def _tool_grep_code(args):
    pattern = args['pattern']
    search_path = _validate_path(args.get('path', WORKSPACE))
    context = args.get('context_lines', 2)
    include = args.get('include', None)
    exclude = args.get('exclude', None)
    try:
        regex = re.compile(pattern, re.IGNORECASE)
    except re.error as e:
        return f'Error: Invalid regex: {e}'
    results = []
    skip_dirs = {'.git', '__pycache__', 'node_modules', '.venv', 'venv', '.idea', '.vscode'}
    for root, dirs, files in os.walk(search_path):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        for fname in files:
            if include and not fnmatch.fnmatch(fname, include):
                continue
            if exclude and fnmatch.fnmatch(fname, exclude):
                continue
            fpath = os.path.join(root, fname)
            try:
                with open(fpath, 'r', encoding='utf-8', errors='ignore') as f:
                    all_lines = f.readlines()
                matches = []
                for i, line in enumerate(all_lines):
                    if regex.search(line):
                        matches.append(i)
                if not matches:
                    continue
                rel = os.path.relpath(fpath, search_path)
                for idx in matches:
                    start = max(0, idx - context)
                    end = min(len(all_lines), idx + context + 1)
                    results.append(f'\n{rel}:{idx+1}:\n' + ''.join(
                        f'  {"*" if j == idx else " "} {j+1:>5}\t{all_lines[j].rstrip()}\n'
                        for j in range(start, end)
                    ))
                if len(results) >= 30:
                    break
            except (PermissionError, OSError):
                continue
        if len(results) >= 30:
            break
    if not results:
        return f'No matches for pattern "{pattern}"'
    return f'Found {len(results)} match(es) for "{pattern}":\n' + '\n'.join(results)

def _tool_file_info(args):
    path = _validate_path(args['path'])
    if not os.path.exists(path):
        return f'Error: Path not found: {path}'
    st = os.stat(path)
    is_dir = os.path.isdir(path)
    is_link = os.path.islink(path)
    ftype = 'symlink' if is_link else ('directory' if is_dir else 'regular file')
    size = st.st_size
    if is_dir:
        try:
            size = sum(
                os.path.getsize(os.path.join(dp, f))
                for dp, dn, fn in os.walk(path)
                for f in fn
            )
        except (PermissionError, OSError):
            size = 0
    mod_time = datetime.fromtimestamp(st.st_mtime).strftime('%Y-%m-%d %H:%M:%S')
    perm_oct = oct(st.st_mode)[-3:]
    perm_rwx = ''
    for p in perm_oct:
        perm_rwx += {'0': '---', '1': '--x', '2': '-w-', '3': '-wx', '4': 'r--', '5': 'r-x', '6': 'rw-', '7': 'rwx'}[p] + ' '
    return (
        f'Path:     {path}\n'
        f'Type:     {ftype}\n'
        f'Size:     {size:,} bytes\n'
        f'Modified: {mod_time}\n'
        f'Permissions: {perm_oct} ({perm_rwx.strip()})'
    )

def _tool_create_directory(args):
    path = _validate_path(args['path'])
    os.makedirs(path, exist_ok=True)
    return f'Directory created: {path}'

def _tool_delete_path(args):
    path = _validate_path(args['path'])
    real_ws = os.path.realpath(WORKSPACE)
    if os.path.realpath(path) == real_ws:
        return 'Error: Cannot delete the workspace root'
    if not os.path.exists(path):
        return f'Error: Path not found: {path}'
    recursive = args.get('recursive', False)
    try:
        if os.path.isdir(path):
            if recursive:
                shutil.rmtree(path)
                return f'Directory deleted recursively: {path}'
            else:
                try:
                    os.rmdir(path)
                    return f'Directory deleted (must be empty): {path}'
                except OSError as e:
                    return f'Error: Directory not empty. Use recursive=true to delete: {e}'
        else:
            os.remove(path)
            return f'File deleted: {path}'
    except Exception as e:
        return f'Error deleting path: {str(e)}'

_TOOL_HANDLERS = {
    'read_file': _tool_read_file,
    'write_file': _tool_write_file,
    'edit_file': _tool_edit_file,
    'list_directory': _tool_list_directory,
    'search_files': _tool_search_files,
    'run_command': _tool_run_command,
    'git_status': _tool_git_status,
    'git_diff': _tool_git_diff,
    'git_commit': _tool_git_commit,
    'install_package': _tool_install_package,
    'list_packages': _tool_list_packages,
    'grep_code': _tool_grep_code,
    'file_info': _tool_file_info,
    'create_directory': _tool_create_directory,
    'delete_path': _tool_delete_path,
}

def execute_agent_tool(name, arguments):
    """Execute a tool by name with given arguments. Returns (ok, result_string, elapsed_seconds)."""
    handler = _TOOL_HANDLERS.get(name)
    if not handler:
        return False, f'Error: Unknown tool "{name}". Available tools: {", ".join(_TOOL_HANDLERS.keys())}', 0
    t0 = time.time()
    try:
        result = handler(arguments)
        elapsed = time.time() - t0
        return True, result, elapsed
    except ValueError as e:
        return False, f'Security error: {e}', time.time() - t0
    except Exception as e:
        return False, f'Tool execution error: {str(e)}', time.time() - t0

# ==================== LLM Integration ====================
def _build_api_messages(messages, llm_config):
    """Convert chat history to API format with system prompt."""
    sys_prompt = llm_config.get('system_prompt', '')
    if not sys_prompt:
        sys_prompt = DEFAULT_SYSTEM_PROMPT
    elif WORKSPACE not in sys_prompt:
        sys_prompt += f'\n\nCurrent workspace: {WORKSPACE}\nServer directory: {SERVER_DIR}\n'
    api_messages = [{'role': 'system', 'content': sys_prompt}]
    for msg in messages:
        role = msg.get('role', '')
        if role == 'system':
            continue
        elif role == 'tool':
            api_messages.append({
                'role': 'tool',
                'tool_call_id': msg.get('tool_call_id', 'call_default'),
                'content': msg.get('content', ''),
            })
        elif role == 'assistant' and msg.get('tool_calls'):
            api_messages.append({
                'role': 'assistant',
                'content': msg.get('content', None),
                'tool_calls': msg['tool_calls'],
            })
        elif role in ('user', 'assistant'):
            api_messages.append({'role': role, 'content': msg.get('content', '')})
    return api_messages

def _call_llm_api(messages, llm_config, stream=False):
    """Make a non-streaming LLM API call. Returns parsed response dict."""
    api_key = llm_config.get('api_key', '')
    api_base = llm_config.get('api_base', 'https://api.openai.com/v1').rstrip('/') + '/'
    model = llm_config.get('model', 'gpt-4o-mini')
    temperature = llm_config.get('temperature', 0.7)
    max_tokens = llm_config.get('max_tokens', 4096)

    api_messages = _build_api_messages(messages, llm_config)

    payload = {
        'model': model,
        'messages': api_messages,
        'temperature': temperature,
        'max_tokens': max_tokens,
        'tools': AGENT_TOOLS,
        'tool_choice': 'auto',
    }
    if stream:
        payload['stream'] = True

    url = api_base + 'chat/completions'
    headers = {
        'Content-Type': 'application/json',
        'Authorization': f'Bearer {api_key}',
    }

    req = urllib.request.Request(url, json.dumps(payload).encode(), headers=headers, method='POST')

    with urllib.request.urlopen(req, timeout=180) as resp:
        result = json.loads(resp.read().decode())
    return result

def _call_llm_stream_raw(messages, llm_config):
    """Stream LLM response as raw SSE data chunks. Yields parsed delta objects."""
    import urllib.request

    api_key = llm_config.get('api_key', '')
    api_base = llm_config.get('api_base', 'https://api.openai.com/v1').rstrip('/') + '/'
    model = llm_config.get('model', 'gpt-4o-mini')
    temperature = llm_config.get('temperature', 0.7)
    max_tokens = llm_config.get('max_tokens', 4096)

    api_messages = _build_api_messages(messages, llm_config)

    payload = {
        'model': model,
        'messages': api_messages,
        'temperature': temperature,
        'max_tokens': max_tokens,
        'tools': AGENT_TOOLS,
        'tool_choice': 'auto',
        'stream': True,
    }

    url = api_base + 'chat/completions'
    headers = {
        'Content-Type': 'application/json',
        'Authorization': f'Bearer {api_key}',
    }

    req = urllib.request.Request(url, json.dumps(payload).encode(), headers=headers, method='POST')

    with urllib.request.urlopen(req, timeout=180) as resp:
        buffer = ''
        while True:
            chunk = resp.read(4096)
            if not chunk:
                break
            buffer += chunk.decode('utf-8', errors='ignore')
            while '\n' in buffer:
                line, buffer = buffer.split('\n', 1)
                line = line.strip()
                if not line.startswith('data: '):
                    continue
                data_str = line[6:]
                if data_str == '[DONE]':
                    return
                try:
                    data = json.loads(data_str)
                    choices = data.get('choices', [])
                    if choices:
                        yield choices[0].get('delta', {})
                except json.JSONDecodeError:
                    continue

# ==================== Context Window Management ====================
def _estimate_tokens(text):
    """Rough token estimation: ~4 characters per token."""
    return len(text) // 4

def _compress_context(messages, max_tokens=None):
    """Compress conversation history to fit within context window.
    Keeps: system prompt, last 2 user messages, last 4 tool results, summary of older messages.
    """
    if not messages:
        return messages
    max_tokens = max_tokens or 60000
    total = sum(_estimate_tokens(m.get('content', '') or '') for m in messages)
    if total <= max_tokens:
        return messages

    # Split messages into older and recent
    # Find the last 2 user messages from the end
    user_indices = [i for i, m in enumerate(messages) if m.get('role') == 'user']
    if len(user_indices) >= 2:
        split_idx = user_indices[-2]
    else:
        split_idx = max(0, len(messages) - 6)

    older = messages[:split_idx]
    recent = messages[split_idx:]

    # Build a summary of older messages
    summary_parts = []
    for msg in older:
        role = msg.get('role', '')
        content = msg.get('content', '')
        if role == 'user':
            summary_parts.append(f'[User]: {content[:200]}')
        elif role == 'assistant':
            summary_parts.append(f'[Assistant]: {content[:200]}')
        elif role == 'tool':
            name = msg.get('name', 'tool')
            summary_parts.append(f'[Tool {name}]: {_truncate(content, 100)}')

    summary = 'Earlier conversation summary:\n' + '\n'.join(summary_parts[-10:])
    summary_msg = {'role': 'system', 'content': summary}

    # Compress recent tool results if still too large
    compressed_recent = []
    for msg in recent:
        if msg.get('role') == 'tool':
            content = msg.get('content', '')
            if len(content) > 3000:
                msg = dict(msg, content=content[:3000] + '\n[truncated for context]')
        compressed_recent.append(msg)

    # Re-check total size
    all_msgs = [summary_msg] + compressed_recent
    total2 = sum(_estimate_tokens(m.get('content', '') or '') for m in all_msgs)
    if total2 > max_tokens:
        # Further trim tool results
        for msg in all_msgs:
            if msg.get('role') == 'tool':
                content = msg.get('content', '')
                if len(content) > 1000:
                    msg['content'] = content[:1000] + '\n[truncated]'

    return all_msgs

# ==================== Agent Loop ====================
MAX_AGENT_ITERATIONS = 15
MAX_ITERATION_RETRIES = 3

def run_agent_loop(user_message, llm_config, history=None, stream_callback=None):
    """Run the full agent loop: LLM -> tools -> LLM -> ... until final answer.

    Args:
        user_message: The user's message string.
        llm_config: LLM configuration dict.
        history: Existing chat history list (will be appended to).
        stream_callback: Optional callable(event_dict) for real-time streaming.

    Returns:
        dict with 'content' (final text), 'iterations', 'tool_calls_made', 'history'
    """
    if history is None:
        history = load_chat_history()

    user_msg = {'role': 'user', 'content': user_message, 'time': datetime.now().isoformat()}
    history.append(user_msg)

    # Compress context if needed
    context = _compress_context(history, max_tokens=llm_config.get('max_tokens', 4096) * 10)

    def _emit(event):
        if stream_callback:
            stream_callback(event)

    final_content = ''
    total_iterations = 0
    all_tool_calls = []

    for iteration in range(MAX_AGENT_ITERATIONS):
        total_iterations = iteration + 1
        _emit({'type': 'thinking', 'content': f'Iteration {iteration + 1}: Calling LLM...'})

        # Call LLM with retries
        response = None
        for retry in range(MAX_ITERATION_RETRIES):
            try:
                response = _call_llm_api(context, llm_config)
                break
            except urllib.error.HTTPError as e:
                body = e.read().decode() if hasattr(e, 'read') else ''
                if retry < MAX_ITERATION_RETRIES - 1:
                    _emit({'type': 'thinking', 'content': f'LLM API error (retry {retry + 1}): {e.code} {body[:200]}'})
                    time.sleep(1 * (retry + 1))
                else:
                    raise Exception(f'LLM API error after {MAX_ITERATION_RETRIES} retries ({e.code}): {body[:500]}')
            except Exception as e:
                if retry < MAX_ITERATION_RETRIES - 1:
                    _emit({'type': 'thinking', 'content': f'Retry {retry + 1}: {str(e)[:200]}'})
                    time.sleep(1 * (retry + 1))
                else:
                    raise Exception(f'LLM request failed after {MAX_ITERATION_RETRIES} retries: {str(e)}')

        # Parse response
        choice = response.get('choices', [{}])[0]
        message = choice.get('message', {})
        content = message.get('content', '') or ''
        tool_calls_raw = message.get('tool_calls', [])

        # Stream text content
        if content:
            _emit({'type': 'text', 'content': content})
            final_content = content

        # If no tool calls, we're done
        if not tool_calls_raw:
            break

        # Add assistant message with tool_calls to context
        assistant_msg = {
            'role': 'assistant',
            'content': content or None,
            'tool_calls': tool_calls_raw,
            'time': datetime.now().isoformat(),
        }
        context.append(assistant_msg)

        # Execute each tool call
        for tc in tool_calls_raw:
            func = tc.get('function', {})
            tool_name = func.get('name', '')
            try:
                tool_args = json.loads(func.get('arguments', '{}'))
            except json.JSONDecodeError:
                tool_args = {}

            tool_call_id = tc.get('id', f'call_{tool_name}')
            all_tool_calls.append({'name': tool_name, 'args': tool_args})

            _emit({'type': 'tool_start', 'tool': tool_name, 'args': tool_args})

            ok, result_str, elapsed = execute_agent_tool(tool_name, tool_args)

            _emit({
                'type': 'tool_result',
                'tool': tool_name,
                'ok': ok,
                'result': _truncate(result_str, 30000),
                'elapsed': round(elapsed, 2),
            })

            # Add tool result to context
            context.append({
                'role': 'tool',
                'tool_call_id': tool_call_id,
                'name': tool_name,
                'content': result_str,
                'time': datetime.now().isoformat(),
            })

            # Re-check context size and compress if needed
            context = _compress_context(context, max_tokens=llm_config.get('max_tokens', 4096) * 10)

    # Build final assistant message for history
    final_assistant = {
        'role': 'assistant',
        'content': final_content,
        'tool_calls_made': all_tool_calls,
        'iterations': total_iterations,
        'time': datetime.now().isoformat(),
    }
    history.append(final_assistant)

    return {
        'content': final_content,
        'iterations': total_iterations,
        'tool_calls_made': all_tool_calls,
        'history': history,
    }

def run_agent_loop_stream(user_message, llm_config):
    """Generator that runs the agent loop and yields SSE events."""
    history = load_chat_history()
    user_msg = {'role': 'user', 'content': user_message, 'time': datetime.now().isoformat()}
    history.append(user_msg)

    context = _compress_context(history, max_tokens=llm_config.get('max_tokens', 4096) * 10)

    final_content = ''
    total_iterations = 0
    accumulated_text = ''
    tool_calls_in_progress = []
    # Buffer for streaming tool_calls assembly
    current_tool_calls = []
    current_tool_call_idx = {}
    current_args_buffer = {}

    for iteration in range(MAX_AGENT_ITERATIONS):
        total_iterations = iteration + 1
        yield f"data: {json.dumps({'type': 'thinking', 'content': f'Iteration {iteration + 1}: Calling LLM...'})}\n\n"

        # Call LLM with streaming
        response_message = None
        for retry in range(MAX_ITERATION_RETRIES):
            try:
                current_tool_calls = []
                current_args_buffer = {}
                current_tool_call_idx = {}
                delta_content = ''
                delta_tool_calls = []

                for delta in _call_llm_stream_raw(context, llm_config):
                    # Handle text content
                    content_chunk = delta.get('content')
                    if content_chunk:
                        delta_content += content_chunk
                        accumulated_text += content_chunk
                        yield f"data: {json.dumps({'type': 'text', 'content': content_chunk})}\n\n"

                    # Handle tool_calls (assembled from streaming deltas)
                    tc_delta = delta.get('tool_calls')
                    if tc_delta:
                        for tc_part in tc_delta:
                            idx = tc_part.get('index', 0)
                            if idx not in current_tool_call_idx:
                                current_tool_call_idx[idx] = len(current_tool_calls)
                                tc_entry = {
                                    'id': tc_part.get('id', f'call_{idx}'),
                                    'type': 'function',
                                    'function': {'name': '', 'arguments': ''},
                                }
                                current_tool_calls.append(tc_entry)
                                current_args_buffer[idx] = ''

                            tc_entry = current_tool_calls[current_tool_call_idx[idx]]
                            if tc_part.get('id'):
                                tc_entry['id'] = tc_part['id']
                            func_delta = tc_part.get('function', {})
                            if func_delta.get('name'):
                                tc_entry['function']['name'] += func_delta['name']
                            if func_delta.get('arguments'):
                                current_args_buffer[idx] += func_delta['arguments']

                # Finalize tool call arguments
                for idx, tc_entry in enumerate(current_tool_calls):
                    if idx in current_args_buffer:
                        tc_entry['function']['arguments'] = current_args_buffer[idx]

                # Build the complete response message
                response_message = {
                    'role': 'assistant',
                    'content': delta_content or None,
                }
                if current_tool_calls:
                    response_message['tool_calls'] = current_tool_calls
                break

            except urllib.error.HTTPError as e:
                body = e.read().decode() if hasattr(e, 'read') else ''
                if retry < MAX_ITERATION_RETRIES - 1:
                    yield f"data: {json.dumps({'type': 'thinking', 'content': f'LLM API error (retry {retry + 1}): {e.code} {body[:200]}'})}\n\n"
                    time.sleep(1 * (retry + 1))
                else:
                    yield f"data: {json.dumps({'type': 'error', 'content': f'LLM API error after {MAX_ITERATION_RETRIES} retries ({e.code}): {body[:500]}'})}\n\n"
                    return
            except Exception as e:
                if retry < MAX_ITERATION_RETRIES - 1:
                    yield f"data: {json.dumps({'type': 'thinking', 'content': f'Retry {retry + 1}: {str(e)[:200]}'})}\n\n"
                    time.sleep(1 * (retry + 1))
                else:
                    yield f"data: {json.dumps({'type': 'error', 'content': f'LLM request failed: {str(e)}'})}\n\n"
                    return

        if response_message is None:
            response_message = {'role': 'assistant', 'content': accumulated_text or '(no response)'}

        content = response_message.get('content', '') or ''
        tool_calls_raw = response_message.get('tool_calls', [])

        if content:
            final_content = accumulated_text.strip() if accumulated_text.strip() else content

        # If no tool calls, we're done
        if not tool_calls_raw:
            break

        # Add assistant message to context
        context.append({
            'role': 'assistant',
            'content': content or None,
            'tool_calls': tool_calls_raw,
            'time': datetime.now().isoformat(),
        })

        # Reset accumulated text for next iteration
        accumulated_text = ''

        # Execute each tool call
        for tc in tool_calls_raw:
            func = tc.get('function', {})
            tool_name = func.get('name', '')
            try:
                tool_args = json.loads(func.get('arguments', '{}'))
            except json.JSONDecodeError:
                tool_args = {}

            tool_call_id = tc.get('id', f'call_{tool_name}')
            tool_calls_in_progress.append({'name': tool_name, 'args': tool_args})

            yield f"data: {json.dumps({'type': 'tool_start', 'tool': tool_name, 'args': tool_args})}\n\n"

            ok, result_str, elapsed = execute_agent_tool(tool_name, tool_args)

            yield f"data: {json.dumps({'type': 'tool_result', 'tool': tool_name, 'ok': ok, 'result': _truncate(result_str, 30000), 'elapsed': round(elapsed, 2)})}\n\n"

            context.append({
                'role': 'tool',
                'tool_call_id': tool_call_id,
                'name': tool_name,
                'content': result_str,
                'time': datetime.now().isoformat(),
            })

            # Compress context if needed
            context = _compress_context(context, max_tokens=llm_config.get('max_tokens', 4096) * 10)

    # Build final assistant message for history
    final_assistant = {
        'role': 'assistant',
        'content': final_content,
        'tool_calls_made': tool_calls_in_progress,
        'iterations': total_iterations,
        'time': datetime.now().isoformat(),
    }
    history.append(final_assistant)
    save_chat_history(history)

    yield f"data: {json.dumps({'type': 'done', 'iterations': total_iterations, 'tool_calls': len(tool_calls_in_progress)})}\n\n"

# ==================== Chat Endpoints ====================
@app.route('/api/chat/history', methods=['GET'])
def get_chat_history():
    history = load_chat_history()
    return jsonify({'messages': history})

@app.route('/api/chat/clear', methods=['POST'])
def clear_chat_history():
    save_chat_history([])
    return jsonify({'ok': True})

@app.route('/api/chat/send', methods=['POST'])
@handle_error
def send_chat_message():
    """Non-streaming agent endpoint. Returns complete result after agent loop finishes."""
    data = request.json
    message = data.get('message', '').strip()
    if not message:
        return jsonify({'error': 'Message required'}), 400

    llm_config = load_llm_config()
    if not llm_config.get('api_key'):
        return jsonify({'error': 'Please configure LLM API key in settings'}), 400

    try:
        events = []
        result = run_agent_loop(message, llm_config, stream_callback=lambda e: events.append(e))
        save_chat_history(result['history'])
        return jsonify({
            'response': {'role': 'assistant', 'content': result['content']},
            'iterations': result['iterations'],
            'tool_calls_made': result['tool_calls_made'],
            'events': events,
            'history': result['history'][-20:],
        })
    except Exception as e:
        return jsonify({'error': str(e), 'response': {'role': 'assistant', 'content': f'Error: {str(e)}'}}), 500

@app.route('/api/chat/send/stream', methods=['POST'])
def send_chat_stream():
    """SSE streaming agent endpoint. Streams text, tool execution, and status in real-time."""
    data = request.json
    message = data.get('message', '').strip()
    if not message:
        return jsonify({'error': 'Message required'}), 400

    llm_config = load_llm_config()
    if not llm_config.get('api_key'):
        return jsonify({'error': 'Please configure LLM API key'}), 400

    def generate():
        try:
            for sse_event in run_agent_loop_stream(message, llm_config):
                yield sse_event
        except Exception as e:
            yield f"data: {json.dumps({'type': 'error', 'content': str(e)})}\n\n"

    return Response(generate(), mimetype='text/event-stream',
                    headers={'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no'})

@app.route('/api/tools', methods=['GET'])
def list_agent_tools():
    """List all available agent tools with their schemas."""
    tools_info = []
    for t in AGENT_TOOLS:
        f = t.get('function', {})
        tools_info.append({
            'name': f.get('name', ''),
            'description': f.get('description', ''),
            'parameters': f.get('parameters', {}),
        })
    return jsonify({'tools': tools_info})

# ==================== Server Management APIs ====================
@app.route('/api/server/status', methods=['GET'])
@handle_error
def server_status():
    """Returns server uptime, version, process info, memory usage, connected processes count."""
    import resource
    uptime = time.time() - SERVER_START_TIME
    hours, remainder = divmod(int(uptime), 3600)
    minutes, seconds = divmod(remainder, 60)

    mem_info = {}
    try:
        rusage = resource.getrusage(resource.RUSAGE_SELF)
        mem_info['rss_mb'] = round(rusage.ru_maxrss / 1024, 1)  # Linux: ru_maxrss is in KB
    except Exception:
        try:
            mem_info['rss_mb'] = round(os.popen('ps -o rss= -p ' + str(os.getpid())).read().strip().split()[0] / 1024, 1)
        except Exception:
            mem_info['rss_mb'] = 'unknown'

    active_procs = sum(1 for p in running_processes.values() if p.get('running', False))

    return jsonify({
        'status': 'running',
        'version': SERVER_VERSION,
        'uptime_seconds': int(uptime),
        'uptime': f'{hours}h {minutes}m {seconds}s',
        'pid': os.getpid(),
        'port': PORT,
        'host': HOST,
        'python': sys.version.split()[0],
        'platform': sys.platform,
        'workspace': load_config().get('workspace', WORKSPACE),
        'memory': mem_info,
        'connected_processes': active_procs,
        'config_dir': CONFIG_DIR,
        'server_dir': SERVER_DIR,
    })

@app.route('/api/server/restart', methods=['POST'])
@handle_error
def server_restart():
    """Restart the Flask server by spawning a new process and exiting current."""
    # Write a marker file so the new process knows it's a restart
    marker = os.path.join(CONFIG_DIR, 'restart_marker.json')
    with open(marker, 'w') as f:
        json.dumps({'pid': os.getpid(), 'time': datetime.now().isoformat()}, f)

    _log_write('[SERVER] Restart requested, spawning new process...')

    # Spawn new server process
    server_script = os.path.join(SERVER_DIR, 'server.py')
    env = os.environ.copy()
    env['PHONEIDE_WORKSPACE'] = load_config().get('workspace', WORKSPACE)
    env['PHONEIDE_PORT'] = str(PORT)
    env['PHONEIDE_HOST'] = HOST

    try:
        subprocess.Popen(
            [sys.executable, server_script],
            env=env,
            stdout=open(os.path.join(CONFIG_DIR, 'server.log'), 'a'),
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        return jsonify({'ok': True, 'message': 'Server restarting...'})
    except Exception as e:
        return jsonify({'ok': False, 'error': str(e)}), 500
    finally:
        # Exit current process after a short delay
        def _exit():
            time.sleep(0.5)
            os._exit(0)
        threading.Thread(target=_exit, daemon=True).start()

@app.route('/api/server/logs', methods=['POST'])
@handle_error
def server_logs():
    """Returns last N lines of server output log."""
    data = request.json or {}
    count = data.get('count', 100)
    log_file = os.path.join(CONFIG_DIR, 'server.log')

    if not os.path.exists(log_file):
        return jsonify({'lines': [], 'source': 'memory', 'total': len(_log_buffer)})

    try:
        with open(log_file, 'r', errors='ignore') as f:
            all_lines = f.readlines()
        last_n = all_lines[-count:]
        return jsonify({
            'lines': [line.rstrip('\n') for line in last_n],
            'source': 'file',
            'total': len(all_lines),
        })
    except Exception as e:
        # Fall back to memory buffer
        mem_lines = [f'{e["time"]} {e["text"]}' for e in list(_log_buffer)[-count:]]
        return jsonify({'lines': mem_lines, 'source': 'memory', 'total': len(_log_buffer)})

@app.route('/api/server/logs/stream', methods=['GET'])
def server_logs_stream():
    """SSE stream of server log output in real-time."""
    log_file = os.path.join(CONFIG_DIR, 'server.log')

    def generate():
        if os.path.exists(log_file):
            file_size = os.path.getsize(log_file)
        else:
            file_size = 0

        idx = len(_log_buffer)

        while True:
            # Check memory buffer for new entries
            if idx < len(_log_buffer):
                with _log_lock:
                    entries = list(_log_buffer)
                for entry in entries[idx:]:
                    yield f"data: {json.dumps({'type': 'log', 'text': entry['text'], 'time': entry['time']})}\n\n"
                idx = len(entries)

            # Check log file for new content
            if os.path.exists(log_file):
                new_size = os.path.getsize(log_file)
                if new_size > file_size:
                    try:
                        with open(log_file, 'r', errors='ignore') as f:
                            f.seek(file_size)
                            new_content = f.read()
                        for line in new_content.split('\n'):
                            line = line.strip()
                            if line:
                                yield f"data: {json.dumps({'type': 'log', 'text': line, 'time': datetime.now().isoformat()})}\n\n"
                        file_size = new_size
                    except Exception:
                        pass

            time.sleep(0.5)

    return Response(generate(), mimetype='text/event-stream',
                    headers={'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no'})

# ==================== IDE Update APIs ====================

# GitHub repo config for releases
GITHUB_REPO = 'ctz168/phoneide'
GITHUB_RELEASES_URL = f'https://api.github.com/repos/{GITHUB_REPO}/releases/latest'

def _get_git_token():
    """Get GitHub token from config file or environment variable."""
    cfg = load_config()
    token = cfg.get('github_token', '') or os.environ.get('GITHUB_TOKEN', '')
    return token

def _ensure_writable(path):
    """Ensure a directory tree is writable by the current user."""
    if os.path.isdir(path):
        os.chmod(path, 0o755)

def _fetch_github_json(url, timeout=15):
    """Helper to fetch JSON from GitHub API."""
    req = urllib.request.Request(url, headers={
        'User-Agent': 'PhoneIDE-Server',
        'Accept': 'application/vnd.github.v3+json',
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())

@app.route('/api/update/check', methods=['POST'])
@handle_error
def update_check():
    """Check for code updates by comparing local git HEAD with remote main branch."""
    try:
        code_update = False
        remote_sha = ''
        remote_message = ''
        local_sha = ''
        commits_behind = 0
        remote_date = ''
        remote_author = ''

        # Fetch latest commit info from GitHub
        commit_data = _fetch_github_json(f'https://api.github.com/repos/{GITHUB_REPO}/commits/main')
        remote_sha = commit_data.get('sha', '')
        remote_message = commit_data.get('commit', {}).get('message', '')
        remote_date = commit_data.get('commit', {}).get('committer', {}).get('date', '')
        remote_author = commit_data.get('commit', {}).get('author', {}).get('name', '')

        # Get local HEAD
        try:
            r = git_cmd('rev-parse HEAD', cwd=SERVER_DIR)
            if r['ok']:
                local_sha = r['stdout'].strip()
        except Exception:
            pass

        # Compare: if local != remote, there are code updates
        if local_sha and remote_sha and local_sha != remote_sha:
            code_update = True
            try:
                r = git_cmd(f'rev-list --count {local_sha}..{remote_sha}', cwd=SERVER_DIR)
                if r['ok']:
                    commits_behind = int(r['stdout'].strip())
            except Exception:
                commits_behind = -1
        elif not local_sha:
            # No local git info — assume update needed
            code_update = True
            commits_behind = -1

        return jsonify({
            'update_available': code_update,
            'code_update': code_update,
            'current_version': APP_VERSION,
            'local_sha': local_sha[:8] if local_sha else 'unknown',
            'remote_sha': remote_sha[:8] if remote_sha else 'unknown',
            'remote_message': remote_message.split('\n')[0] if remote_message else '',
            'remote_date': remote_date,
            'remote_author': remote_author,
            'commits_behind': commits_behind,
        })
    except Exception as e:
        return jsonify({'error': str(e), 'update_available': False, 'current_version': APP_VERSION})

@app.route('/api/update/apply', methods=['POST'])
@handle_error
def update_apply():
    """Pull latest code from GitHub and restart server."""
    try:
        # Check if SERVER_DIR is a git repo
        if not os.path.exists(os.path.join(SERVER_DIR, '.git')):
            return jsonify({'error': 'Server directory is not a git repository'}), 400

        # Ensure SERVER_DIR is writable
        try:
            subprocess.run(f'chmod -R 755 {shlex_quote(SERVER_DIR)}', shell=True, capture_output=True, timeout=15)
        except Exception:
            pass

        # git stash any local changes
        stash_result = git_cmd('stash', cwd=SERVER_DIR)

        # Pull latest from origin main
        pull_result = git_cmd('pull origin main', cwd=SERVER_DIR, timeout=120)
        if not pull_result['ok']:
            # Restore stash on failure
            git_cmd('stash pop', cwd=SERVER_DIR)
            detail = pull_result['stderr'] or pull_result['stdout'] or 'unknown error'
            return jsonify({'error': f'Git pull failed: {detail}', 'stdout': pull_result['stdout']}), 500

        # Restore stash
        if stash_result['ok']:
            git_cmd('stash pop', cwd=SERVER_DIR)

        _log_write(f'[UPDATE] Pulled latest code: {pull_result["stdout"][:200]}')

        # Restart server
        marker = os.path.join(CONFIG_DIR, 'restart_marker.json')
        with open(marker, 'w') as f:
            json.dump({'pid': os.getpid(), 'reason': 'update', 'time': datetime.now().isoformat()}, f)

        env = os.environ.copy()
        env['PHONEIDE_WORKSPACE'] = load_config().get('workspace', WORKSPACE)
        env['PHONEIDE_PORT'] = str(PORT)
        env['PHONEIDE_HOST'] = HOST

        server_script = os.path.join(SERVER_DIR, 'server.py')
        subprocess.Popen(
            [sys.executable, server_script],
            env=env,
            stdout=open(os.path.join(CONFIG_DIR, 'server.log'), 'a'),
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

        def _exit():
            time.sleep(0.5)
            os._exit(0)
        threading.Thread(target=_exit, daemon=True).start()

        return jsonify({
            'ok': True,
            'message': 'Update applied, server restarting...',
            'pull_output': pull_result['stdout'][:500],
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

# ==================== System Info API ====================
@app.route('/api/system/info', methods=['GET'])
def system_info():
    info = {
        'python': sys.version.split()[0],
        'platform': sys.platform,
        'workspace': load_config().get('workspace', WORKSPACE),
        'pid': os.getpid(),
        'server_version': SERVER_VERSION,
    }
    try:
        result = subprocess.run('uname -a', shell=True, capture_output=True, text=True, timeout=5)
        if result.returncode == 0:
            info['system'] = result.stdout.strip()
    except Exception:
        pass
    return jsonify(info)

# ==================== Main ====================
if __name__ == '__main__':
    # Ensure workspace exists
    os.makedirs(WORKSPACE, exist_ok=True)

    # Set up log file
    _log_file_path = os.path.join(CONFIG_DIR, 'server.log')
    _log_fh = open(_log_file_path, 'a')
    _log_fh.write(f'\n--- PhoneIDE Server starting at {datetime.now().isoformat()} ---\n')
    _log_fh.flush()

    # Redirect stdout/stderr to log file while keeping console output
    import io

    class _TeeStream:
        """Tee output to both file and console."""
        def __init__(self, *targets):
            self.targets = targets
            self._lock = threading.Lock()
        def write(self, data):
            with self._lock:
                for t in self.targets:
                    try:
                        t.write(data)
                        t.flush()
                    except Exception:
                        pass
                _log_write(data.rstrip('\n'))
        def flush(self):
            with self._lock:
                for t in self.targets:
                    try:
                        t.flush()
                    except Exception:
                        pass
        def isatty(self):
            return False

    sys.stdout = _TeeStream(sys.__stdout__, _log_fh)
    sys.stderr = _TeeStream(sys.__stderr__, _log_fh)

    print(f"""
    ╔══════════════════════════════════╗
    ║       PhoneIDE Server           ║
    ║   Mobile Web IDE for Termux     ║
    ╠══════════════════════════════════╣
    ║  Port:    {PORT:<22}║
    ║  Host:    {HOST:<22}║
    ║  Workspace: {os.path.basename(WORKSPACE):<18}║
    ║  URL:     http://localhost:{PORT:<8}║
    ║  Version: {SERVER_VERSION:<22}║
    ╚══════════════════════════════════╝
    """)

    # Initialize git if needed
    config = load_config()
    ws = config.get('workspace', WORKSPACE)
    if not os.path.exists(os.path.join(ws, '.git')):
        try:
            subprocess.run(f'git init {shlex_quote(ws)}', shell=True, capture_output=True, timeout=5)
            print(f"[INFO] Initialized git repo in {ws}")
        except Exception:
            pass

    _log_write(f'[SERVER] Starting on {HOST}:{PORT}, workspace: {WORKSPACE}')

    app.run(host=HOST, port=PORT, debug=False, threaded=True)
