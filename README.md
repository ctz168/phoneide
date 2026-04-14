# PhoneIDE

A lightweight, mobile-optimized web IDE designed for Termux/Ubuntu environments.

## Features

- **Code Editor** - Syntax highlighting for 30+ languages, powered by CodeMirror
- **File Management** - Open folders, create/rename/delete files and directories
- **Git Integration** - Clone, pull, push, commit, branch management, diff view
- **Global Search** - Search and replace across all project files with regex support
- **Code Execution** - Run Python and other scripts with output streaming
- **Virtual Environments** - Create and manage Python virtual environments
- **AI Assistant** - Built-in LLM chat with agent tool execution
- **Mobile Optimized** - Touch gestures, swipe panels, responsive design
- **Dark Theme** - Easy on the eyes, Catppuccin-inspired color scheme

## Quick Start

```bash
# Install
bash install.sh

# Start
python3 server.py

# Or use the start script
bash start.sh

# Open in mobile browser
# http://localhost:1239
```

## Requirements

- Python 3.8+
- Modern mobile browser (Chrome, Firefox, Safari)

## Configuration

- Workspace: Default `~/phoneide_workspace`
- Port: Default `1239` (set via `PHONEIDE_PORT` env var)
- Config: `~/.phoneide/config.json`
- LLM Config: `~/.phoneide/llm_config.json`

## Usage

| Gesture | Action |
|---------|--------|
| Swipe right from left edge | Open file sidebar |
| Swipe left from right edge | Open AI chat |
| Swipe left on open sidebar | Close sidebar |
| Long press file | Context menu (rename, delete) |

## API

The server provides REST APIs on `http://localhost:1239`:

- `/api/files/*` - File operations
- `/api/run/*` - Code execution
- `/api/git/*` - Git operations
- `/api/search` - Global search
- `/api/chat/*` - LLM chat
- `/api/llm/config` - LLM configuration
- `/api/compilers` - Available compilers
- `/api/venv/*` - Virtual environment management

## License

MIT
