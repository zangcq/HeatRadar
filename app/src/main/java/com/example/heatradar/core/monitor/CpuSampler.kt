package com.example.heatradar.core.monitor

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CpuSampler @Inject constructor() {

    private val TAG = "CpuSampler"
    private val cpuCoreCount: Int by lazy {
        try {
            Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            4
        }
    }

    private var lastTotalTime: Long = 0
    private var lastIdleTime: Long = 0
    private var lastSampleTime: Long = 0
    private var lastSystemCpuPercent: Float = 0f
    private var initialized = false

    @Synchronized
    fun getSystemCpuPercent(): Float {
        val totalTime = readSystemCpuTotal()
        val idleTime = readSystemCpuIdle()
        val now = System.currentTimeMillis()

        if (totalTime <= 0L) return lastSystemCpuPercent

        if (!initialized || lastTotalTime == 0L || lastIdleTime == 0L) {
            lastTotalTime = totalTime
            lastIdleTime = idleTime
            lastSampleTime = now
            initialized = true
            return 0f
        }

        val totalDelta = totalTime - lastTotalTime
        val idleDelta = idleTime - lastIdleTime

        if (totalDelta > 0 && now - lastSampleTime > 200) {
            val usagePercent = (totalDelta - idleDelta).toFloat() / totalDelta * 100f
            lastSystemCpuPercent = usagePercent.coerceIn(0f, 100f)
        }

        lastTotalTime = totalTime
        lastIdleTime = idleTime
        lastSampleTime = now

        return lastSystemCpuPercent
    }

    fun sampleProcessCpu(pids: List<Int>): Map<Int, Float> {
        val totalTime1 = readSystemCpuTotal()
        val processTimes1 = pids.associateWith { readProcessCpuTime(it) }

        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {}

        val totalTime2 = readSystemCpuTotal()
        val processTimes2 = pids.associateWith { readProcessCpuTime(it) }

        val systemTotalDelta = totalTime2 - totalTime1
        val results = mutableMapOf<Int, Float>()

        if (systemTotalDelta > 0) {
            pids.forEach { pid ->
                val processDelta = (processTimes2[pid] ?: 0) - (processTimes1[pid] ?: 0)
                if (processDelta > 0) {
                    val rawPercent = (processDelta.toFloat() / systemTotalDelta) * 100f
                    val normalizedPercent = rawPercent
                    results[pid] = normalizedPercent.coerceIn(0f, 100f)
                } else {
                    results[pid] = 0f
                }
            }
        }

        return results
    }

    private fun readSystemCpuTotal(): Long {
        return try {
            val content = File("/proc/stat").readText()
            val firstLine = content.lineSequence().firstOrNull() ?: return 0L
            val parts = firstLine.split(Regex("\\s+"))
            parts.drop(1).filter { it.isNotEmpty() }.sumOf { it.toLongOrNull() ?: 0L }
        } catch (e: Exception) {
            Log.e(TAG, "readSystemCpuTotal failed", e)
            0L
        }
    }

    private fun readSystemCpuIdle(): Long {
        return try {
            val content = File("/proc/stat").readText()
            val firstLine = content.lineSequence().firstOrNull() ?: return 0L
            val parts = firstLine.split(Regex("\\s+"))
            val idle = if (parts.size > 4) parts[4].toLongOrNull() ?: 0L else 0L
            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
            idle + iowait
        } catch (e: Exception) {
            Log.e(TAG, "readSystemCpuIdle failed", e)
            0L
        }
    }

    private fun readProcessCpuTime(pid: Int): Long {
        return try {
            val content = File("/proc/$pid/stat").readText()
            val statEnd = content.lastIndexOf(')')
            if (statEnd < 0) return 0L
            val afterComm = content.substring(statEnd + 2)
            val parts = afterComm.split(Regex("\\s+"))
            if (parts.size >= 15) {
                val utime = parts[11].toLongOrNull() ?: 0L
                val stime = parts[12].toLongOrNull() ?: 0L
                utime + stime
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getCpuCount(): Int = cpuCoreCount
}
