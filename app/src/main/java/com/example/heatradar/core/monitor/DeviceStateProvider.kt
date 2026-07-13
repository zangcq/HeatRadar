package com.example.heatradar.core.monitor

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceState(
    val totalCpuFreqMhz: Long,
    val maxCpuFreqMhz: Long,
    val cpuUsagePercent: Float,
    val temperatureCelsius: Float,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val usedMemoryBytes: Long,
    val memoryUsagePercent: Float,
    val cpuFreqsMhz: List<Long> = emptyList(),
    val gpuBusyPercent: Float = 0f,
    val gpuFreqMhz: Long = 0L,
    val batteryTempCelsius: Float = 0f,
    val allTemps: List<ThermalZone> = emptyList(),
    val power: PowerInfo = PowerInfo(),
    val fps: Float = 0f
) {
    companion object {
        val EMPTY = DeviceState(
            totalCpuFreqMhz = 0L,
            maxCpuFreqMhz = 0L,
            cpuUsagePercent = 0f,
            temperatureCelsius = 0f,
            totalMemoryBytes = 0L,
            availableMemoryBytes = 0L,
            usedMemoryBytes = 0L,
            memoryUsagePercent = 0f
        )
    }
}

@Singleton
class DeviceStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metricsHolder: SystemMetricsHolder
) {

    companion object {
        private const val TAG = "DeviceStateProvider"
        private const val FREQ_CACHE_TTL_MS = 10_000L
        private const val TEMP_CACHE_TTL_MS = 5_000L
        private const val DAEMON_DATA_TTL_MS = 5_000L
    }

    private var cachedMaxFreq: Long? = null
    private var cachedCpuCount: Int? = null
    private var lastFreqReadTime: Long = 0L
    private var cachedAvgFreq: Long = 0L
    private var lastTempReadTime: Long = 0L
    private var cachedTemp: Float = 0f

    private val am by lazy { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }

    fun getDeviceState(): DeviceState {
        val snapshot = metricsHolder.getSnapshot()
        val daemonFresh = snapshot.available &&
                (System.currentTimeMillis() - snapshot.timestamp) < DAEMON_DATA_TTL_MS &&
                snapshot.temps.isNotEmpty()

        return if (daemonFresh) {
            getDeviceStateFromSnapshot(snapshot)
        } else {
            getDeviceStateFallback()
        }
    }

    private fun getDeviceStateFromSnapshot(s: SystemMetricsSnapshot): DeviceState {
        val mem = s.memory
        val totalMemBytes = mem.memTotalKb * 1024L
        val usedMemBytes = mem.usedKb * 1024L
        val availMemBytes = mem.memAvailableKb * 1024L
        val memPercent = mem.usedPercent
        val avgFreqMhz = s.avgCpuFreqMhz
        val maxFreqMhz = maxOf(s.maxCpuFreqMhz, getMaxCpuFreqCached())
        val cpuTemp = s.maxCpuTempCelsius.toFloat()
        val battTemp = s.batteryTempCelsius.toFloat()

        return DeviceState(
            totalCpuFreqMhz = avgFreqMhz,
            maxCpuFreqMhz = maxFreqMhz,
            cpuUsagePercent = s.totalCpuPercent,
            temperatureCelsius = if (cpuTemp > 0f) cpuTemp else s.temps.maxOfOrNull { it.tempCelsius.toFloat() } ?: 0f,
            totalMemoryBytes = totalMemBytes,
            availableMemoryBytes = availMemBytes,
            usedMemoryBytes = usedMemBytes,
            memoryUsagePercent = memPercent,
            cpuFreqsMhz = s.cpuFreqsKhz.map { it / 1000 },
            gpuBusyPercent = s.gpu.gpuBusyPercent,
            gpuFreqMhz = s.gpu.gpuClkMhz,
            batteryTempCelsius = battTemp,
            allTemps = s.temps,
            power = s.power
        )
    }

    private fun getDeviceStateFallback(): DeviceState {
        val cpuFreqs = readCpuFrequenciesCached()
        val maxFreq = getMaxCpuFreq()
        val temp = readTemperatureCached()
        val memory = readMemory()

        val usedMem = memory.first - memory.second
        val memPercent = if (memory.first > 0) {
            usedMem.toFloat() / memory.first * 100f
        } else 0f

        val cpuUsage = metricsHolder.getTotalCpuPercent()

        return DeviceState(
            totalCpuFreqMhz = cpuFreqs,
            maxCpuFreqMhz = maxFreq,
            cpuUsagePercent = cpuUsage,
            temperatureCelsius = temp,
            totalMemoryBytes = memory.first,
            availableMemoryBytes = memory.second,
            usedMemoryBytes = usedMem,
            memoryUsagePercent = memPercent,
            cpuFreqsMhz = if (cpuFreqs > 0) List(getCpuCoreCount()) { cpuFreqs } else emptyList()
        )
    }

    private fun getMaxCpuFreqCached(): Long {
        return cachedMaxFreq ?: getMaxCpuFreq()
    }

    private fun getCpuCoreCount(): Int {
        cachedCpuCount?.let { return it }
        val count = (Runtime.getRuntime().availableProcessors()).coerceAtMost(16)
        cachedCpuCount = count
        return count
    }

    private fun getMaxCpuFreq(): Long {
        cachedMaxFreq?.let { return it }
        return try {
            var max = 0L
            for (i in 0 until getCpuCoreCount()) {
                val path = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val file = File(path)
                if (file.exists()) {
                    val khz = file.readText().trim().toLongOrNull() ?: continue
                    val mhz = khz / 1000
                    if (mhz > max) max = mhz
                }
            }
            cachedMaxFreq = max
            max
        } catch (e: Exception) {
            0L
        }
    }

    private fun readCpuFrequenciesCached(): Long {
        val now = System.currentTimeMillis()
        if (now - lastFreqReadTime < FREQ_CACHE_TTL_MS) {
            return cachedAvgFreq
        }
        lastFreqReadTime = now
        cachedAvgFreq = readCpuFrequencies()
        return cachedAvgFreq
    }

    private fun readCpuFrequencies(): Long {
        return try {
            var total = 0L
            var count = 0
            for (i in 0 until getCpuCoreCount()) {
                val path = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
                val file = File(path)
                if (file.exists()) {
                    val khz = file.readText().trim().toLongOrNull() ?: continue
                    total += khz / 1000
                    count++
                }
            }
            if (count > 0) total / count else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun readTemperatureCached(): Float {
        val now = System.currentTimeMillis()
        if (now - lastTempReadTime < TEMP_CACHE_TTL_MS) {
            return cachedTemp
        }
        lastTempReadTime = now
        cachedTemp = readTemperature()
        return cachedTemp
    }

    private fun readTemperature(): Float {
        return try {
            val paths = (0..11).map { "/sys/class/thermal/thermal_zone$it/temp" } +
                listOf(
                    "/sys/devices/virtual/thermal/thermal_zone0/temp",
                    "/sys/devices/virtual/thermal/thermal_zone1/temp"
                )
            var maxTemp = 0f
            for (path in paths) {
                val file = File(path)
                if (file.exists()) {
                    val text = file.readText().trim()
                    val milliCelsius = text.toFloatOrNull() ?: continue
                    val celsius = milliCelsius / 1000f
                    if (celsius > maxTemp && celsius < 150f && celsius > -50f) {
                        maxTemp = celsius
                    }
                }
            }
            maxTemp
        } catch (e: Exception) {
            0f
        }
    }

    private fun readMemory(): Pair<Long, Long> {
        return try {
            val snapshot = metricsHolder.getSnapshot()
            if (snapshot.memory.memTotalKb > 0) {
                val totalBytes = snapshot.memory.memTotalKb * 1024L
                val availBytes = snapshot.memory.memAvailableKb * 1024L
                if (totalBytes > 0) return Pair(totalBytes, availBytes)
            }
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Pair(mi.totalMem, mi.availMem)
        } catch (e: Exception) {
            Log.e(TAG, "readMemory failed", e)
            Pair(0L, 0L)
        }
    }
}
