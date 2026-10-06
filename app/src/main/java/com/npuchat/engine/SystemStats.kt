package com.npuchat.engine

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * What an app can measure while the NPU generates. There is no Hexagon/cDSP utilisation counter
 * an app can read: on the S22+ every cDSP sysfs node is SELinux-denied (checked over adb with
 * run-as). So this reports the CPU side (our process, core clocks) and the GPU, which should
 * stay near 0% while the model runs on the NPU.
 */
data class SysSnapshot(
    /** This process's CPU time as % of one core (top-style: 200% = two cores busy). */
    val appCpuPercent: Double?,
    val bigCoreMhz: Int?,
    val primeCoreMhz: Int?,
    val gpuBusyPercent: Int?,
    val cpusAllowed: String,
)

object SystemStats {
    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private var lastTicks = -1L
    private var lastNs = 0L

    @Synchronized
    fun sample(): SysSnapshot {
        val now = System.nanoTime()
        val ticks = processTicks()
        val cpu = if (ticks != null && lastTicks >= 0 && now > lastNs) {
            (ticks - lastTicks).toDouble() / clkTck / ((now - lastNs) / 1e9) * 100.0
        } else null
        if (ticks != null) { lastTicks = ticks; lastNs = now }
        return SysSnapshot(
            appCpuPercent = cpu,
            // Snapdragon 8 Gen 1: cpu0-3 Cortex-A510, cpu4-6 Cortex-A710, cpu7 Cortex-X2.
            bigCoreMhz = readInt("/sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq")?.div(1000),
            primeCoreMhz = readInt("/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq")?.div(1000),
            gpuBusyPercent = readInt("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"),
            cpusAllowed = NpuEnv.cpusAllowed(),
        )
    }

    /** utime + stime from /proc/self/stat (fields 14 and 15). */
    private fun processTicks(): Long? = runCatching {
        val fields = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')
        fields[11].toLong() + fields[12].toLong()
    }.getOrNull()

    private fun readInt(path: String): Int? = runCatching {
        File(path).readText().trim().takeWhile { it.isDigit() }.toInt()
    }.getOrNull()
}
