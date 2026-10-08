package com.npuchat.engine

import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * What an app can measure while the NPU generates. There is no Hexagon/cDSP utilisation counter
 * an app can read: on the S22+ every cDSP sysfs node and the block-device I/O counters are
 * SELinux-denied (checked over adb with run-as). Readable: our own CPU time and disk reads, core
 * clocks, GPU busy %, storage fill, and the thermal zones, including the NSP (= Hexagon NPU)
 * sensors "nspss-0..2".
 */
data class SysSnapshot(
    /** This process's CPU time as % of one core (top-style: 200% = two cores busy). */
    val appCpuPercent: Double?,
    val bigCoreMhz: Int?,
    val primeCoreMhz: Int?,
    val gpuBusyPercent: Int?,
    val cpusAllowed: String,
    /** Hottest NSP (NPU subsystem) thermal sensor, °C. */
    val npuTempC: Double?,
    val batteryTempC: Double?,
    /** Internal storage (/data) used, %. */
    val storageUsedPercent: Int?,
    /** Bytes this process actually read from storage per second, MB/s. */
    val appDiskReadMBps: Double?,
    /** System RAM in use, % ((MemTotal - MemAvailable) / MemTotal). */
    val ramUsedPercent: Int?,
    /** This process's resident memory (VmRSS), GB. Includes mapped model pages. */
    val appRamGb: Double?,
)

object SystemStats {
    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private var lastTicks = -1L
    private var lastReadBytes = -1L
    private var lastNs = 0L

    private val zones: List<Pair<String, File>> by lazy {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().mapNotNull { z ->
            runCatching { File(z, "type").readText().trim() to File(z, "temp") }.getOrNull()
        }
    }
    private val npuZones by lazy { zones.filter { it.first.startsWith("nspss") }.map { it.second } }
    private val batteryZone by lazy { zones.firstOrNull { it.first == "battery" }?.second }

    @Synchronized
    fun sample(dataDir: File): SysSnapshot {
        val now = System.nanoTime()
        val secs = (now - lastNs) / 1e9
        val ticks = processTicks()
        val readBytes = processReadBytes()
        val cpu = if (ticks != null && lastTicks >= 0 && secs > 0) (ticks - lastTicks).toDouble() / clkTck / secs * 100.0 else null
        val disk = if (readBytes != null && lastReadBytes >= 0 && secs > 0) (readBytes - lastReadBytes) / 1e6 / secs else null
        if (ticks != null) lastTicks = ticks
        if (readBytes != null) lastReadBytes = readBytes
        lastNs = now
        return SysSnapshot(
            appCpuPercent = cpu,
            // Snapdragon 8 Gen 1: cpu0-3 Cortex-A510, cpu4-6 Cortex-A710, cpu7 Cortex-X2.
            bigCoreMhz = readLong("/sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq")?.div(1000)?.toInt(),
            primeCoreMhz = readLong("/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq")?.div(1000)?.toInt(),
            gpuBusyPercent = readLong("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.toInt(),
            cpusAllowed = NpuEnv.cpusAllowed(),
            npuTempC = npuZones.mapNotNull { milliC(it) }.maxOrNull(),
            batteryTempC = batteryZone?.let(::milliC),
            storageUsedPercent = runCatching {
                val st = StatFs(dataDir.path)
                (100 - st.availableBytes * 100 / st.totalBytes).toInt()
            }.getOrNull(),
            appDiskReadMBps = disk,
            ramUsedPercent = ramUsedPercent(),
            appRamGb = kbField("/proc/self/status", "VmRSS")?.let { it / 1e6 },
        )
    }

    private fun ramUsedPercent(): Int? {
        val total = kbField("/proc/meminfo", "MemTotal") ?: return null
        val avail = kbField("/proc/meminfo", "MemAvailable") ?: return null
        return ((total - avail) * 100 / total).toInt()
    }

    /** A "Name:   1234 kB" field from a /proc file, in kB. */
    private fun kbField(path: String, name: String): Long? = runCatching {
        File(path).readLines().first { it.startsWith("$name:") }.substringAfter(':').trim().split(' ')[0].toLong()
    }.getOrNull()

    /** utime + stime from /proc/self/stat (fields 14 and 15). */
    private fun processTicks(): Long? = runCatching {
        val fields = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')
        fields[11].toLong() + fields[12].toLong()
    }.getOrNull()

    /** "read_bytes" from /proc/self/io: bytes really fetched from storage (not page-cache hits). */
    private fun processReadBytes(): Long? = runCatching {
        File("/proc/self/io").readLines().first { it.startsWith("read_bytes") }.substringAfter(':').trim().toLong()
    }.getOrNull()

    /** Thermal zones report millidegrees; values far outside 0..150 °C are unused sensors. */
    private fun milliC(f: File): Double? =
        readLong(f.path)?.let { it / 1000.0 }?.takeIf { it in 0.0..150.0 }

    private fun readLong(path: String): Long? = runCatching {
        File(path).readText().trim().takeWhile { it.isDigit() || it == '-' }.toLong()
    }.getOrNull()
}
