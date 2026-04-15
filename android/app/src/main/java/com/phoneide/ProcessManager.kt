package com.phoneide

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * ProcessManager - Manages proot process execution.
 * Based on stableclaw_android's ProcessManager pattern with two command modes:
 *   - Install mode (buildInstallCommand): for apt, pip, npm, etc.
 *   - Server mode (buildServerCommand): for long-lived Flask server
 *   - Login mode (buildLoginCommand): for interactive terminal shell
 */
class ProcessManager(val filesDir: String, val nativeLibDir: String) {

    companion object {
        private const val TAG = "ProcessManager"

        // Match proot-distro v4.37.0 defaults
        const val FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Distro"
        const val FAKE_KERNEL_VERSION =
            "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"
    }

    // Directories
    val rootfsDir get() = "$filesDir/rootfs/ubuntu"
    private val tmpDir get() = "$filesDir/tmp"
    private val homeDir get() = "$filesDir/home"
    private val configDir get() = "$filesDir/config"
    val libDir get() = "$filesDir/lib"
    val hostIdeDir get() = "$filesDir/phoneide"

    private var initialized = false

    // Secondary constructor for compatibility with PhoneIDEApp
    constructor(context: Context) : this(
        context.filesDir.absolutePath,
        context.applicationInfo.nativeLibraryDir
    )

    /**
     * Initialize paths and directories. Safe to call multiple times.
     */
    fun initialize() {
        if (initialized) return

        // Ensure all required directories exist
        listOf(rootfsDir, tmpDir, homeDir, configDir, libDir, hostIdeDir).forEach {
            File(it).mkdirs()
        }

        // Fix libtalloc SONAME
        setupLibtalloc()

        // Create fake /proc and /sys data for proot bind mounts
        setupFakeSysdata()

        // Ensure resolv.conf exists
        ensureResolvConf()

        initialized = true
        Log.d(TAG, "ProcessManager initialized")
        Log.d(TAG, "  rootfsDir: $rootfsDir")
        Log.d(TAG, "  nativeLibDir: $nativeLibDir")
        Log.d(TAG, "  proot exists: ${getProotPath().let { File(it).exists() }}")
    }

    fun ensureDirsReady() { initialize() }

    fun isInitialized(): Boolean = initialized
    fun isRootfsReady(): Boolean = File("$rootfsDir/bin/bash").exists()

    fun getProotPath(): String = "$nativeLibDir/libproot.so"

    // ================================================================
    // Host-side environment for proot binary itself.
    // ONLY proot-specific vars -- guest env is set via `env -i` inside.
    // ================================================================
    fun prootEnv(): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to tmpDir,
        "PROOT_LOADER" to "$nativeLibDir/libprootloader.so",
        "PROOT_LOADER_32" to "$nativeLibDir/libprootloader32.so",
        "LD_LIBRARY_PATH" to "$libDir:$nativeLibDir",
    )

    private fun setupLibtalloc() {
        val source = File("$nativeLibDir/libtalloc.so")
        val target = File("$libDir/libtalloc.so.2")
        if (source.exists() && !target.exists()) {
            source.copyTo(target)
            target.setExecutable(true)
        }
    }

    // ================================================================
    // Fake /proc and /sys data for proot bind mounts.
    // Android restricts many /proc entries; we provide static data.
    // ================================================================
    fun setupFakeSysdata() {
        val procDir = File("$configDir/proc_fakes")
        val sysDir = File("$configDir/sys_fakes")
        procDir.mkdirs()
        sysDir.mkdirs()

        // /proc/loadavg
        File(procDir, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")

        // /proc/stat -- matching proot-distro (8 CPUs)
        File(procDir, "stat").writeText(
            "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
            "cpu0 31 0 226 12027 82 10 4 9 0 0\n" +
            "cpu1 45 0 290 11498 21 9 8 7 0 0\n" +
            "cpu2 52 0 401 11730 36 15 6 10 0 0\n" +
            "cpu3 42 0 268 11677 31 12 5 8 0 0\n" +
            "cpu4 789 0 720 11364 26 100 83 18 0 0\n" +
            "cpu5 486 0 438 11685 42 86 60 13 0 0\n" +
            "cpu6 314 0 336 11808 45 68 52 11 0 0\n" +
            "cpu7 198 0 198 11491 25 42 36 11 0 0\n" +
            "intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n" +
            "ctxt 38014093\n" +
            "btime 1694292441\n" +
            "processes 26442\n" +
            "procs_running 1\n" +
            "procs_blocked 0\n" +
            "softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n"
        )

        // /proc/uptime
        File(procDir, "uptime").writeText("124.08 932.80\n")

        // /proc/version -- fake kernel info matching proot-distro v4.37.0
        File(procDir, "version").writeText(
            "Linux version $FAKE_KERNEL_RELEASE (proot@termux) " +
            "(gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) " +
            "$FAKE_KERNEL_VERSION\n"
        )

        // /proc/vmstat -- matching proot-distro format
        File(procDir, "vmstat").writeText(
            "nr_free_pages 1743136\n" +
            "nr_zone_inactive_anon 179281\n" +
            "nr_zone_active_anon 7183\n" +
            "nr_zone_inactive_file 22858\n" +
            "nr_zone_active_file 51328\n" +
            "nr_zone_unevictable 642\n" +
            "nr_zone_write_pending 0\n" +
            "nr_mlock 0\n" +
            "nr_slab_reclaimable 7520\n" +
            "nr_slab_unreclaimable 10776\n" +
            "pgpgin 198292\n" +
            "pgpgout 7674\n" +
            "pswpin 0\n" +
            "pswpout 0\n" +
            "pgalloc_normal 44669136\n" +
            "pgfree 46674674\n" +
            "pgactivate 1085674\n" +
            "pgdeactivate 340776\n"
        )

        // /proc/sys/kernel/cap_last_cap
        val capDir = File("$configDir/proc_fakes/sys/kernel")
        capDir.mkdirs()
        File(capDir, "cap_last_cap").writeText("40")

        // /proc/sys/fs/inotify/max_user_watches
        val inotifyDir = File("$configDir/proc_fakes/sys/fs/inotify")
        inotifyDir.mkdirs()
        File(inotifyDir, "max_user_watches").writeText("8192")

        // /proc/sys/crypto/fips_enabled -- fixes apt SIGABRT from libgcrypt
        val cryptoDir = File("$configDir/proc_fakes/sys/crypto")
        cryptoDir.mkdirs()
        File(cryptoDir, "fips_enabled").writeText("0")

        // /sys/fs/selinux -- empty dir disables SELinux checks
        File("$sysFakes/empty").mkdirs()
    }

    private val sysFakes get() = "$configDir/sys_fakes"

    // ================================================================
    // DNS / resolv.conf
    // ================================================================
    private fun ensureResolvConf() {
        val content = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"

        // Primary: host-side file used by --bind mount
        try {
            val resolvFile = File(configDir, "resolv.conf")
            if (!resolvFile.exists() || resolvFile.length() == 0L) {
                resolvFile.parentFile?.mkdirs()
                resolvFile.writeText(content)
            }
        } catch (_: Exception) {}

        // Fallback: write directly into rootfs /etc/resolv.conf
        try {
            val rootfsResolv = File(rootfsDir, "etc/resolv.conf")
            if (!rootfsResolv.exists() || rootfsResolv.length() == 0L) {
                rootfsResolv.parentFile?.mkdirs()
                rootfsResolv.writeText(content)
            }
        } catch (_: Exception) {}
    }

    fun writeResolvConf() { ensureResolvConf() }

    // ================================================================
    // Common proot flags shared by all command modes.
    // Matches proot-distro's bind mounts exactly.
    // ================================================================
    private fun commonProotFlags(): List<String> {
        ensureResolvConf()

        val prootPath = getProotPath()
        val procFakes = "$configDir/proc_fakes"
        val sysFakes = "$configDir/sys_fakes"

        return listOf(
            prootPath,
            "--link2symlink",
            "-L",
            "--kill-on-exit",
            "--rootfs=$rootfsDir",
            "--cwd=/root",
            // Core device binds (matching proot-distro)
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
            "--bind=$procFakes/sys/kernel/cap_last_cap:/proc/sys/kernel/cap_last_cap",
            "--bind=$procFakes/sys/fs/inotify/max_user_watches:/proc/sys/fs/inotify/max_user_watches",
            // Extra: libgcrypt reads this; missing causes apt SIGABRT
            "--bind=$procFakes/sys/crypto/fips_enabled:/proc/sys/crypto/fips_enabled",
            // Shared memory
            "--bind=$rootfsDir/tmp:/dev/shm",
            // SELinux override -- empty dir disables SELinux checks
            "--bind=$sysFakes/empty:/sys/fs/selinux",
            // App-specific binds
            "--bind=$configDir/resolv.conf:/etc/resolv.conf",
            "--bind=$homeDir:/root/home",
            "--bind=$hostIdeDir:/root/phoneide",
        ).let { flags ->
            // Storage access
            val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                val sdcard = Environment.getExternalStorageDirectory()
                sdcard.exists() && sdcard.canRead()
            }
            if (hasAccess) {
                flags + listOf(
                    "--bind=/storage:/storage",
                    "--bind=/storage/emulated/0:/sdcard"
                )
            } else {
                flags
            }
        }
    }

    // ================================================================
    // INSTALL MODE -- for apt-get, pip, npm, etc.
    // Simpler: no --sysvipc, simple kernel-release.
    // ================================================================
    fun buildInstallCommand(command: String): List<String> {
        val flags = commonProotFlags().toMutableList()

        // --root-id: fake root identity (same as proot-distro run_proot_cmd)
        flags.add(1, "--root-id")
        // Simple kernel-release (proot-distro run_proot_cmd uses plain string)
        flags.add(2, "--kernel-release=$FAKE_KERNEL_RELEASE")
        // NO --sysvipc during install (causes dpkg SIGABRT)

        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=dumb",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "/bin/bash", "-c",
            command,
        ))

        return flags
    }

    // ================================================================
    // SERVER MODE -- for running Flask server (long-lived).
    // Full featured: --sysvipc, full uname struct.
    // ================================================================
    fun buildServerCommand(command: String): List<String> {
        val flags = commonProotFlags().toMutableList()

        // --change-id=0:0 for root
        flags.add(1, "--change-id=0:0")
        // --sysvipc for long-lived processes
        flags.add(2, "--sysvipc")
        // Full uname struct format (matching proot-distro command_login)
        val kernelRelease = "\\Linux\\localhost\\$FAKE_KERNEL_RELEASE" +
            "\\$FAKE_KERNEL_VERSION\\aarch64\\localdomain\\-1\\"
        flags.add(3, "--kernel-release=$kernelRelease")

        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "/bin/bash", "-c",
            command,
        ))

        return flags
    }

    // ================================================================
    // LOGIN MODE -- for interactive terminal shell.
    // Same as server mode but with /bin/bash -l.
    // ================================================================
    fun buildLoginCommand(
        columns: Int = 80,
        rows: Int = 24
    ): List<String> {
        val flags = commonProotFlags().toMutableList()

        // --change-id=0:0 for root
        flags.add(1, "--change-id=0:0")
        // --sysvipc for login sessions
        flags.add(2, "--sysvipc")
        // Full uname struct
        val kernelRelease = "\\Linux\\localhost\\$FAKE_KERNEL_RELEASE" +
            "\\$FAKE_KERNEL_VERSION\\aarch64\\localdomain\\-1\\"
        flags.add(3, "--kernel-release=$kernelRelease")

        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "COLUMNS=$columns",
            "LINES=$rows",
            "/bin/bash", "-l",
        ))

        return flags
    }

    // ================================================================
    // Execute a command in proot (install mode) and return output.
    // Used during bootstrap for apt, pip, etc.
    // ================================================================
    fun runInProotSync(command: String, timeoutSeconds: Long = 900): String {
        initialize()
        val cmd = buildInstallCommand(command)
        val env = prootEnv()

        val pb = ProcessBuilder(cmd)
        // CRITICAL: Clear inherited Android JVM environment.
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (l.contains("proot warning") || l.contains("can't sanitize")) {
                continue
            }
            output.appendLine(l)
        }

        val exited = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            throw RuntimeException("Command timed out after ${timeoutSeconds}s")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw RuntimeException(
                "Command failed (exit code $exitCode): ${output.toString().takeLast(3000)}"
            )
        }

        return output.toString()
    }

    /**
     * Legacy compatibility method for BootstrapManager/SetupActivity.
     */
    fun runInProot(
        command: String,
        cwd: String? = null,
        env: Map<String, String>? = null,
        timeoutMs: Long = 300_000
    ): CommandResult {
        val startTime = System.currentTimeMillis()
        return try {
            val cmdList = buildInstallCommand(command)
            val pb = ProcessBuilder(cmdList)
            pb.environment().clear()
            pb.environment().putAll(prootEnv())
            pb.redirectErrorStream(true)

            val process = pb.start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.contains("proot warning") || l.contains("can't sanitize")) continue
                output.append(l).append("\n")
            }

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandResult(false, output.toString(), "Timeout", -1,
                    System.currentTimeMillis() - startTime)
            } else {
                CommandResult(process.exitValue() == 0, output.toString(), "",
                    process.exitValue(), System.currentTimeMillis() - startTime)
            }
        } catch (e: Exception) {
            CommandResult(false, "", e.message ?: "Unknown", -1,
                System.currentTimeMillis() - startTime)
        }
    }

    // ================================================================
    // Start a long-lived process (e.g. Flask server).
    // Returns Process for lifecycle management.
    // ================================================================
    fun startProotProcess(command: String): Process? {
        initialize()
        return try {
            val cmd = buildServerCommand(command)
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(prootEnv())
            pb.redirectErrorStream(false)
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proot process", e)
            null
        }
    }

    /**
     * Start a login shell for terminal use (legacy compatibility).
     */
    fun startLoginShell(columns: Int = 80, rows: Int = 24): Process? {
        initialize()
        return try {
            val cmd = buildLoginCommand(columns, rows)
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(prootEnv())
            pb.redirectErrorStream(false)
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start login shell", e)
            null
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
