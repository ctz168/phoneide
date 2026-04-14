package com.phoneide

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BootstrapManager - Manages Ubuntu rootfs lifecycle for proot.
 * Downloads rootfs tarball, extracts with pure Java (Apache Commons Compress),
 * and configures proot compatibility patches.
 */
class BootstrapManager(
    private val context: Context,
    private val processManager: ProcessManager
) {

    companion object {
        private const val TAG = "BootstrapManager"

        // Ubuntu 24.04 rootfs from Termux proot-distro
        // Using the same source as proot-distro for reliability
        private const val UBUNTU_ROOTFS_URL = "https://github.com/termux/proot-distro/releases/download/v2.6.0/ubuntu-arm64-v8a-v2.6.0.tar.xz"
        private const val UBUNTU_ROOTFS_ALT_URL = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot-distro/proot-distro-rootfs-ubuntu_24.04_arm64-v8a.tar.xz"

        // The rootfs dir is managed by ProcessManager
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
        return File("$rootfsDir/bin/bash").exists() &&
               File("$rootfsDir/etc/apt").isDirectory
    }

    /**
     * Full bootstrap: download -> extract -> configure -> install deps.
     * Call from a coroutine/background thread.
     */
    fun bootstrap(): Boolean {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Bootstrap already in progress")
            return false
        }

        try {
            processManager.initialize()
            val rootfsDir = processManager.getRootfsDir()

            reportProgress(0, "Starting bootstrap...")

            // Step 1: Download rootfs
            if (!isRootfsReady()) {
                reportProgress(5, "Downloading Ubuntu rootfs...")
                if (!downloadRootfs(rootfsDir)) {
                    reportProgress(-1, "Failed to download rootfs")
                    return false
                }
                reportProgress(40, "Rootfs downloaded")
            } else {
                reportProgress(40, "Rootfs already exists, skipping download")
            }

            // Step 2: Extract rootfs
            reportProgress(45, "Extracting rootfs (this may take a few minutes)...")
            // If not yet extracted (download saved as .tar.xz)
            val tarFile = File("$rootfsDir/rootfs.tar.xz")
            if (tarFile.exists()) {
                extractRootfs(tarFile, rootfsDir)
                tarFile.delete()
            }
            reportProgress(70, "Rootfs extracted")

            // Step 3: Configure rootfs for proot compatibility
            reportProgress(75, "Configuring proot compatibility...")
            configureRootfs()
            reportProgress(80, "Rootfs configured")

            // Step 4: Install Python and dependencies
            reportProgress(85, "Installing Python and Flask...")
            installDependencies()
            reportProgress(95, "Dependencies installed")

            // Step 5: Copy IDE files into rootfs
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

    private fun downloadRootfs(rootfsDir: String): Boolean {
        val rootfsFile = File("$rootfsDir/rootfs.tar.xz")
        File(rootfsDir).mkdirs()

        // Try primary URL, then fallback
        val urls = listOf(UBUNTU_ROOTFS_URL, UBUNTU_ROOTFS_ALT_URL)

        for ((index, url) in urls.withIndex()) {
            try {
                reportProgress(5 + index * 15, "Trying mirror ${index + 1}...")
                downloadFile(url, rootfsFile) { bytesRead, totalBytes ->
                    val percent = if (totalBytes > 0) {
                        (10 + (bytesRead.toFloat() / totalBytes * 25)).toInt()
                    } else {
                        10
                    }
                    reportProgress(percent.coerceAtMost(38),
                        "Downloading... ${(bytesRead / 1024 / 1024)}MB")
                }
                return rootfsFile.exists() && rootfsFile.length() > 1_000_000 // At least 1MB
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
        val connection = url.openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.connect()

        val totalBytes = connection.contentLength.toLong()
        var bytesRead: Long = 0

        connection.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    onProgress?.invoke(bytesRead, totalBytes)
                }
            }
        }
    }

    /**
     * Extract rootfs tar.xz using Apache Commons Compress (pure Java, no system tools).
     * Two-phase extraction: first files/dirs, then symlinks.
     */
    private fun extractRootfs(tarFile: File, rootfsDir: String) {
        // Phase 1: Extract directories, regular files, and hard links
        val symlinks = mutableListOf<Pair<String, String>>() // (linkTarget, entryName)

        FileInputStream(tarFile).use { fis ->
            XZCompressorInputStream(fis).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    var count = 0
                    val totalEntries = 5000 // rough estimate for progress

                    while (entry != null) {
                        count++
                        if (count % 100 == 0) {
                            val percent = 45 + (count.toFloat() / totalEntries * 22).toInt().coerceAtMost(24)
                            reportProgress(percent, "Extracting: ${entry.name}")
                        }

                        val entryFile = File(rootfsDir, entry.name)

                        // Security: prevent path traversal
                        if (!entryFile.canonicalPath.startsWith(File(rootfsDir).canonicalPath)) {
                            entry = tarIn.nextTarEntry
                            continue
                        }

                        when {
                            entry.isSymbolicLink -> {
                                symlinks.add(entry.linkName to entry.name)
                            }
                            entry.isDirectory -> {
                                entryFile.mkdirs()
                            }
                            entry.isFile -> {
                                entryFile.parentFile?.mkdirs()
                                entryFile.outputStream().use { out ->
                                    tarIn.copyEntryContents(out as java.io.OutputStream)
                                }
                                // Preserve permissions
                                if (entry.mode > 0) {
                                    try {
                                        entryFile.setReadable((entry.mode and 0400) != 0)
                                        entryFile.setExecutable((entry.mode and 0100) != 0)
                                    } catch (e: Exception) { }
                                }
                            }
                            entry.isLink -> {
                                // Hard link
                                val targetFile = File(rootfsDir, entry.linkName)
                                if (targetFile.exists()) {
                                    entryFile.parentFile?.mkdirs()
                                    targetFile.copyTo(entryFile, overwrite = true)
                                }
                            }
                        }

                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }

        // Phase 2: Create symlinks (deferred so target dirs exist)
        reportProgress(67, "Creating symlinks...")
        for ((linkTarget, entryName) in symlinks) {
            try {
                val entryFile = File(rootfsDir, entryName)
                entryFile.parentFile?.mkdirs()
                if (entryFile.exists()) entryFile.delete()
                java.nio.file.Files.createSymbolicLink(
                    entryFile.toPath(),
                    File(linkTarget).toPath()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink $entryName -> $linkTarget: ${e.message}")
            }
        }
    }

    /**
     * Configure rootfs for proot compatibility.
     * Fixes apt sandboxing, dpkg, and Android UID mapping.
     */
    private fun configureRootfs() {
        val rootfsDir = processManager.getRootfsDir()

        // 1. Disable apt sandboxing (proot can't intercept setresuid)
        val aptConfDir = "$rootfsDir/etc/apt/apt.conf.d"
        File(aptConfDir).mkdirs()
        File("$aptConfDir/01-phoneide-proot").writeText(
            "APT::Sandbox::User \"root\";\n" +
            "Dpkg::Use-Pty \"0\";\n" +
            "Dpkg::Options { \"--force-confnew\"; \"--force-overwrite\"; };\n"
        )

        // 2. Configure dpkg for proot compatibility
        val dpkgConfDir = "$rootfsDir/etc/dpkg/dpkg.cfg.d"
        File(dpkgConfDir).mkdirs()
        File("$dpkgConfDir/01-phoneide-proot").writeText(
            "force-unsafe-io\n" +
            "no-debsig\n" +
            "force-overwrite\n" +
            "force-depends\n"
        )

        // 3. Register Android UID in rootfs passwd/group (matching proot-distro)
        try {
            val uid = android.os.Process.myUid()
            val gid = uid // Typically same on Android

            val passwdFile = File("$rootfsDir/etc/passwd")
            if (passwdFile.exists()) {
                val passwd = passwdFile.readText()
                if (!passwd.contains("aid_android")) {
                    passwdFile.appendText(
                        "\naid_android:x:$uid:$gid:Android:/:/sbin/nologin\n"
                    )
                }
            }

            val groupFile = File("$rootfsDir/etc/group")
            if (groupFile.exists()) {
                val group = groupFile.readText()
                if (!group.contains("aid_android")) {
                    groupFile.appendText(
                        "\naid_android:x:$gid:\n"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Android UID: ${e.message}")
        }

        // 4. Fix /etc/hostname
        File("$rootfsDir/etc/hostname").writeText("phoneide\n")

        // 5. Ensure /tmp exists
        File("$rootfsDir/tmp").mkdirs()
    }

    /**
     * Install Python, Flask, and other dependencies inside proot.
     */
    private fun installDependencies() {
        // Use processManager.runInProot to execute apt commands
        val result = processManager.runInProot(
            "apt-get update && apt-get install -y python3 python3-pip python3-venv git curl wget nano 2>&1",
            timeoutMs = 600_000
        )
        Log.d(TAG, "apt-get install result: success=${result.success}, exit=${result.exitCode}")
        if (result.stderr.isNotEmpty()) {
            Log.w(TAG, "apt stderr: ${result.stderr.take(500)}")
        }

        // Install Flask
        val pipResult = processManager.runInProot(
            "pip3 install --break-system-packages flask flask-cors 2>&1 || pip3 install flask flask-cors 2>&1",
            timeoutMs = 300_000
        )
        Log.d(TAG, "pip install result: success=${pipResult.success}")
    }

    /**
     * Copy IDE files from host side into the rootfs.
     */
    private fun setupIDEFiles() {
        val hostIdeDir = processManager.getIdeDir()
        val rootfsDir = processManager.getRootfsDir()

        // Create phoneide directory in rootfs
        val rootIdeDir = "$rootfsDir/root/phoneide"
        File(rootIdeDir).mkdirs()

        // Copy all files from host IDE dir to rootfs
        val hostDir = File(hostIdeDir)
        if (hostDir.exists() && hostDir.isDirectory) {
            copyDirectory(hostDir, File(rootIdeDir))
        }

        Log.d(TAG, "IDE files copied to $rootIdeDir")
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
}
