package com.npuchat.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Level(val priority: Int, val letter: Char) {
    VERBOSE(Log.VERBOSE, 'V'),
    DEBUG(Log.DEBUG, 'D'),
    INFO(Log.INFO, 'I'),
    WARN(Log.WARN, 'W'),
    ERROR(Log.ERROR, 'E'),
}

data class LogLine(val timeMs: Long, val level: Level, val tag: String, val message: String) {
    fun format(): String = "${TIME_FORMAT.get()!!.format(Date(timeMs))} ${level.letter}/$tag: $message"

    private companion object {
        val TIME_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    }
}

/**
 * App-wide logger. Every line goes to logcat (tag "NpuChat.<area>"), to an in-memory ring buffer
 * for the in-app viewer, and to filesDir/logs/app.log, flushed per line so the file survives a
 * native crash during NPU init. [minLevel] makes it quieter without removing any log calls.
 */
object AppLog {
    private const val TAG_PREFIX = "NpuChat."
    private const val RING_SIZE = 4000
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    @Volatile var minLevel: Level = Level.DEBUG

    private val lock = Any()
    private val ring = ArrayDeque<LogLine>(RING_SIZE)
    private var writer: FileWriter? = null
    private var logFile: File? = null

    private val _version = MutableStateFlow(0L)
    /** Bumps on every new line so the viewer can recompose. */
    val version: StateFlow<Long> = _version

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        synchronized(lock) {
            logFile = File(dir, "app.log")
            rotateIfNeeded()
            writer = FileWriter(logFile, true)
        }
    }

    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    fun log(level: Level, tag: String, msg: String, t: Throwable? = null) {
        if (level.priority < minLevel.priority) return
        val full = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        Log.println(level.priority, TAG_PREFIX + tag, full)
        val line = LogLine(System.currentTimeMillis(), level, tag, full)
        synchronized(lock) {
            if (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(line)
            try {
                writer?.apply {
                    write(line.format())
                    write("\n")
                    flush()
                }
                if ((logFile?.length() ?: 0) > MAX_FILE_BYTES) {
                    writer?.close()
                    rotateIfNeeded()
                    writer = FileWriter(logFile, true)
                }
            } catch (e: Exception) {
                Log.e(TAG_PREFIX + "Log", "log file write failed", e)
            }
        }
        _version.value = _version.value + 1
    }

    fun snapshot(): List<LogLine> = synchronized(lock) { ring.toList() }

    fun clearBuffer() {
        synchronized(lock) { ring.clear() }
        _version.value = _version.value + 1
    }

    /** Current and previous log files, oldest first. */
    fun files(): List<File> = synchronized(lock) {
        val f = logFile ?: return emptyList()
        listOf(File(f.parentFile, "app.log.1"), f).filter { it.exists() }
    }

    private fun rotateIfNeeded() {
        val f = logFile ?: return
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            val old = File(f.parentFile, "app.log.1")
            old.delete()
            f.renameTo(old)
        }
    }
}
