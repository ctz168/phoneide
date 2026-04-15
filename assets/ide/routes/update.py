"""
PhoneIDE - IDE Update API routes.
"""

import os
import re
import sys
import json
import subprocess
import threading
import time
import urllib.request
import urllib.error
from datetime import datetime
from flask import Blueprint, jsonify, request
from utils import handle_error, load_config, save_chat_history, WORKSPACE, SERVER_DIR, PORT, HOST, CONFIG_DIR, log_write
from routes.git import git_cmd

bp = Blueprint('update', __name__)

# GitHub repo config for releases
GITHUB_REPO = 'ctz168/phoneide'
GITHUB_RELEASES_URL = f'https://api.github.com/repos/{GITHUB_REPO}/releases/latest'


def _fetch_github_json(url, timeout=15):
    """Helper to fetch JSON from GitHub API."""
    req = urllib.request.Request(url, headers={
        'User-Agent': 'PhoneIDE-Server',
        'Accept': 'application/vnd.github.v3+json',
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def _parse_version(version_str):
    """Parse version string like '3.0.40' or '3.0.40-build.6305' into comparable tuple.

    Returns (major, minor, patch) tuple, ignoring any build suffix.
    Returns None if parsing fails.
    """
    if not version_str:
        return None
    # Strip leading 'v' and split on first '-' to remove build suffix
    cleaned = version_str.lstrip('v').split('-')[0]
    m = re.match(r'(\d+)\.(\d+)\.(\d+)', cleaned)
    if m:
        return (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    return None


def _get_current_version():
    """Try to read the current app version from build.gradle."""
    gradle_path = os.path.join(SERVER_DIR, 'android', 'app', 'build.gradle')
    if os.path.exists(gradle_path):
        try:
            with open(gradle_path, 'r') as f:
                for line in f:
                    if 'versionName' in line and 'versionCode' not in line:
                        m = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", line)
                        if m:
                            return m.group(1)
        except Exception:
            pass
    # Fallback: try reading from local git describe
    try:
        r = git_cmd('describe --tags --abbrev=0', cwd=SERVER_DIR)
        if r['ok'] and r['stdout'].strip():
            return r['stdout'].strip().lstrip('v')
    except Exception:
        pass
    return '0.0.0'


@bp.route('/api/update/check', methods=['POST'])
@handle_error
def update_check():
    """Check for updates by fetching latest version from GitHub Releases."""
    try:
        # Get current version
        current_version = _get_current_version()
        current_ver = _parse_version(current_version)

        # 1. Check GitHub Releases for latest APK
        release_data = _fetch_github_json(GITHUB_RELEASES_URL)
        latest_tag = release_data.get('tag_name', '')
        release_name = release_data.get('name', latest_tag)
        release_body = release_data.get('body', '')
        release_date = release_data.get('published_at', '')
        html_url = release_data.get('html_url', '')

        # Parse latest version from tag
        latest_ver = _parse_version(latest_tag)

        # Find the release APK asset
        apk_url = ''
        apk_size = 0
        for asset in release_data.get('assets', []):
            if asset.get('name', '').endswith('.apk') and 'release' in asset.get('name', '').lower():
                apk_url = asset.get('browser_download_url', '')
                apk_size = asset.get('size', 0)
                break

        # If no release APK found, try the first APK asset
        if not apk_url:
            for asset in release_data.get('assets', []):
                if asset.get('name', '').endswith('.apk'):
                    apk_url = asset.get('browser_download_url', '')
                    apk_size = asset.get('size', 0)
                    break

        # Compare versions for APK update
        apk_update = False
        if apk_url and current_ver and latest_ver:
            apk_update = latest_ver > current_ver
        elif apk_url and not current_ver:
            # If we can't determine current version, assume update needed
            apk_update = True

        # 2. Also check code commits
        code_update = False
        remote_sha = ''
        remote_message = ''
        local_sha = ''
        commits_behind = 0
        try:
            commit_data = _fetch_github_json(f'https://api.github.com/repos/{GITHUB_REPO}/commits/main')
            remote_sha = commit_data.get('sha', '')
            remote_message = commit_data.get('commit', {}).get('message', '')

            try:
                r = git_cmd('rev-parse HEAD', cwd=SERVER_DIR)
                if r['ok']:
                    local_sha = r['stdout'].strip()
            except Exception:
                pass

            if local_sha and local_sha != remote_sha:
                code_update = True
                try:
                    r = git_cmd(f'rev-list --count {local_sha}..{remote_sha}', cwd=SERVER_DIR)
                    if r['ok']:
                        commits_behind = int(r['stdout'].strip())
                except Exception:
                    commits_behind = -1
        except Exception:
            pass

        # Check if update available (APK or code)
        update_available = apk_update or code_update

        return jsonify({
            'update_available': update_available,
            'apk_update': apk_update,
            'code_update': code_update,
            'current_version': current_version,
            'new_version': latest_tag.lstrip('v').split('-')[0],
            'latest_tag': latest_tag,
            'release_name': release_name,
            'release_body': release_body,
            'release_date': release_date,
            'release_url': html_url,
            'apk_url': apk_url,
            'apk_size': apk_size,
            'apk_size_human': f'{apk_size / 1024 / 1024:.1f}MB' if apk_size > 0 else 'Unknown',
            'local_sha': local_sha[:8] if local_sha else 'unknown',
            'remote_sha': remote_sha[:8] if remote_sha else 'unknown',
            'remote_message': remote_message.split('\n')[0] if remote_message else '',
            'commits_behind': commits_behind,
        })
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return jsonify({'error': 'No releases found', 'update_available': False, 'current_version': _get_current_version()})
        return jsonify({'error': f'GitHub API error: {e.code}', 'update_available': False, 'current_version': _get_current_version()})
    except Exception as e:
        return jsonify({'error': str(e), 'update_available': False, 'current_version': _get_current_version()})


@bp.route('/api/update/apply', methods=['POST'])
@handle_error
def update_apply():
    """Pull latest code from GitHub and restart server."""
    try:
        # Check if SERVER_DIR is a git repo
        if not os.path.exists(os.path.join(SERVER_DIR, '.git')):
            return jsonify({'error': 'Server directory is not a git repository'}), 400

        # git stash any local changes
        stash_result = git_cmd('stash', cwd=SERVER_DIR)

        # Pull latest
        pull_result = git_cmd('pull origin main', cwd=SERVER_DIR, timeout=120)
        if not pull_result['ok']:
            # Restore stash on failure
            git_cmd('stash pop', cwd=SERVER_DIR)
            return jsonify({'error': f'Git pull failed: {pull_result["stderr"]}', 'stdout': pull_result['stdout']}), 500

        # Restore stash
        if stash_result['ok']:
            git_cmd('stash pop', cwd=SERVER_DIR)

        log_write(f'[UPDATE] Pulled latest code: {pull_result["stdout"][:200]}')

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
