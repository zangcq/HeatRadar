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
    val memoryUsagePercent: Float
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
    private val cpuSampler: CpuSampler
) {

    private val TAG = "DeviceStateProvider"

    fun getDeviceState(): DeviceState {
        val cpuFreqs = readCpuFrequencies()
        val maxFreq = readMaxCpuFreq()
        val temp = readTemperature()
        val memory = readMemory()
        val cpuUsage = cpuSampler.getSystemCpuPercent()

        val usedMem = memory.first - memory.second
        val memPercent = if (memory.first > 0) {
            usedMem.toFloat() / memory.first * 100f
        } else 0f

        Log.d(TAG, "getDeviceState: cpu=$cpuUsage% mem=$memPercent% temp=$temp°C")

        return DeviceState(
            totalCpuFreqMhz = cpuFreqs,
            maxCpuFreqMhz = maxFreq,
            cpuUsagePercent = cpuUsage,
            temperatureCelsius = temp,
            totalMemoryBytes = memory.first,
            availableMemoryBytes = memory.second,
            usedMemoryBytes = usedMem,
            memoryUsagePercent = memPercent
        )
    }

    private fun readCpuFrequencies(): Long {
        return try {
            var total = 0L
            var count = 0
            for (i in 0..15) {
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
            Log.e(TAG, "readCpuFrequencies failed", e)
            0L
        }
    }

    private fun readMaxCpuFreq(): Long {
        return try {
            var max = 0L
            for (i in 0..15) {
                val path = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val file = File(path)
                if (file.exists()) {
                    val khz = file.readText().trim().toLongOrNull() ?: continue
                    val mhz = khz / 1000
                    if (mhz > max) max = mhz
                }
            }
            max
        } catch (e: Exception) {
            Log.e(TAG, "readMaxCpuFreq failed", e)
            0L
        }
    }

    private fun readTemperature(): Float {
        return try {
            val paths = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/class/thermal/thermal_zone3/temp",
                "/sys/class/thermal/thermal_zone4/temp",
                "/sys/class/thermal/thermal_zone5/temp",
                "/sys/class/thermal/thermal_zone6/temp",
                "/sys/class/thermal/thermal_zone7/temp",
                "/sys/class/thermal/thermal_zone8/temp",
                "/sys/class/thermal/thermal_zone9/temp",
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
            Log.e(TAG, "readTemperature failed", e)
            0f
        }
    }

    private fun readMemory(): Pair<Long, Long> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Pair(mi.totalMem, mi.availMem)
        } catch (e: Exception) {
            Log.e(TAG, "readMemory failed", e)
            Pair(0L, 0L)
        }
    }
}
