package com.example.heatradar.core.monitor

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryInfo(
    val memTotalKb: Long = 0L,
    val memFreeKb: Long = 0L,
    val memAvailableKb: Long = 0L,
    val cachedKb: Long = 0L,
    val buffersKb: Long = 0L,
    val swapTotalKb: Long = 0L,
    val swapFreeKb: Long = 0L,
    val swapCachedKb: Long = 0L
) {
    val usedKb: Long get() = (memTotalKb - memFreeKb - cachedKb - buffersKb).coerceAtLeast(0)
    val usedPercent: Float get() = if (memTotalKb > 0) usedKb.toFloat() / memTotalKb * 100f else 0f
    val usedMb: Long get() = usedKb / 1024
    val totalMb: Long get() = memTotalKb / 1024
    val availableMb: Long get() = memAvailableKb / 1024
}

data class GpuInfo(
    val gpuBusyPercent: Float = 0f,
    val gpuClkHz: Long = 0L
) {
    val gpuClkMhz: Long get() = gpuClkHz / 1000
}

data class PowerInfo(
    val currentUa: Long = 0L,      // micro-amps
    val voltageUv: Long = 0L,      // micro-volts
    val powerMw: Long = 0L,        // milli-watts
    val status: String = "",       // Charging/Discharging/Full
    val health: String = "",       // Good/Overheat/etc
    val capacity: Int = 0          // 0-100%
) {
    val currentMa: Long get() = currentUa / 1000
    val voltageV: Float get() = voltageUv / 1_000_000f
    val isCharging: Boolean get() = status.equals("Charging", ignoreCase = true)
    val isDischarging: Boolean get() = status.equals("Discharging", ignoreCase = true)
}

data class NetworkInfo(
    val downBps: Long = 0L,
    val upBps: Long = 0L
) {
    val downKbps: Float get() = downBps / 1024f
    val upKbps: Float get() = upBps / 1024f
    val downMbps: Float get() = downBps / (1024f * 1024f)
    val upMbps: Float get() = upBps / (1024f * 1024f)

    val downDisplay: String get() = when {
        downMbps >= 1f -> "%.1f MB/s".format(downMbps)
        downKbps >= 1f -> "%.0f KB/s".format(downKbps)
        else -> "%d B/s".format(downBps)
    }
    val upDisplay: String get() = when {
        upMbps >= 1f -> "%.1f MB/s".format(upMbps)
        upKbps >= 1f -> "%.0f KB/s".format(upKbps)
        else -> "%d B/s".format(upBps)
    }
}

data class ThermalZone(
    val type: String,
    val tempCelsius: Int
)

data class SystemMetricsSnapshot(
    val totalCpuPercent: Float = 0f,
    val userCpuPercent: Float = 0f,
    val sysCpuPercent: Float = 0f,
    val idleCpuPercent: Float = 100f,
    val cpuCoreCount: Int = 1,
    val cpuFreqsKhz: List<Long> = emptyList(),
    val temps: List<ThermalZone> = emptyList(),
    val memory: MemoryInfo = MemoryInfo(),
    val gpu: GpuInfo = GpuInfo(),
    val power: PowerInfo = PowerInfo(),
    val network: NetworkInfo = NetworkInfo(),
    val timestamp: Long = 0L,
    val available: Boolean = false
) {
    val maxCpuTempCelsius: Int
        get() = temps
            .filter { it.type.contains("cpu", ignoreCase = true) || it.type.contains("cluster", ignoreCase = true) }
            .maxOfOrNull { it.tempCelsius }
            ?: temps.maxOfOrNull { it.tempCelsius }
            ?: 0

    val batteryTempCelsius: Int
        get() = temps.firstOrNull { it.type.equals("battery", ignoreCase = true) }?.tempCelsius ?: 0

    val avgCpuFreqMhz: Long
        get() = if (cpuFreqsKhz.isNotEmpty()) cpuFreqsKhz.average().toLong() / 1000 else 0L

    val maxCpuFreqMhz: Long
        get() = (cpuFreqsKhz.maxOrNull() ?: 0L) / 1000
}

@Singleton
class SystemMetricsHolder @Inject constructor() {

    companion object {
        private const val TAG = "SystemMetricsHolder"
    }

    @Volatile
    private var snapshot = SystemMetricsSnapshot(
        cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        timestamp = System.currentTimeMillis()
    )

    private var lastProcCpuTotal: Long = 0L
    private var lastProcCpuIdle: Long = 0L
    private var lastProcCpuTime: Long = 0L
    private var procSampleCount = 0
    private var procCpuWarned = false

    fun getSnapshot(): SystemMetricsSnapshot = snapshot

    fun getTotalCpuPercent(): Float = snapshot.totalCpuPercent

    fun getCpuCoreCount(): Int = snapshot.cpuCoreCount

    fun update(s: SystemMetricsSnapshot) {
        snapshot = s
    }

    fun updateFromDaemon(
        totalCpu: Float,
        cores: Int,
        cpuFreqsKhz: List<Long>,
        temps: List<ThermalZone>,
        memory: MemoryInfo,
        gpu: GpuInfo,
        power: PowerInfo = PowerInfo(),
        network: NetworkInfo = NetworkInfo()
    ) {
        val now = System.currentTimeMillis()
        snapshot = SystemMetricsSnapshot(
            totalCpuPercent = totalCpu.coerceIn(0f, 100f),
            idleCpuPercent = 100f - totalCpu.coerceIn(0f, 100f),
            cpuCoreCount = cores.coerceAtLeast(1),
            cpuFreqsKhz = cpuFreqsKhz,
            temps = temps,
            memory = memory,
            gpu = gpu,
            power = power,
            network = network,
            timestamp = now,
            available = true
        )
    }

    @Synchronized
    fun sampleCpuFromProc() {
        try {
            val procStatFile = File("/proc/stat")
            if (!procStatFile.exists()) return

            val content = procStatFile.readText()
            val cpuLine = content.lines().firstOrNull { it.startsWith("cpu ") } ?: return

            val parts = cpuLine.trim().split(Regex("\\s+"))
            if (parts.size < 8) return

            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 7) return

            val total = values.sum()
            val idle = values[3] + (if (values.size > 4) values[4] else 0)
            val now = System.currentTimeMillis()

            procSampleCount++

            if (total <= 0L) return

            if (lastProcCpuTotal == 0L || lastProcCpuIdle == 0L) {
                lastProcCpuTotal = total
                lastProcCpuIdle = idle
                lastProcCpuTime = now
                return
            }

            val totalDelta = total - lastProcCpuTotal
            val idleDelta = idle - lastProcCpuIdle

            if (totalDelta > 0 && now - lastProcCpuTime > 300) {
                val usagePercent = ((totalDelta - idleDelta).toFloat() / totalDelta * 100f)
                    .coerceIn(0f, 100f)

                if (usagePercent > 0.1f || procSampleCount < 5) {
                    if (!procCpuWarned) {
                        Log.i(TAG, "sampleCpuFromProc: reading /proc/stat works! usage=%.1f%%".format(usagePercent))
                        procCpuWarned = true
                    }
                    val mem = sampleMemoryFromProc()
                    snapshot = snapshot.copy(
                        totalCpuPercent = usagePercent,
                        idleCpuPercent = 100f - usagePercent,
                        memory = mem,
                        timestamp = now,
                        available = true
                    )
                } else if (procSampleCount < 10) {
                    Log.d(TAG, "sampleCpuFromProc: /proc/stat gave 0% (selinux restricted on this device)")
                }
            }

            lastProcCpuTotal = total
            lastProcCpuIdle = idle
            lastProcCpuTime = now
        } catch (e: Exception) {
            if (procSampleCount < 3) {
                Log.w(TAG, "sampleCpuFromProc failed: ${e.message}")
            }
        }
    }

    fun sampleMemoryFromProc(): MemoryInfo {
        return try {
            val meminfoFile = File("/proc/meminfo")
            if (!meminfoFile.exists()) return snapshot.memory

            val values = mutableMapOf<String, Long>()
            meminfoFile.readLines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val key = parts[0].removeSuffix(":")
                    val v = parts[1].toLongOrNull()
                    if (v != null) values[key] = v
                }
            }
            MemoryInfo(
                memTotalKb = values["MemTotal"] ?: 0L,
                memFreeKb = values["MemFree"] ?: 0L,
                memAvailableKb = values["MemAvailable"] ?: 0L,
                cachedKb = values["Cached"] ?: 0L,
                buffersKb = values["Buffers"] ?: 0L,
                swapTotalKb = values["SwapTotal"] ?: 0L,
                swapFreeKb = values["SwapFree"] ?: 0L,
                swapCachedKb = values["SwapCached"] ?: 0L
            )
        } catch (e: Exception) {
            snapshot.memory
        }
    }
}
