# PhoneIDE

一款轻量级的移动端 Web IDE，专为 Termux/Ubuntu 环境设计。

在手机上打开浏览器即可获得完整的代码编辑体验：语法高亮、文件管理、Git 操作、代码运行、AI 编程助手——一个应用搞定全部。

## 安装方式

### 方式一：Android APK 直接安装（推荐）

下载 APK 文件安装到 Android 手机上，自带 WebView 和终端，无需浏览器：

1. 从 [Releases](https://github.com/ctz168/phoneide/releases) 页面下载最新 APK
2. 在手机上启用 **设置 → 安全 → 允许安装未知来源应用**
3. 打开下载的 APK 文件安装
4. 首次启动需要先安装 [Termux](https://f-droid.org/packages/com.termux/)（从 F-Droid 安装）
5. 按照 PhoneIDE 向导完成 Ubuntu 环境初始化

> 前置条件：手机上需要先从 F-Droid 安装 Termux，PhoneIDE 会利用 Termux 的 proot-distro 来运行 Ubuntu 环境。

### 方式二：Termux 一键安装

```bash
curl -fsSL https://raw.githubusercontent.com/ctz168/phoneide/main/install.sh | bash -s -- -r ctz168/phoneide
```

上面这行命令会自动完成：克隆仓库 → 安装 Python 依赖 → 创建工作空间。

> 如果你的网络环境无法直接访问 GitHub Raw，也可以手动安装：

```bash
git clone https://github.com/ctz168/phoneide.git && cd phoneide && bash install.sh
```

## 快速启动

```bash
cd phoneide
python3 server.py
```

然后在手机浏览器中打开 `http://localhost:1239` 即可使用。

也可以用启动脚本（会自动处理端口占用并打开浏览器）：

```bash
bash start.sh
# 或指定端口
bash start.sh 8080
```

## 功能特性

### 代码编辑器

基于 CodeMirror 5 内核，支持 30+ 种编程语言的语法高亮、自动补全、括号匹配、代码折叠、行号显示等功能。默认适配 Python，同时也支持 JavaScript、TypeScript、Go、Rust、Java、C/C++ 等主流语言。编辑器内置搜索替换功能，支持正则表达式匹配，可以快速定位和批量修改代码。

### 文件管理

完整的文件树浏览体验，支持打开任意文件夹作为工作空间。可以新建文件和目录、重命名、删除、复制粘贴。长按文件弹出上下文菜单，提供更多操作选项。文件列表会自动识别文件类型并显示对应的图标，让目录结构一目了然。

### Git 集成

内置全套 Git 操作界面，无需离开 IDE 即可完成版本管理。支持查看状态、提交日志、分支切换、暂存区管理、远程推送和拉取、仓库克隆、Diff 查看以及 Stash 暂存。在左侧面板的 Git 标签页中，可以直观地看到哪些文件已修改、已暂存或未跟踪。

### 全局搜索

支持在整个项目范围内搜索文本内容，包括正则表达式搜索、大小写敏感切换、文件类型过滤等功能。搜索结果以列表形式展示，点击即可跳转到对应文件的匹配行。还支持跨文件的批量替换操作。

### 代码运行

支持直接在 IDE 中运行代码，默认使用 python3 编译器。支持自动检测系统中已安装的编译器和运行时，包括 Python、Node.js、GCC、G++、Go、Rust、Ruby、Lua、Bash 等。运行输出通过 SSE（Server-Sent Events）实时流式推送到前端，运行过程中随时可以终止进程。

### 虚拟环境

内置 Python 虚拟环境管理功能，可以一键创建、切换和管理 venv。激活虚拟环境后，运行代码时会自动使用 venv 中的 Python 和已安装的包。还可以查看当前虚拟环境中已安装的包列表，方便包管理。

### AI 编程助手

右侧滑出面板集成了 LLM 对话功能，支持配置任意 OpenAI 兼容的 API（包括自定义 API 地址）。AI 助手内置了 9 种工具能力：读写文件、执行代码、全局搜索、Git 操作、终端命令等。对话历史自动保存，支持上下文连续对话。

### 移动端优化

专为手机触屏操作设计：从左侧边缘右滑打开文件侧边栏，从右侧边缘左滑打开 AI 对话面板。支持安全区域适配（刘海屏等），底部有调试/运行输出窗口，可通过左侧面板按钮切换显示隐藏。

### 深色主题

采用 Catppuccin 风格的深色配色方案，长时间编码也不伤眼。界面元素层次分明，代码高亮清晰易读。

## 环境要求

| 项目 | 最低要求 |
|------|----------|
| Python | 3.8 及以上 |
| 浏览器 | Chrome / Firefox / Safari（近两年版本） |
| 操作系统 | Termux (Android) / Ubuntu / Debian |

## 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 工作空间 | `~/phoneide_workspace` | 代码文件存放目录 |
| 端口 | `1239` | 可通过 `PHONEIDE_PORT` 环境变量修改 |
| 配置文件 | `~/.phoneide/config.json` | IDE 基本设置 |
| LLM 配置 | `~/.phoneide/llm_config.json` | AI 助手 API 配置 |
| 聊天记录 | `~/.phoneide/chat_history.json` | 对话历史（自动保存最近 200 条） |

## 手势操作

| 手势 | 功能 |
|------|------|
| 从左侧边缘右滑 | 打开文件侧边栏 |
| 从右侧边缘左滑 | 打开 AI 对话面板 |
| 在已打开的侧栏上左滑 | 关闭侧栏 |
| 长按文件 | 弹出上下文菜单（重命名、删除等） |

## API 接口

服务端运行在 `http://localhost:1239`，提供以下 REST API：

| 接口路径 | 功能 |
|----------|------|
| `GET /api/files/list` | 列出目录文件 |
| `GET /api/files/read` | 读取文件内容 |
| `POST /api/files/save` | 保存文件 |
| `POST /api/files/create` | 创建文件/目录 |
| `POST /api/files/delete` | 删除文件/目录 |
| `POST /api/files/rename` | 重命名文件/目录 |
| `POST /api/files/open_folder` | 打开文件夹作为工作空间 |
| `POST /api/run/execute` | 执行代码 |
| `POST /api/run/stop` | 终止运行 |
| `GET /api/run/output/stream` | SSE 实时输出流 |
| `GET /api/compilers` | 获取可用编译器列表 |
| `POST /api/venv/create` | 创建虚拟环境 |
| `POST /api/venv/activate` | 激活虚拟环境 |
| `GET /api/venv/packages` | 查看已安装包 |
| `GET /api/git/status` | Git 状态 |
| `POST /api/git/commit` | Git 提交 |
| `POST /api/git/push` | Git 推送 |
| `POST /api/git/pull` | Git 拉取 |
| `POST /api/git/clone` | 克隆仓库 |
| `POST /api/search` | 全局搜索 |
| `POST /api/search/replace` | 全局替换 |
| `POST /api/chat/send` | 发送 AI 对话 |

## 项目结构

```
phoneide/
├── server.py           # Flask 后端服务（核心）
├── requirements.txt    # Python 依赖
├── install.sh          # 安装脚本
├── start.sh            # 启动脚本
├── build_apk.sh        # Android APK 构建脚本
└── static/
    ├── index.html      # 主页面
    ├── css/
    │   └── style.css   # 样式表
    └── js/
        ├── app.js      # 主程序入口、手势控制、面板切换
        ├── editor.js   # CodeMirror 编辑器管理
        ├── files.js    # 文件树浏览与管理
        ├── git.js      # Git 操作界面
        ├── search.js   # 全局搜索与替换
        ├── terminal.js # 代码运行与输出
        └── chat.js     # LLM 对话与 Agent 工具执行
└── android/            # Android APK 项目
    ├── app/src/main/
    │   ├── java/com/phoneide/
    │   │   ├── MainActivity.kt       # WebView IDE 界面
    │   │   ├── TerminalActivity.kt    # 内置终端
    │   │   ├── SetupActivity.kt      # 首次设置向导
    │   │   ├── ServerService.kt      # Flask 后台服务
    │   │   └── PhoneIDEApp.kt        # 应用全局配置
    │   └── res/                       # 布局、图标、主题
    ├── .github/workflows/build.yml   # CI/CD 自动构建
    └── app/build.gradle.kts          # Gradle 构建配置
```

## 构建安卓 APK

如果你想自行构建 APK，需要 JDK 17+ 和 Android SDK：

```bash
# 方式一：使用构建脚本
./build_apk.sh debug
./build_apk.sh release

# 方式二：手动构建
cd android
./gradlew assembleDebug
./gradlew assembleRelease
```

也可以使用 GitHub Actions 自动构建：推送代码到 main 分支后会自动构建，生成的 APK 会出现在 [Releases](https://github.com/ctz168/phoneide/releases) 页面。

## 技术栈

- **后端**: Python Flask + Flask-CORS
- **前端**: 原生 HTML/CSS/JavaScript
- **编辑器**: CodeMirror 5
- **实时通信**: Server-Sent Events (SSE)
- **AI 集成**: OpenAI 兼容 API 协议
- **Android**: Kotlin + WebView + Material Design
- **CI/CD**: GitHub Actions

## 许可证

MIT License
