---
Task ID: 1
Agent: Main Agent
Task: 将 PhoneIDE 打包成含 Termux + Ubuntu 的 Android APK，自带 WebView 和 Terminal

Work Log:
- 分析环境：x86_64 Linux，无 Android SDK，仅有 JRE headless
- 下载安装 Android SDK command-line tools (platforms;android-34, build-tools;34.0.0)
- 下载安装 JDK 21 完整版（需 jlink 支持）
- 创建完整 Android 项目结构（Kotlin + Gradle 8.5）
- 实现 5 个核心 Kotlin 文件：
  - PhoneIDEApp.kt: 全局配置和应用管理
  - MainActivity.kt: WebView 加载 IDE 界面，自动重连
  - ServerService.kt: Flask 服务器前台服务
  - SetupActivity.kt: 首次设置向导（Termux 检测 + proot Ubuntu 安装）
  - TerminalActivity.kt: 内置终端（支持 Termux/Ubuntu/Python 三种模式）
- 创建 Material Design 暗色主题布局
- AI 生成应用图标并转换为 5 种分辨率
- 修复编译错误（BuildConfig、Font 引用、ProcessBuilder 参数）
- 成功编译 debug APK (6.4MB)
- 添加 /api/health 端点到 server.py
- 创建 build_apk.sh 构建脚本
- 创建 GitHub Actions CI/CD workflow
- 更新 README.md 添加 APK 安装说明
- 推送所有代码到 GitHub

Stage Summary:
- 生成 APK 文件: /home/z/my-project/download/PhoneIDE-v1.0.0-debug.apk (6.4MB)
- Android 项目位于 phoneide/android/ 目录
- 34 个文件，2244 行新增代码
- GitHub 仓库已更新：https://github.com/ctz168/phoneide
- 注意：APK 需要设备上有 Termux（F-Droid 版本）才能使用 Linux 环境
