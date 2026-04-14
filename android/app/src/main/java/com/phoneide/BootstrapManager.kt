package com.phoneide

import android.content.Context
import android.os.Build
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.net.HttpURLConnection
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

        private const val RELEASES_BASE = "https://github.com/termux/proot-distro/releases/download"

        /**
         * Build the list of rootfs download URLs based on device ABI.
         * Includes multiple Ubuntu versions and GitHub mirror proxies.
         */
        fun getRootfsUrls(): List<String> {
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val archSuffix = if (primaryAbi.startsWith("arm64") || primaryAbi == "aarch64") {
                "aarch64"
            } else if (primaryAbi.startsWith("armeabi") || primaryAbi.startsWith("arm")) {
                "arm"
            } else {
                // Fallback: try aarch64 first for unknown archs
                "aarch64"
            }

            Log.d(TAG, "Detected arch: $primaryAbi -> suffix: $archSuffix")

            val urls = mutableListOf<String>()

            // Ubuntu 24.04 LTS (noble) - most stable, recommended
            urls.add("$RELEASES_BASE/v4.18.0/ubuntu-noble-$archSuffix-pd-v4.18.0.tar.xz")

            // Ubuntu 25.04 (plucky) - newer
            urls.add("$RELEASES_BASE/v4.29.0/ubuntu-plucky-$archSuffix-pd-v4.29.0.tar.xz")

            // Ubuntu 25.10 (questing) - latest
            urls.add("$RELEASES_BASE/v4.30.1/ubuntu-questing-$archSuffix-pd-v4.30.1.tar.xz")

            // Ubuntu 24.04 LTS (noble) v4.11.0 - smaller size fallback
            urls.add("$RELEASES_BASE/v4.11.0/ubuntu-noble-$archSuffix-pd-v4.11.0.tar.xz")

            // GitHub mirror proxies (for users who can't access github.com directly)
            val ghProxies = listOf(
                "https://ghfast.top",
                "https://gh-proxy.com"
            )
            for (proxy in ghProxies) {
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
        val bashExists = File("$rootfsDir/bin/bash").exists()
        val aptExists = File("$rootfsDir/etc/apt").isDirectory
        val usrExists = File("$rootfsDir/usr").isDirectory
        Log.d(TAG, "isRootfsReady: bash=$bashExists, apt=$aptExists, usr=$usrExists")
        return bashExists && (aptExists || usrExists)
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

        val urls = getRootfsUrls()
        Log.d(TAG, "Will try ${urls.size} URLs for rootfs download")

        for ((index, url) in urls.withIndex()) {
            // Clean up partial download
            if (rootfsFile.exists()) rootfsFile.delete()

            try {
                val label = if (index < 3) "Ubuntu ${listOf("24.04", "25.04", "25.10")[index]}" else "Proxy ${index - 2}"
                reportProgress(5, "Trying $label (URL ${index + 1}/${urls.size})...")
                Log.d(TAG, "Downloading from: $url")

                downloadFile(url, rootfsFile) { bytesRead, totalBytes ->
                    val percent = if (totalBytes > 0) {
                        (10 + (bytesRead.toFloat() / totalBytes * 25)).toInt()
                    } else {
                        10
                    }
                    val mb = bytesRead / 1024 / 1024
                    reportProgress(percent.coerceAtMost(38), "Downloading... ${mb}MB")
                }

                // Validate: at least 10MB for a valid Ubuntu rootfs
                if (rootfsFile.exists() && rootfsFile.length() > 10_000_000) {
                    Log.d(TAG, "Downloaded rootfs: ${rootfsFile.length() / 1024 / 1024}MB from $url")
                    return true
                } else {
                    Log.w(TAG, "Downloaded file too small: ${rootfsFile.length()} bytes, skipping")
                    if (rootfsFile.exists()) rootfsFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download from URL $index failed: ${e.message}")
                reportProgress(5, "Mirror $index failed, trying next...")
            }
        }

        Log.e(TAG, "All ${urls.size} download URLs failed")
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
        connection.readTimeout = 300_000  // 5 min timeout for large files (~60MB)
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "PhoneIDE/2.0")
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            throw IOException("HTTP $responseCode for $urlString")
        }

        val totalBytes = connection.contentLength.toLong()
        var bytesRead: Long = 0

        connection.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(16384)  // 16KB buffer for faster download
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
                                FileOutputStream(entryFile).use { fos ->
                                    val buf = ByteArray(8192)
                                    var len: Int
                                    while (tarIn.read(buf).also { len = it } != -1) {
                                        fos.write(buf, 0, len)
                                    }
                                }
                                // Preserve permissions
                                if (entry.mode > 0) {
                                    try {
                                        entryFile.setReadable((entry.mode and 256) != 0)
                                        entryFile.setExecutable((entry.mode and 64) != 0)
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
     * Non-fatal: logs warnings but does not throw exceptions.
     */
    private fun installDependencies() {
        try {
            // Step 1: Quick test that proot + rootfs actually works
            val testResult = processManager.runInProot(
                "echo 'proot OK' && cat /etc/os-release | head -1",
                timeoutMs = 30_000
            )
            if (!testResult.success) {
                Log.e(TAG, "Proot test failed: ${testResult.stdout}")
                reportProgress(-1, "Proot 测试失败: ${testResult.stdout.take(200)}")
                // Don't return - try apt anyway
            } else {
                Log.d(TAG, "Proot test OK: ${testResult.stdout.trim()}")
            }

            // Step 2: apt-get update
            val updateResult = processManager.runInProot(
                "apt-get update 2>&1",
                timeoutMs = 120_000
            )
            Log.d(TAG, "apt-get update: success=${updateResult.success}, exit=${updateResult.exitCode}")
            if (!updateResult.success) {
                Log.w(TAG, "apt-get update failed (non-fatal): ${updateResult.stdout.take(300)}")
            }

            // Step 3: apt-get install python3
            val installResult = processManager.runInProot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 python3-pip 2>&1",
                timeoutMs = 600_000
            )
            Log.d(TAG, "apt-get install result: success=${installResult.success}, exit=${installResult.exitCode}")
            if (installResult.stdout.isNotEmpty()) {
                Log.d(TAG, "apt stdout: ${installResult.stdout.take(500)}")
            }

            // Step 4: Install Flask
            val pipResult = processManager.runInProot(
                "pip3 install --break-system-packages flask flask-cors 2>&1 || pip3 install flask flask-cors 2>&1",
                timeoutMs = 300_000
            )
            Log.d(TAG, "pip install result: success=${pipResult.success}")
            if (pipResult.stdout.isNotEmpty()) {
                Log.d(TAG, "pip stdout: ${pipResult.stdout.take(300)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "installDependencies failed (non-fatal): ${e.message}")
            reportProgress(-1, "依赖安装失败(非致命): ${e.message}")
        }
    }

    /**
     * Copy IDE files from APK assets into the rootfs.
     * First extracts from assets/ide/ to hostIdeDir, then copies into rootfs.
     */
    private fun setupIDEFiles() {
        val hostIdeDir = processManager.getIdeDir()
        val rootIdeDir = "${processManager.getRootfsDir()}/root/phoneide"

        // Step 1: Extract IDE files from APK assets to host directory
        val hostDir = File(hostIdeDir)
        if (!hostDir.exists() || hostDir.listFiles()?.isEmpty() != false) {
            hostDir.mkdirs()
            extractAssetsDir("ide", hostDir)
            Log.d(TAG, "Extracted IDE files from assets to $hostIdeDir")
        }

        // Step 2: Copy into rootfs (proot bind mount target)
        File(rootIdeDir).mkdirs()
        copyDirectory(hostDir, File(rootIdeDir))
        Log.d(TAG, "IDE files copied to $rootIdeDir")

        // Step 3: Verify key files exist
        val serverPy = File("$rootIdeDir/server.py")
        val indexHtml = File("$rootIdeDir/static/index.html")
        if (serverPy.exists() && indexHtml.exists()) {
            Log.d(TAG, "IDE files verified: server.py + index.html OK")
        } else {
            Log.w(TAG, "IDE files incomplete! server.py=${serverPy.exists()}, index.html=${indexHtml.exists()}")
        }
    }

    /**
     * Recursively extract a directory from APK assets to the filesystem.
     */
    private fun extractAssetsDir(assetPath: String, targetDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: return

        for (file in files) {
            val fullAssetPath = "$assetPath/$file"
            val targetFile = File(targetDir, file)

            // Check if it's a directory or file by trying to list it
            val subFiles = try { assetManager.list(fullAssetPath) } catch (e: Exception) { null }

            if (subFiles != null && subFiles.isNotEmpty()) {
                // It's a directory
                targetFile.mkdirs()
                extractAssetsDir(fullAssetPath, targetFile)
            } else {
                // It's a file - copy it
                targetFile.parentFile?.mkdirs()
                try {
                    assetManager.open(fullAssetPath).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (input.read(buffer).also { len = it } != -1) {
                                output.write(buffer, 0, len)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract asset $fullAssetPath: ${e.message}")
                }
            }
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
}
