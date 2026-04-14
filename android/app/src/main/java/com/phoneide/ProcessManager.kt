package com.phoneide

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * ProcessManager - Core class for managing proot processes.
 * Bundles proot binary from Termux packages directly in the APK (as jniLibs).
 * No external Termux dependency required.
 */
class ProcessManager(private val context: Context) {

    companion object {
        private const val TAG = "ProcessManager"

        // proot binary names in jniLibs (renamed from Termux .deb packages)
        private const val PROOT_LIB = "libproot.so"
        private const val PROOT_LOADER_LIB = "libprootloader.so"
        private const val PROOT_LOADER32_LIB = "libprootloader32.so"
        private const val LIBTALLOC_LIB = "libtalloc.so"

        // Rootfs location
        private const val ROOTFS_NAME = "ubuntu-rootfs"
        private const val LIB_DIR_NAME = "phoneide-lib"

        // Server
        private const val IDE_DIR_NAME = "phoneide"
    }

    // Paths resolved in init()
    private lateinit var appDir: String           // context.filesDir.absolutePath
    private lateinit var nativeLibDir: String      // app.nativeLibraryDir
    private lateinit var rootfsDir: String         // appDir/ROOTFS_NAME
    private lateinit var libDir: String            // appDir/LIB_DIR_NAME
    private lateinit var ideDir: String            // rootfsDir/root/phoneide  (inside proot)
    private lateinit var prootBin: String          // path to libproot.so
    private lateinit var hostIdeDir: String        // appDir/IDE_DIR_NAME (host-side copy)

    private var initialized = false
    private val outputListeners = ConcurrentLinkedQueue<(String) -> Unit>()

    /**
     * Initialize paths and fix libtalloc SONAME.
     * Copies proot binaries from nativeLibDir to app data dir (for execution).
     * Call this once after creating ProcessManager.
     */
    fun initialize() {
        if (initialized) return

        appDir = context.filesDir.absolutePath
        nativeLibDir = context.applicationInfo.nativeLibraryDir
        rootfsDir = "$appDir/$ROOTFS_NAME"
        libDir = "$appDir/$LIB_DIR_NAME"
        hostIdeDir = "$appDir/$IDE_DIR_NAME"

        // Ensure lib directory exists
        File(libDir).mkdirs()

        // Copy proot binary to app data dir for execution
        // (nativeLibraryDir may have noexec SELinux policy)
        setupProotBinary()

        // Fix libtalloc SONAME: Termux's proot links against libtalloc.so.2
        // but Android jniLibs strips the version suffix.
        setupLibtalloc()

        // Ensure basic directories
        File(hostIdeDir).mkdirs()

        initialized = true
        Log.d(TAG, "ProcessManager initialized")
        Log.d(TAG, "  appDir: $appDir")
        Log.d(TAG, "  nativeLibDir: $nativeLibDir")
        Log.d(TAG, "  rootfsDir: $rootfsDir")
        Log.d(TAG, "  libDir: $libDir")
        Log.d(TAG, "  prootBin: $prootBin")
        Log.d(TAG, "  proot exists: ${File(prootBin).exists()}")
        Log.d(TAG, "  proot canExecute: ${File(prootBin).exists() && File(prootBin).canExecute()}")
    }

    /**
     * Copy proot + loader binaries from nativeLibDir to libDir (app data).
     * Android's nativeLibraryDir may not allow direct execution due to SELinux.
     *
     * proot resolves its loader relative to its own binary path:
     *   dirname(proot) -> ../libexec/proot/loader
     * So we create: appDir/bin/proot -> appDir/libexec/proot/loader
     */
    private fun setupProotBinary() {
        // Directory layout matching Termux:
        //   appDir/bin/proot           (the proot executable)
        //   appDir/libexec/proot/loader  (the ELF loader)
        //   appDir/libexec/proot/loader32 (32-bit loader, arm64 only)
        //   appDir/lib/                   (libtalloc etc.)
        val binDir = File("$appDir/bin")
        val libexecDir = File("$appDir/libexec/proot")
        val libExecDir2 = File("$appDir/lib")
        binDir.mkdirs()
        libexecDir.mkdirs()
        libExecDir2.mkdirs()

        // Copy proot binary
        val nativeProot = File("$nativeLibDir/$PROOT_LIB")
        val destProot = File("$binDir/proot")
        if (nativeProot.exists()) {
            if (!destProot.exists() || destProot.length() != nativeProot.length()) {
                nativeProot.copyTo(destProot, overwrite = true)
                destProot.setExecutable(true, false)
                destProot.setReadable(true, false)
                destProot.setWritable(true, false)
                Log.d(TAG, "Copied proot -> $binDir/proot (${destProot.length()} bytes)")
            }
        } else {
            Log.w(TAG, "proot NOT found in nativeLibDir: $nativeProot")
        }

        // Copy loader -> libexec/proot/loader (proot resolves it relative to its own path)
        val nativeLoader = File("$nativeLibDir/$PROOT_LOADER_LIB")
        val destLoader = File("$libexecDir/loader")
        if (nativeLoader.exists() && (!destLoader.exists() || destLoader.length() != nativeLoader.length())) {
            nativeLoader.copyTo(destLoader, overwrite = true)
            destLoader.setExecutable(true, false)
            destLoader.setReadable(true, false)
            Log.d(TAG, "Copied loader -> $libexecDir/loader")
        }

        // Copy loader32 -> libexec/proot/loader32 (arm64 only)
        val nativeLoader32 = File("$nativeLibDir/$PROOT_LOADER32_LIB")
        val destLoader32 = File("$libexecDir/loader32")
        if (nativeLoader32.exists() && (!destLoader32.exists() || destLoader32.length() != nativeLoader32.length())) {
            nativeLoader32.copyTo(destLoader32, overwrite = true)
            destLoader32.setExecutable(true, false)
            destLoader32.setReadable(true, false)
            Log.d(TAG, "Copied loader32 -> $libexecDir/loader32")
        }

        prootBin = "$binDir/proot"
    }

    private fun setupLibtalloc() {
        val source = File("$nativeLibDir/$LIBTALLOC_LIB")
        val target = File("$libDir/libtalloc.so.2")
        if (source.exists() && !target.exists()) {
            source.copyTo(target)
            Log.d(TAG, "Created libtalloc.so.2 SONAME symlink")
        }
    }

    fun isInitialized(): Boolean = initialized
    fun isRootfsReady(): Boolean = File("$rootfsDir/bin/bash").exists()
    fun getRootfsDir(): String = rootfsDir
    fun getIdeDir(): String = hostIdeDir
    fun getProotBin(): String = prootBin

    // ============================================================
    // Proot Command Builders
    // ============================================================

    /**
     * Build proot command for single-shot non-interactive commands (apt, pip, npm, git, etc.)
     * Matches Termux proot-distro's run_proot_cmd() behavior.
     */
    fun buildInstallCommand(
        command: String,
        cwd: String? = null,
        env: Map<String, String>? = null
    ): Array<String> {
        val kernelRelease = getKernelRelease()
        val args = mutableListOf(
            prootBin,
            "--change-id=0:0",
            "--sysvipc",
            "--kernel-release=$kernelRelease",
            "--link2symlink", "-L",
            "--kill-on-exit",
            "--rootfs=$rootfsDir",
        )

        // CWD
        args.add("--cwd=${cwd ?: "/root"}")

        // Bind mounts matching proot-distro
        args.addAll(listOf(
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/sys",
        ))

        // Fake /proc entries for Android compatibility
        val procFakes = setupProcFakes()
        for ((guest, host) in procFakes) {
            args.add("--bind=$host:$guest")
        }

        // DNS
        setupResolvConf()
        args.add("--bind=$libDir/resolv.conf:/etc/resolv.conf")

        // Storage binding
        args.add("--bind=$hostIdeDir:/root/phoneide")

        // Clean environment via env -i
        val envList = mutableListOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=dumb",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH=$libDir",
            "TMPDIR=/tmp",
        )

        // Add extra env vars
        env?.forEach { (k, v) -> envList.add("$k=$v") }

        envList.addAll(listOf("sh", "-c", command))

        return (args + envList).toTypedArray()
    }

    /**
     * Build proot command for interactive login shell sessions.
     * Matches Termux proot-distro's command_login() behavior.
     */
    fun buildLoginCommand(
        columns: Int = 80,
        rows: Int = 24,
        extraArgs: List<String>? = null
    ): Array<String> {
        val kernelRelease = getKernelRelease()
        val args = mutableListOf(
            prootBin,
            "--change-id=0:0",
            "--sysvipc",
            "--kernel-release=$kernelRelease",
            "--link2symlink", "-L",
            "--kill-on-exit",
            "--rootfs=$rootfsDir",
            "--cwd=/root",
        )

        // Bind mounts
        args.addAll(listOf(
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/sys",
        ))

        // Fake /proc entries
        val procFakes = setupProcFakes()
        for ((guest, host) in procFakes) {
            args.add("--bind=$host:$guest")
        }

        // DNS
        setupResolvConf()
        args.add("--bind=$libDir/resolv.conf:/etc/resolv.conf")

        // Storage
        args.add("--bind=$hostIdeDir:/root/phoneide")

        // Clean environment
        val envList = mutableListOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "TERM=xterm-256color",
            "COLUMNS=$columns", "LINES=$rows",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH=$libDir",
            "TMPDIR=/tmp",
        )

        // Login shell
        envList.addAll(extraArgs ?: listOf("/bin/bash", "-l"))

        return (args + envList).toTypedArray()
    }

    // ============================================================
    // Process Execution
    // ============================================================

    /**
     * Run a single-shot command and return output.
     * @param timeoutMs max wait time in milliseconds
     */
    fun runInProot(
        command: String,
        cwd: String? = null,
        env: Map<String, String>? = null,
        timeoutMs: Long = 300_000
    ): CommandResult {
        initialize()
        val cmdArray = buildInstallCommand(command, cwd, env)
        return executeCommand(cmdArray, timeoutMs)
    }

    /**
     * Start a long-lived process (e.g. Flask server) via proot.
     * Returns the Process object so caller can manage its lifecycle.
     */
    fun startProotProcess(
        command: String,
        cwd: String? = null,
        env: Map<String, String>? = null
    ): Process? {
        initialize()
        val cmdArray = buildInstallCommand(command, cwd, env)
        return try {
            Log.d(TAG, "Starting proot process: ${cmdArray.take(5).joinToString(" ")} ...")
            val pb = ProcessBuilder(*cmdArray)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = libDir
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proot process", e)
            null
        }
    }

    /**
     * Start an interactive login shell via proot for terminal use.
     */
    fun startLoginShell(columns: Int = 80, rows: Int = 24): Process? {
        initialize()
        val cmdArray = buildLoginCommand(columns, rows)
        return try {
            Log.d(TAG, "Starting login shell")
            val pb = ProcessBuilder(*cmdArray)
            pb.redirectErrorStream(false)
            pb.environment()["LD_LIBRARY_PATH"] = libDir
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start login shell", e)
            null
        }
    }

    // ============================================================
    // Command Execution Helper
    // ============================================================

    private fun executeCommand(cmdArray: Array<String>, timeoutMs: Long): CommandResult {
        val startTime = System.currentTimeMillis()
        try {
            val pb = ProcessBuilder(*cmdArray)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = libDir

            val process = pb.start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
                notifyOutput(line ?: "")
            }
            reader.close()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    success = false,
                    stdout = output.toString(),
                    stderr = "Command timed out after ${timeoutMs}ms",
                    exitCode = -1,
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                return CommandResult(
                    success = process.exitValue() == 0,
                    stdout = output.toString(),
                    stderr = "",
                    exitCode = process.exitValue(),
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            return CommandResult(
                success = false,
                stdout = "",
                stderr = e.message ?: "Unknown error",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ============================================================
    // Android Compatibility Helpers
    // ============================================================

    private fun getKernelRelease(): String {
        return try {
            val release = File("/proc/version").readText().trim()
            // Extract kernel version (e.g. "5.10.101-android13-4-...")
            val match = Regex("Linux version (\\S+)").find(release)
            match?.groupValues?.get(1) ?: "5.10.0"
        } catch (e: Exception) {
            "5.10.0"
        }
    }

    private fun setupProcFakes(): Map<String, String> {
        val fakeDir = "$libDir/proc-fakes"
        File(fakeDir).mkdirs()

        val fakes = mutableMapOf<String, String>()

        // /proc/loadavg - fake minimal load
        writeFile("$fakeDir/loadavg", "0.00 0.00 0.00 1/100 1\n")
        fakes["/proc/loadavg"] = "$fakeDir/loadavg"

        // /proc/stat - fake minimal cpu stat
        writeFile("$fakeDir/stat", "cpu  0 0 0 0 0 0 0 0 0 0\nintr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n")
        fakes["/proc/stat"] = "$fakeDir/stat"

        // /proc/uptime
        val uptime = (System.currentTimeMillis() / 1000).toString()
        writeFile("$fakeDir/uptime", "$uptime $uptime\n")
        fakes["/proc/uptime"] = "$fakeDir/uptime"

        // /proc/version - fake kernel version
        writeFile("$fakeDir/version", "Linux version 5.10.0 (proot) (gcc) #1 SMP\n")
        fakes["/proc/version"] = "$fakeDir/version"

        // /proc/meminfo - fake minimal memory info
        writeFile("$fakeDir/meminfo", "MemTotal:        4096000 kB\nMemFree:         2048000 kB\nMemAvailable:    3072000 kB\nBuffers:           65536 kB\nCached:          1024000 kB\nSwapTotal:       1048576 kB\nSwapFree:        1048576 kB\n")
        fakes["/proc/meminfo"] = "$fakeDir/meminfo"

        return fakes
    }

    private fun setupResolvConf() {
        val resolvFile = File("$libDir/resolv.conf")
        if (!resolvFile.exists()) {
            writeFile("$libDir/resolv.conf",
                "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n"
            )
        }
    }

    private fun writeFile(path: String, content: String) {
        try {
            File(path).writeText(content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $path: ${e.message}")
        }
    }

    // ============================================================
    // Output Listeners
    // ============================================================

    fun addOutputListener(listener: (String) -> Unit) {
        outputListeners.add(listener)
    }

    fun removeOutputListener(listener: (String) -> Unit) {
        outputListeners.remove(listener)
    }

    private fun notifyOutput(line: String) {
        for (listener in outputListeners) {
            try { listener(line) } catch (e: Exception) { }
        }
    }

    // ============================================================
    // Data Classes
    // ============================================================

    data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long
    )
}
