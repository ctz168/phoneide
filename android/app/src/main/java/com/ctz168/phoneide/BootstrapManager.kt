package com.ctz168.phoneide

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * BootstrapManager - Manages Ubuntu rootfs lifecycle for proot.
 * Downloads rootfs tarball from Ubuntu cdimage, extracts with pure Java,
 * and configures proot compatibility patches.
 * Based on stableclaw_android's BootstrapManager.
 */
class BootstrapManager(
    private val context: Context,
    private val filesDir: String,
    private val nativeLibDir: String
) {
    companion object {
        private const val TAG = "BootstrapManager"

        // Ubuntu 24.04 base rootfs from cdimage (NOT termux/proot-distro)
        private const val UBUNTU_ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
        private const val UBUNTU_ROOTFS_ALT_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz"
    }

    private val rootfsDir get() = "$filesDir/rootfs/ubuntu"
    private val tmpDir get() = "$filesDir/tmp"
    private val configDir get() = "$filesDir/config"
    private val libDir get() = "$filesDir/lib"
    private val homeDir get() = "$filesDir/home"
    private val hostIdeDir get() = "$filesDir/phoneide"

    private val isRunning = AtomicBoolean(false)
    private var progressListener: ((Int, String) -> Unit)? = null

    // Legacy constructor
    constructor(context: Context, processManager: ProcessManager) : this(
        context,
        processManager.filesDir,
        processManager.nativeLibDir
    )

    fun setProgressListener(listener: ((Int, String) -> Unit)?) {
        this.progressListener = listener
    }

    private fun reportProgress(percent: Int, message: String) {
        Log.d(TAG, "[$percent%] $message")
        progressListener?.invoke(percent, message)
    }

    // ================================================================
    // Public API
    // ================================================================

    fun setupDirectories() {
        listOf(rootfsDir, tmpDir, homeDir, configDir, libDir, hostIdeDir).forEach {
            File(it).mkdirs()
        }
        setupLibtalloc()
        setupFakeSysdata()
        ensureResolvConf()
    }

    fun isRootfsReady(): Boolean {
        return File("$rootfsDir/bin/bash").exists() && File("$rootfsDir/etc/apt").isDirectory
    }

    fun isBootstrapComplete(): Boolean {
        return File("$rootfsDir/bin/bash").exists() &&
               File("$rootfsDir/usr/bin/python3").exists() &&
               File("$rootfsDir/root/phoneide/server.py").exists()
    }

    fun readRootfsFile(path: String): String? {
        val file = File("$rootfsDir/$path")
        return if (file.exists()) file.readText() else null
    }

    fun writeRootfsFile(path: String, content: String) {
        val file = File("$rootfsDir/$path")
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /**
     * Full bootstrap: download -> extract -> configure -> install deps.
     */
    fun bootstrap(): Boolean {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Bootstrap already in progress")
            return false
        }

        try {
            reportProgress(0, "Starting bootstrap...")
            setupDirectories()

            // Step 1: Download rootfs
            if (!isRootfsReady()) {
                reportProgress(5, "Downloading Ubuntu 24.04 rootfs...")
                val tarFile = File("$tmpDir/ubuntu-rootfs.tar.gz")
                if (!downloadRootfs(tarFile)) {
                    reportProgress(-1, "Failed to download rootfs")
                    return false
                }
                reportProgress(40, "Rootfs downloaded (${tarFile.length() / 1024 / 1024}MB)")

                // Step 2: Extract rootfs
                reportProgress(45, "Extracting rootfs...")
                extractRootfs(tarFile.absolutePath)
                tarFile.delete()
                reportProgress(70, "Rootfs extracted")
            } else {
                reportProgress(70, "Rootfs already exists")
            }

            // Step 3: Configure (called inside extractRootfs, but call again for safety)
            reportProgress(75, "Configuring proot compatibility...")
            configureRootfs()
            reportProgress(80, "Rootfs configured")

            // Step 4: Install dependencies
            reportProgress(85, "Installing Python and Flask...")
            installDependencies()
            reportProgress(95, "Dependencies installed")

            // Step 5: Copy IDE files
            reportProgress(97, "Setting up IDE files...")
            setupIDEFiles()

            reportProgress(100, "Bootstrap complete!")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed", e)
            reportProgress(-1, "Bootstrap failed: ${e.message}")
            return false
        } finally {
            isRunning.set(false)
        }
    }

    // ================================================================
    // Download
    // ================================================================

    private fun downloadRootfs(targetFile: File): Boolean {
        File(tmpDir).mkdirs()
        val urls = listOf(UBUNTU_ROOTFS_URL, UBUNTU_ROOTFS_ALT_URL)

        for ((index, url) in urls.withIndex()) {
            try {
                reportProgress(5 + index * 15, "Trying mirror ${index + 1}...")
                downloadFile(url, targetFile) { bytesRead, totalBytes ->
                    val percent = if (totalBytes > 0) {
                        (10 + (bytesRead.toFloat() / totalBytes * 25)).toInt()
                    } else { 10 }
                    reportProgress(percent.coerceAtMost(38),
                        "Downloading... ${(bytesRead / 1024 / 1024)}MB")
                }
                if (targetFile.exists() && targetFile.length() > 1_000_000) return true
            } catch (e: Exception) {
                Log.w(TAG, "Download from mirror $index failed: ${e.message}")
            }
        }
        return false
    }

    private fun downloadFile(
        urlString: String,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.connect()

        val totalBytes = connection.contentLength.toLong()
        var bytesRead: Long = 0

        connection.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    onProgress?.invoke(bytesRead, totalBytes)
                }
            }
        }
        connection.disconnect()
    }

    // ================================================================
    // Rootfs Extraction (pure Java, Apache Commons Compress)
    // Two-phase: Phase 1 dirs/files/hardlinks, Phase 2 symlinks.
    // ================================================================

    fun extractRootfs(tarPath: String) {
        val rootfs = File(rootfsDir)
        if (rootfs.exists()) deleteRecursively(rootfs)
        rootfs.mkdirs()

        val deferredSymlinks = mutableListOf<Pair<String, String>>()
        var entryCount = 0
        var fileCount = 0
        var symlinkCount = 0
        var extractionError: Exception? = null

        try {
            FileInputStream(tarPath).use { fis ->
                BufferedInputStream(fis, 256 * 1024).use { bis ->
                    GZIPInputStream(bis).use { gis ->
                        TarArchiveInputStream(gis).use { tis ->
                            var entry: TarArchiveEntry? = tis.nextEntry
                            while (entry != null) {
                                entryCount++
                                val name = entry.name
                                    .removePrefix("./")
                                    .removePrefix("/")

                                if (name.isEmpty() || name.startsWith("dev/") || name == "dev") {
                                    entry = tis.nextEntry
                                    continue
                                }

                                val outFile = File(rootfsDir, name)

                                when {
                                    entry.isDirectory -> {
                                        outFile.mkdirs()
                                    }
                                    entry.isSymbolicLink -> {
                                        deferredSymlinks.add(
                                            Pair(entry.linkName, outFile.absolutePath)
                                        )
                                        symlinkCount++
                                    }
                                    entry.isLink -> {
                                        val target = entry.linkName
                                            .removePrefix("./")
                                            .removePrefix("/")
                                        val targetFile = File(rootfsDir, target)
                                        outFile.parentFile?.mkdirs()
                                        try {
                                            if (targetFile.exists()) {
                                                targetFile.copyTo(outFile, overwrite = true)
                                                if (targetFile.canExecute()) {
                                                    outFile.setExecutable(true, false)
                                                }
                                                fileCount++
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    else -> {
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { fos ->
                                            val buf = ByteArray(65536)
                                            var len: Int
                                            while (tis.read(buf).also { len = it } != -1) {
                                                fos.write(buf, 0, len)
                                            }
                                        }
                                        outFile.setReadable(true, false)
                                        outFile.setWritable(true, false)
                                        val mode = entry.mode
                                        if (mode == 0 || mode and 0b001_001_001 != 0) {
                                            val path = name.lowercase()
                                            if (mode and 0b001_001_001 != 0 ||
                                                path.contains("/bin/") ||
                                                path.contains("/sbin/") ||
                                                path.endsWith(".sh") ||
                                                path.contains("/lib/apt/methods/")) {
                                                outFile.setExecutable(true, false)
                                            }
                                        }
                                        fileCount++
                                    }
                                }

                                entry = tis.nextEntry
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            extractionError = e
        }

        if (entryCount == 0) {
            throw RuntimeException(
                "Extraction failed: tarball empty or corrupt. Error: ${extractionError?.message}"
            )
        }

        // Phase 2: Create symlinks
        var symlinkErrors = 0
        for ((target, path) in deferredSymlinks) {
            try {
                val file = File(path)
                if (file.exists()) {
                    if (file.isDirectory) {
                        val linkTarget = if (target.startsWith("/")) {
                            target.removePrefix("/")
                        } else {
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
                symlinkErrors++
            }
        }

        // Verify extraction
        if (!File("$rootfsDir/bin/bash").exists() &&
            !File("$rootfsDir/usr/bin/bash").exists()) {
            throw RuntimeException(
                "Extraction failed: bash not found in rootfs. " +
                "Entries: $entryCount, Files: $fileCount, Symlinks: $symlinkCount"
            )
        }

        // Post-extraction configuration
        configureRootfs()
    }

    // ================================================================
    // Rootfs Configuration
    // ================================================================

    fun configureRootfs() {
        // 1. Disable apt sandboxing
        val aptConfDir = File("$rootfsDir/etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "01-phoneide-proot").writeText(
            "APT::Sandbox::User \"root\";\n" +
            "Dpkg::Use-Pty \"0\";\n" +
            "Dpkg::Options { \"--force-confnew\"; \"--force-overwrite\"; };\n"
        )

        // 2. Configure dpkg for proot compatibility
        val dpkgConfDir = File("$rootfsDir/etc/dpkg/dpkg.cfg.d")
        dpkgConfDir.mkdirs()
        File(dpkgConfDir, "01-phoneide-proot").writeText(
            "force-unsafe-io\n" +
            "no-debsig\n" +
            "force-overwrite\n" +
            "force-depends\n" +
            "force-statoverride-add\n"
        )

        // 3. Clear stale stat-overrides
        val statOverride = File("$rootfsDir/var/lib/dpkg/statoverride")
        if (statOverride.exists()) statOverride.writeText("")

        // 4. Pre-create ALL needed directories
        listOf(
            "$rootfsDir/etc/ssl/certs",
            "$rootfsDir/usr/share/keyrings",
            "$rootfsDir/etc/apt/sources.list.d",
            "$rootfsDir/var/lib/dpkg/updates",
            "$rootfsDir/var/lib/dpkg/triggers",
            "$rootfsDir/tmp",
            "$rootfsDir/var/tmp",
            "$rootfsDir/run",
            "$rootfsDir/run/lock",
            "$rootfsDir/dev/shm",
            "$rootfsDir/root/.cache",
            "$rootfsDir/root/.local/share",
            "$rootfsDir/root/phoneide",
        ).forEach { File(it).mkdirs() }

        // 5. /etc/machine-id
        val machineId = File("$rootfsDir/etc/machine-id")
        if (!machineId.exists()) {
            machineId.parentFile?.mkdirs()
            machineId.writeText("10000000000000000000000000000000\n")
        }

        // 6. policy-rc.d prevents services from auto-starting
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
                "::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n"
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

        // 11. Fix executable permissions
        fixBinPermissions()
    }

    private fun registerAndroidUsers() {
        val uid = android.os.Process.myUid()
        val gid = uid

        for (name in listOf("passwd", "shadow", "group", "gshadow")) {
            val f = File("$rootfsDir/etc/$name")
            if (f.exists()) f.setWritable(true, false)
        }

        val passwd = File("$rootfsDir/etc/passwd")
        if (passwd.exists()) {
            val content = passwd.readText()
            if (!content.contains("aid_android")) {
                passwd.appendText("aid_android:x:$uid:$gid:Android:/:/sbin/nologin\n")
            }
        }

        val shadow = File("$rootfsDir/etc/shadow")
        if (shadow.exists()) {
            val content = shadow.readText()
            if (!content.contains("aid_android")) {
                shadow.appendText("aid_android:*:18446:0:99999:7:::\n")
            }
        }

        val group = File("$rootfsDir/etc/group")
        if (group.exists()) {
            val content = group.readText()
            val groups = mapOf(
                "aid_inet" to 3003,
                "aid_net_raw" to 3004,
                "aid_sdcard_rw" to 1015,
                "aid_android" to gid,
            )
            for ((name, id) in groups) {
                if (!content.contains(name)) {
                    group.appendText("$name:x:$id:root,aid_android\n")
                }
            }
        }

        val gshadow = File("$rootfsDir/etc/gshadow")
        if (gshadow.exists()) {
            val content = gshadow.readText()
            for (name in listOf("aid_inet", "aid_net_raw", "aid_sdcard_rw", "aid_android")) {
                if (!content.contains(name)) {
                    gshadow.appendText("$name:*::root,aid_android\n")
                }
            }
        }
    }

    private fun fixBinPermissions() {
        val recursiveExecDirs = listOf(
            "$rootfsDir/usr/bin", "$rootfsDir/usr/sbin",
            "$rootfsDir/usr/local/bin", "$rootfsDir/usr/local/sbin",
            "$rootfsDir/usr/lib/apt/methods", "$rootfsDir/usr/lib/dpkg",
            "$rootfsDir/usr/libexec", "$rootfsDir/var/lib/dpkg/info",
            "$rootfsDir/bin", "$rootfsDir/sbin",
        )
        for (dirPath in recursiveExecDirs) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) fixExecRecursive(dir)
        }

        val libDirs = listOf("$rootfsDir/usr/lib", "$rootfsDir/lib")
        for (dirPath in libDirs) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) fixSharedLibsRecursive(dir)
        }
    }

    private fun fixExecRecursive(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) fixExecRecursive(file)
            else if (file.isFile) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    private fun fixSharedLibsRecursive(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) fixSharedLibsRecursive(file)
            else if (file.name.endsWith(".so") || file.name.contains(".so.")) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    // ================================================================
    // Dependency Installation
    // ================================================================

    fun installDependencies() {
        val pm = ProcessManager(filesDir, nativeLibDir)
        pm.initialize()

        try {
            // Install Python and basic tools
            val aptResult = pm.runInProotSync(
                "apt-get update && apt-get install -y python3 python3-pip python3-venv git curl wget nano 2>&1 || true",
                600
            )
            Log.d(TAG, "apt result: ${aptResult.takeLast(500)}")
        } catch (e: Exception) {
            Log.e(TAG, "apt install failed: ${e.message}")
        }

        try {
            // Install Flask
            pm.runInProotSync(
                "pip3 install --break-system-packages flask flask-cors 2>&1 || pip3 install flask flask-cors 2>&1 || true",
                300
            )
        } catch (e: Exception) {
            Log.e(TAG, "pip install failed: ${e.message}")
        }
    }

    // ================================================================
    // IDE Files
    // ================================================================

    fun setupIDEFiles() {
        val rootIdeDir = "$rootfsDir/root/phoneide"
        File(rootIdeDir).mkdirs()

        val hostDir = File(hostIdeDir)
        if (hostDir.exists() && hostDir.isDirectory) {
            copyDirectory(hostDir, File(rootIdeDir))
            Log.d(TAG, "IDE files copied to $rootIdeDir")
        }
    }

    private fun copyDirectory(source: File, target: File) {
        if (!source.exists()) return
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            val targetFile = File(target, file.name)
            if (file.isDirectory) {
                copyDirectory(file, targetFile)
            } else {
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun setupLibtalloc() {
        val source = File("$nativeLibDir/libtalloc.so")
        val target = File("$libDir/libtalloc.so.2")
        if (source.exists() && !target.exists()) {
            source.copyTo(target)
            target.setExecutable(true)
        }
    }

    private fun setupFakeSysdata() {
        val pm = ProcessManager(filesDir, nativeLibDir)
        pm.setupFakeSysdata()
    }

    private fun ensureResolvConf() {
        val pm = ProcessManager(filesDir, nativeLibDir)
        pm.writeResolvConf()
    }

    fun writeResolvConf() { ensureResolvConf() }

    private fun deleteRecursively(file: File) {
        try {
            if (!file.canonicalPath.startsWith(filesDir)) return
        } catch (_: Exception) { return }

        try {
            val path = file.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) {
                file.delete()
                return
            }
        } catch (_: Exception) {}
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
