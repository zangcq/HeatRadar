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
    @ApplicationContext private val context: Context
) {

    private val TAG = "DeviceStateProvider"

    private var lastFreqStats: List<CpuFreqSample>? = null
    private var lastSampleWallTime: Long = 0L

    fun getDeviceState(): DeviceState {
        val cpuFreqs = readCpuFrequencies()
        val maxFreq = readMaxCpuFreq()
        val cpuUsage = readCpuLoadFromFreqStats() ?: 0f
        val temp = readTemperature()
        val memory = readMemory()

        Log.i(TAG, "getDeviceState: cpuLoad=$cpuUsage%, freq=$cpuFreqs MHz, max=$maxFreq MHz")

        return DeviceState(
            totalCpuFreqMhz = cpuFreqs,
            maxCpuFreqMhz = maxFreq,
            cpuUsagePercent = cpuUsage,
            temperatureCelsius = temp,
            totalMemoryBytes = memory.first,
            availableMemoryBytes = memory.second,
            usedMemoryBytes = memory.first - memory.second,
            memoryUsagePercent = if (memory.first > 0) {
                (memory.first - memory.second).toFloat() / memory.first * 100f
            } else 0f
        )
    }

    private fun readCpuFrequencies(): Long {
        return try {
            var total = 0L
            var count = 0
            for (i in 0..7) {
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
            for (i in 0..7) {
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

    private fun readCpuLoadFromFreqStats(): Float? {
        return try {
            val stats = readAllCpuFreqStats()
            val now = System.currentTimeMillis()

            if (stats == null) {
                Log.w(TAG, "readCpuLoadFromFreqStats: failed to read stats")
                return null
            }

            if (lastFreqStats == null || lastSampleWallTime == 0L) {
                lastFreqStats = stats
                lastSampleWallTime = now
                Log.i(TAG, "readCpuLoadFromFreqStats: first sample, waiting for next interval")
                return 0f
            }

            val wallDelta = now - lastSampleWallTime
            if (wallDelta < 500) {
                Log.w(TAG, "readCpuLoadFromFreqStats: interval too short: ${wallDelta}ms")
                return null
            }

            val maxFreqKhz = readMaxCpuFreq() * 1000L
            if (maxFreqKhz <= 0) {
                Log.w(TAG, "readCpuLoadFromFreqStats: invalid max freq")
                return null
            }

            var totalWeightedFreqKhz = 0.0
            var totalTimeDelta = 0L

            val lastMap = lastFreqStats!!.associate { it.freqKhz to it.timeJiffies }

            for (current in stats) {
                val prevTime = lastMap[current.freqKhz] ?: 0L
                val timeDelta = current.timeJiffies - prevTime
                if (timeDelta > 0) {
                    totalWeightedFreqKhz += current.freqKhz * timeDelta
                    totalTimeDelta += timeDelta
                }
            }

            lastFreqStats = stats
            lastSampleWallTime = now

            if (totalTimeDelta <= 0) {
                Log.w(TAG, "readCpuLoadFromFreqStats: no time delta")
                return 0f
            }

            val avgFreqKhz = totalWeightedFreqKhz / totalTimeDelta
            val loadPercent = (avgFreqKhz / maxFreqKhz * 100.0).toFloat()
            val clamped = loadPercent.coerceIn(0f, 100f)

            Log.i(TAG, "readCpuLoadFromFreqStats: avgFreq=${avgFreqKhz.toLong()/1000}MHz, maxFreq=${maxFreqKhz/1000}MHz, load=$clamped%")
            clamped
        } catch (e: Exception) {
            Log.e(TAG, "readCpuLoadFromFreqStats failed", e)
            null
        }
    }

    private fun readAllCpuFreqStats(): List<CpuFreqSample>? {
        return try {
            val samples = mutableListOf<FreqTimePair>()
            val cpuPaths = listOf(
                "/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state",
                "/sys/devices/system/cpu/cpu4/cpufreq/stats/time_in_state",
                "/sys/devices/system/cpu/cpu7/cpufreq/stats/time_in_state",
                "/sys/devices/system/cpu/cpufreq/policy0/stats/time_in_state",
                "/sys/devices/system/cpu/cpufreq/policy4/stats/time_in_state",
                "/sys/devices/system/cpu/cpufreq/policy7/stats/time_in_state"
            )

            val readPaths = mutableSetOf<String>()
            for (path in cpuPaths) {
                val file = File(path)
                if (!file.exists()) continue
                try {
                    val canonical = file.canonicalPath
                    if (canonical in readPaths) continue
                    readPaths.add(canonical)
                } catch (_: Exception) {}

                val lines = file.readLines()
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val freq = parts[0].toLongOrNull() ?: continue
                        val time = parts[1].toLongOrNull() ?: continue
                        samples.add(FreqTimePair(freq, time))
                    }
                }
            }

            if (samples.isEmpty()) {
                for (i in 0..7) {
                    val path = "/sys/devices/system/cpu/cpu$i/cpufreq/stats/time_in_state"
                    val file = File(path)
                    if (!file.exists()) continue
                    val lines = file.readLines()
                    for (line in lines) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val freq = parts[0].toLongOrNull() ?: continue
                            val time = parts[1].toLongOrNull() ?: continue
                            samples.add(FreqTimePair(freq, time))
                        }
                    }
                }
            }

            if (samples.isEmpty()) {
                Log.w(TAG, "readAllCpuFreqStats: no stats found")
                return null
            }

            val grouped = samples.groupBy { it.freqKhz }
                .map { (freq, entries) ->
                    CpuFreqSample(freqKhz = freq, timeJiffies = entries.sumOf { it.timeJiffies })
                }

            grouped
        } catch (e: Exception) {
            Log.e(TAG, "readAllCpuFreqStats failed", e)
            null
        }
    }

    private data class FreqTimePair(val freqKhz: Long, val timeJiffies: Long)
    private data class CpuFreqSample(val freqKhz: Long, val timeJiffies: Long)

    private fun readTemperature(): Float {
        return try {
            val paths = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/class/thermal/thermal_zone3/temp",
                "/sys/class/thermal/thermal_zone4/temp",
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
