package com.phoneide

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ProcessManager - Core class for managing proot processes.
 * Bundles proot binary from Termux packages directly in the APK (as jniLibs).
 * No external Termux dependency required.
 *
 * KEY DIFFERENCES from naive proot setup (learned from stableclaw_android):
 * 1. Host environment must be CLEANED (pb.environment().clear()) to avoid
 *    Android JVM vars leaking into proot and breaking fork+exec.
 * 2. PROOT_LOADER/PROOT_LOADER_32 env vars tell proot where to find the
 *    ELF loader binaries (instead of relying on relative path resolution).
 * 3. PROOT_TMP_DIR must be set to a writable directory for proot internals.
 * 4. Many more fake /proc entries needed (vmstat, cap_last_cap, fips_enabled, etc.)
 * 5. Install mode uses --root-id (not --change-id=0:0) and no --sysvipc.
 * 6. Kernel release should be a proot-distro compatible format.
 * 7. /proc/sys/crypto/fips_enabled is CRITICAL - missing causes apt SIGABRT.
 */
class ProcessManager(private val context: android.content.Context) {

    companion object {
        private const val TAG = "ProcessManager"

        // proot binary names in jniLibs (from Termux packages)
        private const val PROOT_LIB = "libproot.so"
        private const val PROOT_LOADER_LIB = "libprootloader.so"
        private const val PROOT_LOADER32_LIB = "libprootloader32.so"
        private const val LIBTALLOC_LIB = "libtalloc.so"

        // Directories
        private const val ROOTFS_NAME = "ubuntu-rootfs"
        private const val LIB_DIR_NAME = "phoneide-lib"
        private const val CONFIG_DIR_NAME = "phoneide-config"
        private const val IDE_DIR_NAME = "phoneide"

        // Match proot-distro kernel release format
        const val FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Distro"
        const val FAKE_KERNEL_VERSION =
            "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"
    }

    // Paths resolved in init()
    private lateinit var appDir: String
    private lateinit var nativeLibDir: String
    private lateinit var rootfsDir: String
    private lateinit var libDir: String
    private lateinit var configDir: String
    private lateinit var hostIdeDir: String
    private lateinit var prootBin: String

    private var initialized = false

    fun initialize() {
        if (initialized) return

        appDir = context.filesDir.absolutePath
        nativeLibDir = context.applicationInfo.nativeLibraryDir
        rootfsDir = "$appDir/$ROOTFS_NAME"
        libDir = "$appDir/$LIB_DIR_NAME"
        configDir = "$appDir/$CONFIG_DIR_NAME"
        hostIdeDir = "$appDir/$IDE_DIR_NAME"

        File(libDir).mkdirs()
        File(configDir).mkdirs()

        setupProotBinary()
        setupLibtalloc()
        setupFakeSysdata()
        setupResolvConf()

        File(hostIdeDir).mkdirs()

        initialized = true
        Log.d(TAG, "ProcessManager initialized OK")
        Log.d(TAG, "  proot: $prootBin exists=${File(prootBin).exists()}")
        Log.d(TAG, "  rootfs: $rootfsDir ready=${isRootfsReady()}")
    }

    private fun setupProotBinary() {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libAbi = if (primaryAbi.startsWith("arm64")) "arm64-v8a"
                      else if (primaryAbi.startsWith("armeabi")) "armeabi-v7a" else primaryAbi

        val nativeProot = File("$nativeLibDir/$PROOT_LIB")
        if (!nativeProot.exists()) {
            Log.w(TAG, "nativeLibDir empty, extracting from APK (abi=$libAbi)")
            extractFromApk(libAbi)
        }

        // Copy proot binary to app data (nativeLibraryDir may have noexec SELinux)
        val destProot = File("$libDir/proot")
        val srcProot = File("$nativeLibDir/$PROOT_LIB")
        if (srcProot.exists()) {
            if (!destProot.exists() || destProot.length() != srcProot.length()) {
                srcProot.copyTo(destProot, overwrite = true)
                destProot.setExecutable(true, false)
                destProot.setReadable(true, false)
                destProot.setWritable(true, false)
            }
        } else {
            Log.e(TAG, "proot NOT found! nativeLibDir=$nativeLibDir")
        }

        // Copy loaders to libDir
        for (lib in listOf(PROOT_LOADER_LIB, PROOT_LOADER32_LIB)) {
            val src = File("$nativeLibDir/$lib")
            val dest = File("$libDir/$lib")
            if (src.exists() && (!dest.exists() || dest.length() != src.length())) {
                src.copyTo(dest, overwrite = true)
                dest.setExecutable(true, false)
                dest.setReadable(true, false)
            }
        }

        prootBin = "$libDir/proot"
    }

    private fun extractFromApk(abi: String) {
        val apkPath = context.applicationInfo.sourceDir ?: return
        val libs = listOf(PROOT_LIB, PROOT_LOADER_LIB, PROOT_LOADER32_LIB, LIBTALLOC_LIB)
        try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                for (libName in libs) {
                    val entry = zip.getEntry("lib/$abi/$libName") ?: continue
                    val dest = File(nativeLibDir, libName)
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var len: Int
                            while (input.read(buf).also { len = it } != -1) output.write(buf, 0, len)
                        }
                    }
                    dest.setExecutable(true, false)
                    dest.setReadable(true, false)
                    Log.d(TAG, "Extracted $libName from APK")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "APK extraction failed: ${e.message}")
        }
    }

    private fun setupLibtalloc() {
        val source = File("$nativeLibDir/$LIBTALLOC_LIB")
        val target = File("$libDir/libtalloc.so.2")
        if (source.exists() && (!target.exists() || target.length() != source.length())) {
            source.copyTo(target, overwrite = true)
            Log.d(TAG, "Created libtalloc.so.2")
        }
    }

    /**
     * Create fake /proc and /sys files for proot bind mounts.
     * Based on stableclaw_android's setupFakeSysdata().
     * Many of these are CRITICAL for apt/dpkg to not SIGABRT.
     */
    private fun setupFakeSysdata() {
        val procDir = File("$configDir/proc_fakes")
        val sysDir = File("$configDir/sys_fakes")
        procDir.mkdirs()
        sysDir.mkdirs()

        // /proc/loadavg
        writeFile("$procDir/loadavg", "0.12 0.07 0.02 2/165 765\n")

        // /proc/stat — 8 CPUs to avoid single-core issues
        writeFile("$procDir/stat",
            "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
            "cpu0 245 0 300 12027 82 10 4 9 0 0\n" +
            "cpu1 245 0 300 12027 82 10 4 9 0 0\n" +
            "cpu2 245 0 300 12027 82 10 4 9 0 0\n" +
            "cpu3 245 0 300 12027 82 10 4 9 0 0\n" +
            "cpu4 245 0 300 12027 82 10 4 9 0 0\n" +
            "cpu5 245 0 300 12027 82 10 4 9 0 0\n" +
            "cpu6 245 0 300 12027 82 10 4 9 0 0\n" +
            "cpu7 245 0 300 12027 82 10 4 9 0 0\n" +
            "intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n" +
            "ctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\n" +
            "softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n"
        )

        // /proc/uptime
        writeFile("$procDir/uptime", "124.08 932.80\n")

        // /proc/version — fake kernel info
        writeFile("$procDir/version",
            "Linux version $FAKE_KERNEL_RELEASE (proot@termux) " +
            "(gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) $FAKE_KERNEL_VERSION\n"
        )

        // /proc/vmstat — needed by apt/dpkg
        writeFile("$procDir/vmstat",
            "nr_free_pages 1743136\n" +
            "nr_zone_inactive_anon 179281\n" +
            "nr_zone_active_anon 7183\n" +
            "nr_zone_inactive_file 22858\n" +
            "nr_zone_active_file 51328\n" +
            "nr_zone_unevictable 642\n" +
            "nr_slab_reclaimable 7520\n" +
            "nr_slab_unreclaimable 10776\n" +
            "pgpgin 198292\n" +
            "pgpgout 7674\n" +
            "pswpin 0\n" +
            "pswpout 0\n" +
            "pgalloc_normal 44669136\n" +
            "pgfree 46674674\n" +
            "pgfault 37291463\n" +
            "pgmajfault 6854\n"
        )

        // /proc/meminfo — needed by apt
        writeFile("$procDir/meminfo",
            "MemTotal:        4096000 kB\n" +
            "MemFree:         2048000 kB\n" +
            "MemAvailable:    3072000 kB\n" +
            "Buffers:           65536 kB\n" +
            "Cached:          1024000 kB\n" +
            "SwapTotal:       1048576 kB\n" +
            "SwapFree:        1048576 kB\n"
        )

        // /proc/sys/kernel/cap_last_cap
        writeFile("$procDir/cap_last_cap", "40\n")

        // /proc/sys/fs/inotify/max_user_watches
        writeFile("$procDir/max_user_watches", "4096\n")

        // /proc/sys/crypto/fips_enabled — CRITICAL: libgcrypt reads this, missing causes apt SIGABRT
        writeFile("$procDir/fips_enabled", "0\n")

        // Empty file for /sys/fs/selinux bind (disables SELinux checks)
        writeFile("$sysDir/empty", "")
    }

    private fun setupResolvConf() {
        val content = "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n"
        // Host-side file for bind mount
        try {
            val f = File("$configDir/resolv.conf")
            if (!f.exists() || f.length() == 0L) {
                f.writeText(content)
            }
        } catch (_: Exception) {}
        // Fallback: write directly into rootfs
        try {
            val f = File("$rootfsDir/etc/resolv.conf")
            if (f.parentFile?.exists() == true && (!f.exists() || f.length() == 0L)) {
                f.writeText(content)
            }
        } catch (_: Exception) {}
    }

    private fun writeFile(path: String, content: String) {
        try { File(path).writeText(content) } catch (e: Exception) {
            Log.w(TAG, "Failed to write $path: ${e.message}")
        }
    }

    fun isInitialized(): Boolean = initialized
    fun isRootfsReady(): Boolean = File("$rootfsDir/bin/bash").exists() || File("$rootfsDir/usr/bin/bash").exists()
    fun getRootfsDir(): String = rootfsDir
    fun getIdeDir(): String = hostIdeDir
    fun getProotBin(): String { if (!initialized) initialize(); return prootBin }
    fun getLibDir(): String = libDir
    fun getConfigDir(): String = configDir

    // ============================================================
    // Host-side environment for proot (NOT guest env)
    // ============================================================
    private fun prootEnv(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to "$appDir/tmp",
        "PROOT_LOADER" to "$libDir/$PROOT_LOADER_LIB",
        "PROOT_LOADER_32" to "$libDir/$PROOT_LOADER32_LIB",
        "LD_LIBRARY_PATH" to "$libDir:$nativeLibDir",
    )

    // ============================================================
    // Common proot flags (matching proot-distro)
    // ============================================================
    private fun commonProotFlags(): List<String> {
        setupResolvConf()
        val procFakes = "$configDir/proc_fakes"
        val sysFakes = "$configDir/sys_fakes"

        return listOf(
            prootBin,
            "--link2symlink", "-L",
            "--kill-on-exit",
            "--rootfs=$rootfsDir",
            // Core device binds (matching proot-distro exactly)
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            // Fake /proc entries
            "--bind=$procFakes/loadavg:/proc/loadavg",
            "--bind=$procFakes/stat:/proc/stat",
            "--bind=$procFakes/uptime:/proc/uptime",
            "--bind=$procFakes/version:/proc/version",
            "--bind=$procFakes/vmstat:/proc/vmstat",
            "--bind=$procFakes/meminfo:/proc/meminfo",
            "--bind=$procFakes/cap_last_cap:/proc/sys/kernel/cap_last_cap",
            "--bind=$procFakes/max_user_watches:/proc/sys/fs/inotify/max_user_watches",
            "--bind=$procFakes/fips_enabled:/proc/sys/crypto/fips_enabled",
            // Shared memory
            "--bind=$rootfsDir/tmp:/dev/shm",
            // SELinux override
            "--bind=$sysFakes/empty:/sys/fs/selinux",
            // DNS
            "--bind=$configDir/resolv.conf:/etc/resolv.conf",
            // IDE files
            "--bind=$hostIdeDir:/root/phoneide",
        )
    }

    // ============================================================
    // Install mode (apt, pip, etc.) - matches proot-distro run_proot_cmd()
    // ============================================================
    fun buildInstallCommand(command: String): Array<String> {
        val flags = commonProotFlags().toMutableList()
        // --root-id: install mode (not --change-id=0:0)
        flags.add(1, "--root-id")
        flags.add(2, "--kernel-release=$FAKE_KERNEL_RELEASE")
        // NOTE: No --sysvipc during install (causes dpkg SIGABRT)

        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "TERM=dumb",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "/bin/bash", "-c",
            command,
        ))
        return flags.toTypedArray()
    }

    // ============================================================
    // Login shell mode - matches proot-distro command_login()
    // ============================================================
    fun buildLoginCommand(columns: Int = 80, rows: Int = 24): Array<String> {
        val flags = commonProotFlags().toMutableList()
        val arch = if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true) "aarch64"
                   else if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("armeabi") == true) "armv7l"
                   else "aarch64"

        // Full uname struct format
        val kernelRelease = "\\Linux\\localhost\\$FAKE_KERNEL_RELEASE\\$FAKE_KERNEL_VERSION\\$arch\\localdomain\\-1\\"
        flags.add(1, "--change-id=0:0")
        flags.add(2, "--sysvipc")
        flags.add(3, "--kernel-release=$kernelRelease")

        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "TERM=xterm-256color",
            "COLUMNS=$columns", "LINES=$rows",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "/bin/bash", "-l",
        ))
        return flags.toTypedArray()
    }

    // ============================================================
    // Process Execution
    // ============================================================
    fun runInProot(command: String, timeoutMs: Long = 300_000): CommandResult {
        initialize()
        if (!File(prootBin).exists()) {
            return CommandResult(false, "ERROR: proot not found\n", "", -1, 0)
        }
        if (!isRootfsReady()) {
            return CommandResult(false, "ERROR: rootfs not ready\n", "", -1, 0)
        }
        val cmdArray = buildInstallCommand(command)
        Log.d(TAG, "runInProot: ${cmdArray.take(5).joinToString(" ")} ... ${cmdArray.last()}")
        return executeCommand(cmdArray, prootEnv(), timeoutMs)
    }

    fun startProotProcess(command: String): Process? {
        initialize()
        if (!File(prootBin).exists() || !isRootfsReady()) return null
        val cmdArray = buildInstallCommand(command)
        return try {
            val pb = ProcessBuilder(*cmdArray)
            pb.environment().clear()
            pb.environment().putAll(prootEnv())
            pb.redirectErrorStream(true)
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "startProotProcess failed", e)
            null
        }
    }

    fun startLoginShell(columns: Int = 80, rows: Int = 24): Process? {
        initialize()
        if (!File(prootBin).exists() || !isRootfsReady()) return null
        val cmdArray = buildLoginCommand(columns, rows)
        return try {
            val pb = ProcessBuilder(*cmdArray)
            pb.environment().clear()
            pb.environment().putAll(prootEnv())
            pb.redirectErrorStream(false)
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "startLoginShell failed", e)
            null
        }
    }

    private fun executeCommand(cmdArray: Array<String>, env: Map<String, String>, timeoutMs: Long): CommandResult {
        val startTime = System.currentTimeMillis()
        try {
            val pb = ProcessBuilder(*cmdArray)
            // CRITICAL: Clear inherited Android JVM environment!
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)

            val process = pb.start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // Filter proot warnings
                if (l.contains("proot warning") || l.contains("can't sanitize")) continue
                output.append(l).append("\n")
            }
            reader.close()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(false, output.toString(), "timeout", -1,
                    System.currentTimeMillis() - startTime)
            }
            return CommandResult(
                success = process.exitValue() == 0,
                stdout = output.toString(),
                stderr = "",
                exitCode = process.exitValue(),
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            return CommandResult(false, "", e.message ?: "Unknown", -1,
                System.currentTimeMillis() - startTime)
        }
    }

    data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long
    )
}
