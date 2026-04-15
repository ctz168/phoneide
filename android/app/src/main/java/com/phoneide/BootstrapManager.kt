package com.phoneide

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class BootstrapManager(
    private val context: Context,
    private val processManager: ProcessManager
) {
    companion object {
        private const val TAG = "BootstrapManager"
        private const val RELEASES_BASE = "https://github.com/termux/proot-distro/releases/download"

        fun getRootfsUrls(): List<String> {
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val archSuffix = if (primaryAbi.startsWith("arm64") || primaryAbi == "aarch64") "aarch64"
                             else if (primaryAbi.startsWith("armeabi") || primaryAbi.startsWith("arm")) "arm"
                             else "aarch64"

            val urls = mutableListOf<String>()
            // Ubuntu 24.04 LTS (noble) - most stable
            urls.add("$RELEASES_BASE/v4.18.0/ubuntu-noble-$archSuffix-pd-v4.18.0.tar.xz")
            // Ubuntu 25.04 (plucky)
            urls.add("$RELEASES_BASE/v4.29.0/ubuntu-plucky-$archSuffix-pd-v4.29.0.tar.xz")
            // Ubuntu 25.10 (questing)
            urls.add("$RELEASES_BASE/v4.30.1/ubuntu-questing-$archSuffix-pd-v4.30.1.tar.xz")
            // Fallback: smaller noble
            urls.add("$RELEASES_BASE/v4.11.0/ubuntu-noble-$archSuffix-pd-v4.11.0.tar.xz")

            // Mirror proxies
            for (proxy in listOf("https://ghfast.top", "https://gh-proxy.com")) {
                urls.add("$proxy/$RELEASES_BASE/v4.18.0/ubuntu-noble-$archSuffix-pd-v4.18.0.tar.xz")
                urls.add("$proxy/$RELEASES_BASE/v4.29.0/ubuntu-plucky-$archSuffix-pd-v4.29.0.tar.xz")
            }
            return urls
        }
    }

    private val isRunning = AtomicBoolean(false)
    private var progressListener: ((Int, String) -> Unit)? = null

    fun setProgressListener(listener: ((Int, String) -> Unit)?) {
        this.progressListener = listener
    }

    private fun reportProgress(percent: Int, message: String) {
        Log.d(TAG, "[$percent%] $message")
        progressListener?.invoke(percent, message)
    }

    fun isRootfsReady(): Boolean {
        val rootfsDir = processManager.getRootfsDir()
        return File("$rootfsDir/bin/bash").exists() ||
               File("$rootfsDir/usr/bin/bash").exists()
    }

    fun bootstrap(): Boolean {
        if (isRunning.getAndSet(true)) return false
        try {
            processManager.initialize()
            val rootfsDir = processManager.getRootfsDir()
            reportProgress(0, "Starting bootstrap...")

            // Step 1: Download
            if (!isRootfsReady()) {
                reportProgress(5, "Downloading Ubuntu rootfs...")
                if (!downloadRootfs(rootfsDir)) {
                    reportProgress(-1, "Failed to download rootfs")
                    return false
                }
                reportProgress(40, "Rootfs downloaded")
            } else {
                reportProgress(40, "Rootfs exists")
            }

            // Step 2: Extract
            val tarFile = File("$rootfsDir/rootfs.tar.xz")
            if (tarFile.exists()) {
                reportProgress(45, "Extracting rootfs...")
                extractRootfs(tarFile, rootfsDir)
                tarFile.delete()
            }
            reportProgress(70, "Rootfs extracted")

            // Step 3: Configure
            reportProgress(75, "Configuring proot compatibility...")
            configureRootfs()
            reportProgress(80, "Configured")

            // Step 4: Install deps (non-fatal)
            reportProgress(85, "Installing Python & Flask...")
            installDependencies()
            reportProgress(95, "Deps installed")

            // Step 5: IDE files
            reportProgress(97, "Setting up IDE files...")
            setupIDEFiles()

            reportProgress(100, "Bootstrap complete!")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed", e)
            reportProgress(-1, "Failed: ${e.message}")
            return false
        } finally {
            isRunning.set(false)
        }
    }

    private fun downloadRootfs(rootfsDir: String): Boolean {
        val rootfsFile = File("$rootfsDir/rootfs.tar.xz")
        File(rootfsDir).mkdirs()
        val urls = getRootfsUrls()

        for ((index, url) in urls.withIndex()) {
            if (rootfsFile.exists()) rootfsFile.delete()
            try {
                val label = if (index < 4) "Ubuntu ${listOf("24.04", "25.04", "25.10", "24.04")[index]}"
                            else "Mirror ${index - 3}"
                reportProgress(5, "Trying $label (${index + 1}/${urls.size})...")
                Log.d(TAG, "Downloading: $url")

                downloadFile(url, rootfsFile) { bytesRead, totalBytes ->
                    val pct = if (totalBytes > 0) (10 + (bytesRead.toFloat() / totalBytes * 25)).toInt() else 10
                    reportProgress(pct.coerceAtMost(38), "Downloading... ${bytesRead / 1024 / 1024}MB")
                }

                if (rootfsFile.exists() && rootfsFile.length() > 10_000_000) {
                    Log.d(TAG, "Downloaded ${rootfsFile.length() / 1024 / 1024}MB")
                    return true
                } else {
                    if (rootfsFile.exists()) rootfsFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download $index failed: ${e.message}")
            }
        }
        Log.e(TAG, "All URLs failed")
        return false
    }

    private fun downloadFile(urlString: String, targetFile: File, onProgress: ((Long, Long) -> Unit)? = null) {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "PhoneIDE/2.0")
        conn.connect()

        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")

        val total = conn.contentLength.toLong()
        var read: Long = 0
        conn.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                val buf = ByteArray(16384)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    read += n
                    onProgress?.invoke(read, total)
                }
            }
        }
    }

    /**
     * Extract rootfs tar.xz - based on stableclaw_android's approach.
     * Two-phase: files first, then symlinks. Uses Os.symlink.
     * Skips dev/ directory. Buffered input for speed.
     */
    private fun extractRootfs(tarFile: File, rootfsDir: String) {
        val deferredSymlinks = mutableListOf<Pair<String, String>>()
        var entryCount = 0

        FileInputStream(tarFile).use { fis ->
            BufferedInputStream(fis, 256 * 1024).use { bis ->
                XZCompressorInputStream(bis).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        var entry: TarArchiveEntry? = tarIn.nextTarEntry
                        while (entry != null) {
                            entryCount++
                            if (entryCount % 500 == 0) {
                                reportProgress(45 + (entryCount / 200), "Extracting...")
                            }
                            val name = entry.name.removePrefix("./").removePrefix("/")
                            if (name.isEmpty() || name.startsWith("dev/")) {
                                entry = tarIn.nextTarEntry
                                continue
                            }

                            val outFile = File(rootfsDir, name)
                            // Security check
                            try {
                                if (!outFile.canonicalPath.startsWith(File(rootfsDir).canonicalPath)) {
                                    entry = tarIn.nextTarEntry; continue
                                }
                            } catch (_: Exception) { entry = tarIn.nextTarEntry; continue }

                            when {
                                entry.isDirectory -> outFile.mkdirs()
                                entry.isSymbolicLink -> deferredSymlinks.add(entry.linkName to outFile.absolutePath)
                                entry.isLink -> {
                                    val target = entry.linkName.removePrefix("./").removePrefix("/")
                                    val targetFile = File(rootfsDir, target)
                                    outFile.parentFile?.mkdirs()
                                    try {
                                        if (targetFile.exists()) {
                                            targetFile.copyTo(outFile, overwrite = true)
                                            if (targetFile.canExecute()) outFile.setExecutable(true, false)
                                        }
                                    } catch (_: Exception) {}
                                }
                                else -> {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        val buf = ByteArray(65536)
                                        var len: Int
                                        while (tarIn.read(buf).also { len = it } != -1) fos.write(buf, 0, len)
                                    }
                                    outFile.setReadable(true, false)
                                    outFile.setWritable(true, false)
                                    val mode = entry.mode
                                    if (mode and 0b001_001_001 != 0 || mode == 0 ||
                                        name.contains("/bin/") || name.contains("/sbin/") ||
                                        name.endsWith(".sh") || name.contains("/lib/apt/methods/")) {
                                        outFile.setExecutable(true, false)
                                    }
                                }
                            }
                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            }
        }

        // Phase 2: symlinks
        reportProgress(67, "Creating symlinks...")
        for ((target, path) in deferredSymlinks) {
            try {
                val file = File(path)
                if (file.exists()) {
                    if (file.isDirectory) {
                        // If target dir exists, merge contents
                        val linkTarget = if (target.startsWith("/")) target.removePrefix("/")
                        else {
                            val parent = file.parentFile?.absolutePath ?: rootfsDir
                            File(parent, target).relativeTo(File(rootfsDir)).path
                        }
                        val realTargetDir = File(rootfsDir, linkTarget)
                        if (realTargetDir.exists() && realTargetDir.isDirectory) {
                            file.listFiles()?.forEach { child ->
                                val dest = File(realTargetDir, child.name)
                                if (!dest.exists()) child.renameTo(dest)
                            }
                        }
                        deleteRecursively(file)
                    } else {
                        file.delete()
                    }
                }
                file.parentFile?.mkdirs()
                Os.symlink(target, path)
            } catch (e: Exception) {
                Log.w(TAG, "Symlink failed $path -> $target: ${e.message}")
            }
        }

        // Verify
        if (!File("$rootfsDir/bin/bash").exists() && !File("$rootfsDir/usr/bin/bash").exists()) {
            throw RuntimeException("Extraction failed: bash not found (processed $entryCount entries)")
        }
    }

    /**
     * Configure rootfs for proot compatibility.
     * Based on stableclaw_android's configureRootfs() - comprehensive.
     */
    private fun configureRootfs() {
        val rootfsDir = processManager.getRootfsDir()

        // 1. apt sandboxing fix
        File("$rootfsDir/etc/apt/apt.conf.d").mkdirs()
        File("$rootfsDir/etc/apt/apt.conf.d/01-phoneide-proot").writeText(
            "APT::Sandbox::User \"root\";\n" +
            "Dpkg::Use-Pty \"0\";\n" +
            "Dpkg::Options { \"--force-confnew\"; \"--force-overwrite\"; };\n"
        )

        // 2. dpkg config
        File("$rootfsDir/etc/dpkg/dpkg.cfg.d").mkdirs()
        File("$rootfsDir/etc/dpkg/dpkg.cfg.d/01-phoneide-proot").writeText(
            "force-unsafe-io\nno-debsig\nforce-overwrite\nforce-depends\nforce-statoverride-add\n"
        )

        // 3. Clear stale stat-overrides
        val statOverride = File("$rootfsDir/var/lib/dpkg/statoverride")
        if (statOverride.exists()) statOverride.writeText("")

        // 4. Pre-create all directories that tools need at runtime
        // (mkdir syscall broken in proot on Android 10+)
        listOf(
            "$rootfsDir/etc/ssl/certs",
            "$rootfsDir/usr/share/keyrings",
            "$rootfsDir/etc/apt/sources.list.d",
            "$rootfsDir/var/lib/dpkg/updates",
            "$rootfsDir/var/lib/dpkg/triggers",
            "$rootfsDir/var/cache/apt/archives/partial",
            "$rootfsDir/tmp",
            "$rootfsDir/var/tmp",
            "$rootfsDir/run",
            "$rootfsDir/run/lock",
            "$rootfsDir/dev/shm",
            "$rootfsDir/root",
        ).forEach { File(it).mkdirs() }

        // 5. /etc/machine-id (dpkg triggers need it)
        val machineId = File("$rootfsDir/etc/machine-id")
        if (!machineId.exists()) {
            machineId.parentFile?.mkdirs()
            machineId.writeText("10000000000000000000000000000000\n")
        }

        // 6. policy-rc.d - prevent services from auto-starting
        val policyRc = File("$rootfsDir/usr/sbin/policy-rc.d")
        policyRc.parentFile?.mkdirs()
        policyRc.writeText("#!/bin/sh\nexit 101\n")
        policyRc.setExecutable(true, false)

        // 7. Register Android users
        registerAndroidUsers()

        // 8. /etc/hosts
        val hosts = File("$rootfsDir/etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("localhost")) {
            hosts.writeText(
                "127.0.0.1   localhost.localdomain localhost\n" +
                "::1         localhost.localdomain localhost ip6-localhost\n"
            )
        }

        // 9. /etc/hostname
        File("$rootfsDir/etc/hostname").writeText("phoneide\n")

        // 10. /tmp permissions
        File("$rootfsDir/tmp").let {
            it.mkdirs()
            it.setReadable(true, false)
            it.setWritable(true, false)
            it.setExecutable(true, false)
        }

        // 11. Fix ALL bin/sbin permissions (CRITICAL - dpkg error 100 = "Could not exec dpkg")
        fixBinPermissions()
    }

    private fun registerAndroidUsers() {
        val rootfsDir = processManager.getRootfsDir()
        val uid = android.os.Process.myUid()
        val gid = uid

        for (name in listOf("passwd", "shadow", "group", "gshadow")) {
            File("$rootfsDir/etc/$name")?.let { if (it.exists()) it.setWritable(true, false) }
        }

        File("$rootfsDir/etc/passwd")?.let { f ->
            if (f.exists() && !f.readText().contains("aid_android")) {
                f.appendText("aid_android:x:$uid:$gid:Android:/:/sbin/nologin\n")
            }
        }
        File("$rootfsDir/etc/shadow")?.let { f ->
            if (f.exists() && !f.readText().contains("aid_android")) {
                f.appendText("aid_android:*:18446:0:99999:7:::\n")
            }
        }
        File("$rootfsDir/etc/group")?.let { f ->
            if (f.exists()) {
                val content = f.readText()
                for ((name, id) in mapOf("aid_inet" to 3003, "aid_net_raw" to 3004, "aid_sdcard_rw" to 1015, "aid_android" to gid)) {
                    if (!content.contains(name)) f.appendText("$name:x:$id:root,aid_android\n")
                }
            }
        }
        File("$rootfsDir/etc/gshadow")?.let { f ->
            if (f.exists()) {
                val content = f.readText()
                for (name in listOf("aid_inet", "aid_net_raw", "aid_sdcard_rw", "aid_android")) {
                    if (!content.contains(name)) f.appendText("$name:*::root,aid_android\n")
                }
            }
        }
    }

    /**
     * Fix executable permissions on all files in bin/sbin/lib directories.
     * Java extraction doesn't preserve all permission bits - this is CRITICAL.
     * dpkg error 100 = "Could not exec dpkg" = permission issue.
     */
    private fun fixBinPermissions() {
        val rootfsDir = processManager.getRootfsDir()
        val execDirs = listOf(
            "$rootfsDir/usr/bin", "$rootfsDir/usr/sbin",
            "$rootfsDir/usr/local/bin", "$rootfsDir/usr/local/sbin",
            "$rootfsDir/usr/lib/apt/methods", "$rootfsDir/usr/lib/dpkg",
            "$rootfsDir/usr/libexec",
            "$rootfsDir/var/lib/dpkg/info",
            "$rootfsDir/bin", "$rootfsDir/sbin",
        )
        for (dirPath in execDirs) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) fixExecRecursive(dir)
        }
        // Fix shared libs
        for (libPath in listOf("$rootfsDir/usr/lib", "$rootfsDir/lib")) {
            val dir = File(libPath)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isDirectory) fixSharedLibsRecursive(f)
                    else if (f.name.endsWith(".so") || f.name.contains(".so.")) {
                        f.setReadable(true, false); f.setExecutable(true, false)
                    }
                }
            }
        }
    }

    private fun fixExecRecursive(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) fixExecRecursive(f)
            else if (f.isFile) { f.setReadable(true, false); f.setExecutable(true, false) }
        }
    }

    private fun fixSharedLibsRecursive(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) fixSharedLibsRecursive(f)
            else if (f.name.endsWith(".so") || f.name.contains(".so.")) {
                f.setReadable(true, false); f.setExecutable(true, false)
            }
        }
    }

    /**
     * Install Python and Flask (non-fatal).
     * Split into steps for better error diagnostics.
     */
    private fun installDependencies() {
        try {
            // Quick proot test
            val test = processManager.runInProot("echo OK && cat /etc/os-release | head -1", 30_000)
            if (!test.success) {
                Log.e(TAG, "Proot test failed: ${test.stdout}")
                reportProgress(-1, "Proot test failed: ${test.stdout.take(200)}")
            } else {
                Log.d(TAG, "Proot test: ${test.stdout.trim()}")
            }

            // apt-get update
            val upd = processManager.runInProot("apt-get update 2>&1", 120_000)
            Log.d(TAG, "apt update: ${upd.exitCode}")
            if (!upd.success) Log.w(TAG, "apt update stderr: ${upd.stdout.take(300)}")

            // apt-get install python3
            val inst = processManager.runInProot(
                "apt-get install -y --no-install-recommends python3 python3-pip 2>&1",
                600_000
            )
            Log.d(TAG, "apt install: ${inst.exitCode}")

            // pip install flask
            val pip = processManager.runInProot(
                "pip3 install --break-system-packages flask flask-cors 2>&1 || pip3 install flask flask-cors 2>&1",
                300_000
            )
            Log.d(TAG, "pip: ${pip.exitCode}")
        } catch (e: Exception) {
            Log.e(TAG, "installDependencies non-fatal: ${e.message}")
        }
    }

    private fun setupIDEFiles() {
        val hostIdeDir = processManager.getIdeDir()
        val rootIdeDir = "${processManager.getRootfsDir()}/root/phoneide"

        val hostDir = File(hostIdeDir)
        if (!hostDir.exists() || hostDir.listFiles()?.isEmpty() != false) {
            hostDir.mkdirs()
            extractAssetsDir("ide", hostDir)
        }

        File(rootIdeDir).mkdirs()
        copyDirectory(hostDir, File(rootIdeDir))

        val ok = File("$rootIdeDir/server.py").exists() && File("$rootIdeDir/static/index.html").exists()
        Log.d(TAG, "IDE files: ${if (ok) "OK" else "INCOMPLETE"}")
    }

    private fun extractAssetsDir(assetPath: String, targetDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        for (entry in entries) {
            val fullPath = "$assetPath/$entry"
            val target = File(targetDir, entry)
            val subs = try { context.assets.list(fullPath) } catch (_: Exception) { null }
            if (subs != null && subs.isNotEmpty()) {
                target.mkdirs()
                extractAssetsDir(fullPath, target)
            } else {
                target.parentFile?.mkdirs()
                try {
                    context.assets.open(fullPath).use { input ->
                        target.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Asset extract failed: $fullPath - ${e.message}")
                }
            }
        }
    }

    private fun copyDirectory(source: File, target: File) {
        if (!source.exists()) return
        target.mkdirs()
        source.listFiles()?.forEach { f ->
            val dest = File(target, f.name)
            if (f.isDirectory) copyDirectory(f, dest)
            else f.copyTo(dest, overwrite = true)
        }
    }

    private fun deleteRecursively(file: File) {
        try {
            if (!file.canonicalPath.startsWith(processManager.getRootfsDir())) return
        } catch (_: Exception) { return }
        try {
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) { file.delete(); return }
        } catch (_: Exception) {}
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}
