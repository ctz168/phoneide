# PhoneIDE (Android APK)

将 [PhoneIDE Web Server](https://github.com/ctz168/ide) 封装为 Android APK 的工程。

内嵌 proot Ubuntu 环境，自带 Python + Flask，无需 Termux 即可在手机上运行完整的 Web IDE。

## 下载安装

### 最新版本

[![Download APK](https://img.shields.io/github/v/release/ctz168/phoneide?label=Latest%20Release&style=for-the-badge)](https://github.com/ctz168/phoneide/releases/latest)

| 文件 | 说明 |
|------|------|
| [PhoneIDE-release.apk](https://github.com/ctz168/phoneide/releases/latest/download/PhoneIDE-release.apk) | 正式版（推荐） |
| [PhoneIDE-debug.apk](https://github.com/ctz168/phoneide/releases/latest/download/PhoneIDE-debug.apk) | 调试版 |

### 历史版本

查看 [Releases](https://github.com/ctz168/phoneide/releases) 页面获取所有历史版本。

### 安装步骤

1. 下载 APK 文件
2. 启用 **设置 → 安全 → 允许安装未知来源应用**
3. 安装 APK
4. 首次启动会自动下载 Ubuntu 24.04 rootfs (~300MB) 并安装 Python + Flask

> 需要约 500MB 可用空间。

## 功能特性

- **代码编辑器** - 基于 CodeMirror，支持语法高亮、代码折叠
- **终端模拟器** - 内置终端，支持 Ctrl/Alt/Shift 等特殊按键
- **Git 操作** - 克隆、拉取、推送、提交
- **LLM AI 助手** - 支持 OpenAI 兼容 API
- **文件管理** - 创建、重命名、删除文件/文件夹
- **Python/Shell 执行** - 实时流式输出

## 仓库说明

本仓库（`ctz168/phoneide`）只负责 **Android APK 的封装和构建**。IDE 的网页服务代码（Flask 后端 + 前端）维护在 [ctz168/ide](https://github.com/ctz168/ide)。

构建 APK 时会自动从 `ctz168/ide` 拉取最新代码打包进去。

## 项目结构

```
phoneide/
├── android/                          # Android 工程目录
│   ├── app/
│   │   ├── build.gradle              # Gradle 配置
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/ctz168/phoneide/
│   │       │   ├── MainActivity.kt       # WebView IDE 界面
│   │       │   ├── TerminalActivity.kt   # 内置终端
│   │       │   ├── SetupActivity.kt      # 首次设置向导
│   │       │   ├── ServerService.kt      # Flask 后台服务
│   │       │   ├── BootstrapManager.kt   # Ubuntu rootfs 管理
│   │       │   ├── ProcessManager.kt     # proot 进程管理
│   │       │   └── PhoneIDEApp.kt        # Application 全局配置
│   │       ├── jniLibs/                  # proot 原生库 (arm64/armeabi/x86_64)
│   │       └── res/                      # 布局、图标、主题
│   └── build.gradle
├── scripts/
│   └── fetch-proot-binaries.sh      # 下载 proot .deb → jniLibs
├── build_apk.sh                     # 本地构建脚本
└── .github/workflows/build-apk.yml  # CI 自动构建
```

## 构建 APK

### 方式一：本地构建

需要 JDK 17+ 和 Android SDK：

```bash
git clone https://github.com/ctz168/phoneide.git
cd phoneide

# 构建（会自动从 ctz168/ide 克隆代码）
./build_apk.sh

# 或指定 IDE 分支
IDE_BRANCH=dev ./build_apk.sh
```

### 方式二：GitHub Actions

推送代码到 `main` 分支自动触发构建，APK 发布到 [Releases](https://github.com/ctz168/phoneide/releases)。

也可以手动触发并指定 IDE 分支：

```yaml
# workflow_dispatch 参数
ide_ref: 'main'    # ctz168/ide 的分支/tag/SHA
build_type: 'both' # debug / release / both
```

## 技术栈

- **Android**: Kotlin + WebView + Material Design
- **运行时**: proot Ubuntu 24.04 + Python + Flask
- **IDE 代码**: [ctz168/ide](https://github.com/ctz168/ide) — Flask + CodeMirror 5
- **CI/CD**: GitHub Actions

## 相关仓库

| 仓库 | 说明 |
|------|------|
| [ctz168/ide](https://github.com/ctz168/ide) | IDE 网页服务（Flask 后端 + 前端） |
| [ctz168/phoneide](https://github.com/ctz168/phoneide) | Android APK 封装（本仓库） |

## 许可证

MIT License
