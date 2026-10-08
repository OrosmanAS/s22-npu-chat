package com.npuchat.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.npuchat.BuildConfig
import com.npuchat.engine.NpuEnv
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Evidence gathering: previous process deaths, own-uid logcat, and a shareable log bundle. */
object Diagnostics {
    private const val TAG = "Diag"

    /**
     * Why the previous app processes ended. A native crash in QNN/LiteRT shows up here as
     * REASON_CRASH_NATIVE with a tombstone; we pull the readable strings out of it.
     */
    fun previousExits(context: Context, max: Int = 3): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val exits = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, max)
        } catch (e: Exception) {
            return "exit reasons unavailable: ${e.message}"
        }
        if (exits.isEmpty()) return "no previous exits recorded"
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        return exits.joinToString("\n\n") { x ->
            buildString {
                append("${fmt.format(Date(x.timestamp))} pid=${x.pid} process=${x.processName} ")
                append("reason=${reasonName(x.reason)} status=${x.status} importance=${x.importance} ")
                append("pss=${x.pss / 1024}MB rss=${x.rss / 1024}MB")
                x.description?.let { append("\n  description: $it") }
                if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == ApplicationExitInfo.REASON_ANR) {
                    traceStrings(x)?.let { append("\n  trace:\n").append(it.prependIndent("    ")) }
                }
            }
        }
    }

    private fun traceStrings(x: ApplicationExitInfo): String? = try {
        x.traceInputStream?.use { input ->
            val bytes = input.readBytes()
            if (x.reason == ApplicationExitInfo.REASON_ANR) {
                String(bytes).lineSequence().take(80).joinToString("\n")
            } else {
                // Tombstones are protobuf; printable runs are enough to see the signal and frames.
                Regex("[\\x20-\\x7e]{6,}").findAll(String(bytes, Charsets.ISO_8859_1))
                    .map { it.value }.take(200).joinToString("\n")
            }
        }
    } catch (e: Exception) {
        "trace unreadable: ${e.message}"
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($r)"
    }

    /**
     * logcat for this app's uid (apps may read their own entries without READ_LOGS). This includes
     * native LiteRT/QNN logs and lines from earlier processes, e.g. one that crashed in init.
     */
    fun logcat(maxLines: Int = 8000): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", maxLines.toString())
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        out
    } catch (e: Exception) {
        "logcat failed: ${e.message}"
    }

    /** Writes a single text bundle to cache/export and returns it. */
    fun buildBundle(context: Context, initReport: String): File {
        AppLog.i(TAG, "building log bundle")
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "npuchat-logs-$stamp.txt")
        out.bufferedWriter().use { w ->
            fun section(title: String, body: String) {
                w.write("\n===== $title =====\n")
                w.write(body)
                w.write("\n")
            }
            w.write("NPU Chat ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})\n")
            section("Device", NpuEnv.deviceReport(context))
            section("Native libs (build manifest)", NpuEnv.nativeManifest(context))
            section("nativeLibraryDir", NpuEnv.listNativeLibDir(context))
            section("Environment", NpuEnv.envReport())
            section("Vendor DSP files", NpuEnv.vendorReport())
            section("Init steps", initReport)
            section("Previous process exits", previousExits(context, 5))
            for (f in AppLog.files()) section("App log ${f.name}", f.readText())
            section("logcat (own uid)", logcat())
        }
        AppLog.i(TAG, "bundle ${out.name} ${out.length()} bytes")
        return out
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share logs") }
    }
}
