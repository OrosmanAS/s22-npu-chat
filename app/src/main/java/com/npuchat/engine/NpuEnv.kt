package com.npuchat.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.system.Os
import com.npuchat.log.AppLog
import java.io.File

/**
 * Process environment for the QNN HTP backend, plus read-only diagnostics used as init evidence.
 *
 * [configure] must run in Application.onCreate before anything loads a QNN library: the FastRPC
 * client reads ADSP_LIBRARY_PATH when the HTP stub first opens a DSP session, and it must find
 * our QAIRT 2.50 v69 skel in nativeLibraryDir before any vendor copy. Error 1002 at context
 * creation usually means the DSP could not find or load the skel.
 */
object NpuEnv {
    private const val TAG = "Env"

    val REQUIRED_LIBS = listOf(
        "liblitertlm_jni.so",
        "libLiteRtDispatch_Qualcomm.so",
        "libQnnHtp.so",
        "libQnnSystem.so",
        "libQnnHtpV69Stub.so",
        "libQnnHtpV69Skel.so",
    )

    // Order matters: ours first, then the usual vendor/system DSP library locations.
    private val VENDOR_DSP_PATHS = listOf(
        "/vendor/lib/rfsa/adsp",
        "/vendor/lib/rfsa/dsp/sdk",
        "/vendor/dsp/cdsp",
        "/vendor/dsp",
        "/system/lib/rfsa/adsp",
        "/system/vendor/lib/rfsa/adsp",
        "/dsp",
    )

    private val VENDOR_LIB_PATHS = listOf("/vendor/lib64", "/system/lib64")

    fun nativeLibDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    fun configure(context: Context) {
        val libDir = nativeLibDir(context)
        // ADSP_LIBRARY_PATH is ';'-separated (FastRPC convention), LD_LIBRARY_PATH is ':'-separated.
        val adsp = (listOf(libDir) + VENDOR_DSP_PATHS).joinToString(";")
        val ld = (listOf(libDir) + VENDOR_LIB_PATHS).joinToString(":")
        setenv("ADSP_LIBRARY_PATH", adsp)
        setenv("LD_LIBRARY_PATH", ld)
    }

    private fun setenv(name: String, value: String) {
        try {
            Os.setenv(name, value, true)
            AppLog.i(TAG, "setenv $name=$value")
        } catch (e: Exception) {
            AppLog.e(TAG, "setenv $name failed", e)
        }
    }

    fun envReport(): String = listOf("ADSP_LIBRARY_PATH", "LD_LIBRARY_PATH")
        .joinToString("\n") { "$it=${Os.getenv(it) ?: "<unset>"}" }

    fun deviceReport(context: Context): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), board=${Build.BOARD}, hardware=${Build.HARDWARE}")
        appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        appendLine("Kernel: ${System.getProperty("os.version")}, ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine(memoryReport(context))
        append("CPU affinity: ${cpusAllowed()}")
    }

    fun isSm8450(): Boolean = Build.SOC_MODEL.equals("SM8450", ignoreCase = true)

    fun memoryReport(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "RAM: avail %.2f GB / total %.2f GB, lowMemory=%s, threshold %.2f GB".format(
            mi.availMem / 1e9, mi.totalMem / 1e9, mi.lowMemory, mi.threshold / 1e9,
        )
    }

    /** Cpus_allowed_list of this process; Samsung may pin apps to the little cores (0-3). */
    fun cpusAllowed(): String = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list") }
            ?.substringAfter(':')?.trim() ?: "?"
    } catch (e: Exception) {
        "? (${e.message})"
    }

    data class LibCheck(val name: String, val present: Boolean, val size: Long)

    fun checkLibs(context: Context): List<LibCheck> {
        val dir = File(nativeLibDir(context))
        return REQUIRED_LIBS.map { name ->
            val f = File(dir, name)
            LibCheck(name, f.exists(), if (f.exists()) f.length() else -1)
        }
    }

    fun listNativeLibDir(context: Context): String {
        val dir = File(nativeLibDir(context))
        val files = dir.listFiles()?.sortedBy { it.name } ?: return "$dir: <unreadable>"
        return "$dir:\n" + files.joinToString("\n") { "  ${it.name} ${it.length()}" }
    }

    /** Vendor DSP/QNN files on the phone. We do not use them; listed only as evidence. */
    fun vendorReport(): String = buildString {
        for (p in VENDOR_LIB_PATHS) {
            val f = File(p, "libcdsprpc.so")
            appendLine("${f.path}: ${if (f.exists()) "present" else "missing"}")
        }
        for (p in VENDOR_DSP_PATHS) {
            val d = File(p)
            if (!d.isDirectory) continue
            val names = d.list()?.filter { it.contains("Qnn", true) || it.contains("skel", true) }?.sorted()
            appendLine("$p: ${names?.size ?: "unreadable"} QNN/skel files${names?.take(12)?.let { " $it" } ?: ""}")
        }
    }.trimEnd()

    /** Build provenance written by CI into assets/native_libs.txt (versions + SHA-256s). */
    fun nativeManifest(context: Context): String = try {
        context.assets.open("native_libs.txt").bufferedReader().readText().trim()
    } catch (e: Exception) {
        "native_libs.txt not found (APK not built by CI?)"
    }

    /** dlopen a library and log the outcome. Returns null on success or the error text. */
    fun tryLoad(label: String, load: () -> Unit): String? {
        val t0 = System.nanoTime()
        return try {
            load()
            AppLog.i(TAG, "dlopen OK: $label (${(System.nanoTime() - t0) / 1_000_000} ms)")
            null
        } catch (e: Throwable) {
            AppLog.e(TAG, "dlopen FAILED: $label: ${e.message}")
            e.message ?: e.toString()
        }
    }
}
