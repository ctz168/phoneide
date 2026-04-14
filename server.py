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
        'theme': 'dark',
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
    is_dir = data.get('is_dir', False)
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
    data = request.json
    message = data.get('message', '').strip()
    if not message:
        return jsonify({'error': 'Message required'}), 400

    llm_config = load_llm_config()
    if not llm_config.get('api_key'):
        return jsonify({'error': 'Please configure LLM API key in settings'}), 400

    history = load_chat_history()
    user_msg = {'role': 'user', 'content': message, 'time': datetime.now().isoformat()}
    history.append(user_msg)

    try:
        response = call_llm(history, llm_config)
        assistant_msg = {
            'role': 'assistant',
            'content': response.get('content', ''),
            'tool_calls': response.get('tool_calls', []),
            'time': datetime.now().isoformat(),
        }
        history.append(assistant_msg)

        # Execute tool calls
        tool_results = []
        if response.get('tool_calls'):
            for tc in response['tool_calls']:
                result = execute_tool(tc, llm_config)
                tool_results.append(result)
                history.append({
                    'role': 'tool',
                    'name': tc.get('name', ''),
                    'content': result.get('result', ''),
                    'time': datetime.now().isoformat(),
                })

            # Get final response after tools
            if tool_results:
                final = call_llm(history, llm_config)
                history.append({
                    'role': 'assistant',
                    'content': final.get('content', ''),
                    'time': datetime.now().isoformat(),
                })

        save_chat_history(history)
        return jsonify({
            'response': assistant_msg,
            'tool_results': tool_results,
            'history': history[-10:],  # Return last 10 for display
        })
    except Exception as e:
        error_msg = {'role': 'assistant', 'content': f'Error: {str(e)}', 'time': datetime.now().isoformat(), 'error': True}
        history.append(error_msg)
        save_chat_history(history)
        return jsonify({'response': error_msg, 'tool_results': [], 'error': str(e)}), 500

@app.route('/api/chat/send/stream', methods=['POST'])
def send_chat_stream():
    """SSE streaming endpoint for LLM responses"""
    data = request.json
    message = data.get('message', '').strip()
    if not message:
        return jsonify({'error': 'Message required'}), 400

    llm_config = load_llm_config()
    if not llm_config.get('api_key'):
        return jsonify({'error': 'Please configure LLM API key'}), 400

    def generate():
        history = load_chat_history()
        user_msg = {'role': 'user', 'content': message, 'time': datetime.now().isoformat()}
        history.append(user_msg)

        try:
            for chunk in call_llm_stream(history, llm_config):
                yield f"data: {json.dumps(chunk)}\n\n"

            # Send done signal
            yield f"data: {json.dumps({'type': 'done'})}\n\n"

            # Save history
            full_content = ''
            for item in history:
                if item.get('role') == 'assistant':
                    full_content = item.get('content', '')
            history.append({
                'role': 'assistant',
                'content': full_content,
                'time': datetime.now().isoformat(),
            })
            save_chat_history(history)

        except Exception as e:
            yield f"data: {json.dumps({'type': 'error', 'content': str(e)})}\n\n"

    return Response(generate(), mimetype='text/event-stream',
                    headers={'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no'})

# ---- LLM Integration ----
def call_llm(messages, config):
    """Call LLM with tool definitions"""
    import urllib.request
    import urllib.error

    provider = config.get('provider', 'openai')
    api_key = config.get('api_key', '')
    api_base = config.get('api_base', 'https://api.openai.com/v1')
    model = config.get('model', 'gpt-4o-mini')
    temperature = config.get('temperature', 0.7)
    max_tokens = config.get('max_tokens', 4096)

    if not api_base.endswith('/'):
        api_base += '/'

    # Build system message with tools
    system_msg = config.get('system_prompt', 'You are a helpful coding assistant.')
    system_msg += '\n\nYou have access to the following tools. Use them when needed:\n'
    for tool in get_available_tools():
        system_msg += f"- {tool['name']}: {tool['description']}\n"
    system_msg += "\nTo use a tool, respond with a JSON object: {\"tool\": \"tool_name\", \"args\": {\"key\": \"value\"}}\n"

    api_messages = [{'role': 'system', 'content': system_msg}]
    for msg in messages[-20:]:  # Last 20 messages
        api_messages.append({'role': msg['role'], 'content': msg.get('content', '')})

    payload = {
        'model': model,
        'messages': api_messages,
        'temperature': temperature,
        'max_tokens': max_tokens,
    }

    url = f'{api_base}chat/completions'
    headers = {
        'Content-Type': 'application/json',
        'Authorization': f'Bearer {api_key}',
    }

    req = urllib.request.Request(url, json.dumps(payload).encode(), headers=headers, method='POST')

    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read().decode())
            content = result['choices'][0]['message']['content']
            tool_calls = parse_tool_calls(content)
            return {'content': content, 'tool_calls': tool_calls}
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        raise Exception(f'LLM API error ({e.code}): {body[:500]}')
    except Exception as e:
        raise Exception(f'LLM request failed: {str(e)}')

def call_llm_stream(messages, config):
    """Stream LLM response"""
    import urllib.request

    provider = config.get('provider', 'openai')
    api_key = config.get('api_key', '')
    api_base = config.get('api_base', 'https://api.openai.com/v1')
    model = config.get('model', 'gpt-4o-mini')
    temperature = config.get('temperature', 0.7)
    max_tokens = config.get('max_tokens', 4096)

    if not api_base.endswith('/'):
        api_base += '/'

    system_msg = config.get('system_prompt', 'You are a helpful coding assistant.')
    api_messages = [{'role': 'system', 'content': system_msg}]
    for msg in messages[-20:]:
        api_messages.append({'role': msg['role'], 'content': msg.get('content', '')})

    payload = {
        'model': model,
        'messages': api_messages,
        'temperature': temperature,
        'max_tokens': max_tokens,
        'stream': True,
    }

    url = f'{api_base}chat/completions'
    headers = {
        'Content-Type': 'application/json',
        'Authorization': f'Bearer {api_key}',
    }

    req = urllib.request.Request(url, json.dumps(payload).encode(), headers=headers, method='POST')

    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            buffer = ''
            for chunk_bytes in iter(lambda: resp.read(1), b''):
                chunk = chunk_bytes.decode('utf-8', errors='ignore')
                buffer += chunk
                while '\n' in buffer:
                    line, buffer = buffer.split('\n', 1)
                    line = line.strip()
                    if line.startswith('data: '):
                        data_str = line[6:]
                        if data_str == '[DONE]':
                            return
                        try:
                            data = json.loads(data_str)
                            delta = data.get('choices', [{}])[0].get('delta', {})
                            content = delta.get('content', '')
                            if content:
                                yield {'type': 'content', 'content': content}
                        except:
                            continue
    except Exception as e:
        yield {'type': 'error', 'content': str(e)}

def parse_tool_calls(content):
    """Parse tool call JSON from LLM response"""
    calls = []
    try:
        # Try to find JSON tool calls in the response
        json_match = re.search(r'\{[^{}]*"tool"\s*:\s*"[^"]+"[^{}]*\}', content, re.DOTALL)
        if json_match:
            call = json.loads(json_match.group())
            calls.append(call)
        return calls
    except:
        return calls

# ---- Built-in Tools ----
def get_available_tools():
    """Return list of available agent tools"""
    return [
        {
            'name': 'read_file',
            'description': 'Read the contents of a file in the workspace',
            'args': {'path': 'Relative file path'},
        },
        {
            'name': 'write_file',
            'description': 'Write content to a file in the workspace',
            'args': {'path': 'Relative file path', 'content': 'File content'},
        },
        {
            'name': 'execute_code',
            'description': 'Execute Python code or a script',
            'args': {'code': 'Python code to execute', 'file_path': 'Optional file to run'},
        },
        {
            'name': 'search_files',
            'description': 'Search for text across all files in workspace',
            'args': {'query': 'Search text', 'file_pattern': 'Optional file glob pattern'},
        },
        {
            'name': 'list_files',
            'description': 'List files in a directory',
            'args': {'path': 'Optional directory path (relative)'},
        },
        {
            'name': 'git_status',
            'description': 'Check git repository status',
            'args': {},
        },
        {
            'name': 'git_diff',
            'description': 'Show git diff of changes',
            'args': {'file_path': 'Optional specific file'},
        },
        {
            'name': 'terminal',
            'description': 'Execute a shell command',
            'args': {'command': 'Shell command to execute'},
        },
        {
            'name': 'install_package',
            'description': 'Install a Python package via pip',
            'args': {'package': 'Package name or requirements string'},
        },
    ]

@app.route('/api/tools', methods=['GET'])
def list_tools():
    return jsonify({'tools': get_available_tools()})

def execute_tool(tool_call, llm_config):
    """Execute a tool call from the LLM"""
    tool_name = tool_call.get('tool', tool_call.get('name', ''))
    args = tool_call.get('args', tool_call.get('arguments', {}))

    try:
        if tool_name == 'read_file':
            path = args.get('path', '')
            config = load_config()
            base = config.get('workspace', WORKSPACE)
            target = os.path.realpath(os.path.join(base, path))
            with open(target, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read(50000)  # Limit
            return {'tool': tool_name, 'result': content[:50000], 'ok': True}

        elif tool_name == 'write_file':
            path = args.get('path', '')
            content = args.get('content', '')
            config = load_config()
            base = config.get('workspace', WORKSPACE)
            target = os.path.join(base, path)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, 'w') as f:
                f.write(content)
            return {'tool': tool_name, 'result': f'File saved: {path}', 'ok': True}

        elif tool_name == 'execute_code':
            code = args.get('code', '')
            file_path = args.get('file_path', '')
            config = load_config()
            base = config.get('workspace', WORKSPACE)
            if file_path:
                target = os.path.join(base, file_path)
                proc_id = run_process(f'python3 {shlex_quote(target)}', cwd=base)
            else:
                tmp = os.path.join(base, '.phoneide_agent_tmp.py')
                with open(tmp, 'w') as f:
                    f.write(code)
                proc_id = run_process(f'python3 {shlex_quote(tmp)}', cwd=base)
            time.sleep(1)
            outputs = process_outputs.get(proc_id, [])
            result = '\n'.join(o.get('text', '') for o in outputs)
            return {'tool': tool_name, 'result': result or 'No output', 'ok': True}

        elif tool_name == 'search_files':
            query = args.get('query', '')
            file_pattern = args.get('file_pattern', '*')
            config = load_config()
            base = config.get('workspace', WORKSPACE)
            results = []
            for root, dirs, files in os.walk(base):
                dirs[:] = [d for d in dirs if d not in {'.git', '__pycache__', 'node_modules', '.venv'}]
                for fname in files:
                    if fnmatch.fnmatch(fname, file_pattern):
                        fpath = os.path.join(root, fname)
                        try:
                            with open(fpath, 'r', errors='ignore') as f:
                                for i, line in enumerate(f, 1):
                                    if query.lower() in line.lower():
                                        rel = os.path.relpath(fpath, base)
                                        results.append(f'{rel}:{i}: {line.strip()[:200]}')
                                        if len(results) >= 50:
                                            break
                        except:
                            pass
                if len(results) >= 50:
                    break
            return {'tool': tool_name, 'result': '\n'.join(results) or 'No matches', 'ok': True}

        elif tool_name == 'list_files':
            path = args.get('path', '')
            config = load_config()
            base = config.get('workspace', WORKSPACE)
            target = os.path.join(base, path) if path else base
            items = os.listdir(target) if os.path.isdir(target) else []
            return {'tool': tool_name, 'result': '\n'.join(items) or 'Empty directory', 'ok': True}

        elif tool_name == 'git_status':
            r = git_cmd('status --short')
            return {'tool': tool_name, 'result': r['stdout'] or 'Clean working tree', 'ok': True}

        elif tool_name == 'git_diff':
            filepath = args.get('file_path', '')
            cmd = f'diff -- {shlex_quote(filepath)}' if filepath else 'diff'
            r = git_cmd(cmd)
            return {'tool': tool_name, 'result': r['stdout'] or 'No changes', 'ok': True}

        elif tool_name == 'terminal':
            command = args.get('command', '')
            config = load_config()
            base = config.get('workspace', WORKSPACE)
            proc_id = run_process(command, cwd=base)
            time.sleep(1)
            outputs = process_outputs.get(proc_id, [])
            result = '\n'.join(o.get('text', '') for o in outputs)
            return {'tool': tool_name, 'result': result or 'No output', 'ok': True}

        elif tool_name == 'install_package':
            package = args.get('package', '')
            config = load_config()
            venv = config.get('venv_path', '')
            pip = os.path.join(venv, 'bin', 'pip') if venv else 'pip3'
            proc_id = run_process(f'{pip} install {shlex_quote(package)}')
            time.sleep(2)
            outputs = process_outputs.get(proc_id, [])
            result = '\n'.join(o.get('text', '') for o in outputs)
            return {'tool': tool_name, 'result': result or 'Installing...', 'ok': True}

        else:
            return {'tool': tool_name, 'result': f'Unknown tool: {tool_name}', 'ok': False}

    except Exception as e:
        return {'tool': tool_name, 'result': f'Error: {str(e)}', 'ok': False}

# ---- System Info ----
@app.route('/api/system/info', methods=['GET'])
def system_info():
    info = {
        'python': sys.version.split()[0],
        'platform': sys.platform,
        'workspace': load_config().get('workspace', WORKSPACE),
        'pid': os.getpid(),
    }
    try:
        result = subprocess.run('uname -a', shell=True, capture_output=True, text=True, timeout=5)
        if result.returncode == 0:
            info['system'] = result.stdout.strip()
    except:
        pass
    return jsonify(info)

# ==================== Main ====================
if __name__ == '__main__':
    # Ensure workspace exists
    os.makedirs(WORKSPACE, exist_ok=True)

    print(f"""
    ╔══════════════════════════════════╗
    ║       PhoneIDE Server           ║
    ║   Mobile Web IDE for Termux     ║
    ╠══════════════════════════════════╣
    ║  Port:    {PORT:<22}║
    ║  Host:    {HOST:<22}║
    ║  Workspace: {os.path.basename(WORKSPACE):<18}║
    ║  URL:     http://localhost:{PORT:<8}║
    ╚══════════════════════════════════╝
    """)

    # Initialize git if needed
    config = load_config()
    ws = config.get('workspace', WORKSPACE)
    if not os.path.exists(os.path.join(ws, '.git')):
        try:
            subprocess.run(f'git init {shlex_quote(ws)}', shell=True, capture_output=True, timeout=5)
            print(f"[INFO] Initialized git repo in {ws}")
        except:
            pass

    app.run(host=HOST, port=PORT, debug=False, threaded=True)
