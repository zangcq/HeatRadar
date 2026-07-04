package com.example.heatradar.core.monitor

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CpuSampler @Inject constructor() {

    private var lastTotalTime: Long = 0
    private var lastIdleTime: Long = 0
    private var lastProcessTimes = mutableMapOf<Int, Long>()

    fun sampleProcessCpu(pids: List<Int>): Map<Int, Float> {
        val totalTime1 = readSystemCpuTotal()
        val idleTime1 = readSystemCpuIdle()
        val processTimes1 = pids.associateWith { readProcessCpuTime(it) }

        Thread.sleep(150)

        val totalTime2 = readSystemCpuTotal()
        val idleTime2 = readSystemCpuIdle()
        val processTimes2 = pids.associateWith { readProcessCpuTime(it) }

        val systemTotalDelta = totalTime2 - totalTime1
        val results = mutableMapOf<Int, Float>()

        if (systemTotalDelta > 0) {
            pids.forEach { pid ->
                val processDelta = (processTimes2[pid] ?: 0) - (processTimes1[pid] ?: 0)
                if (processDelta > 0) {
                    val cpuPercent = (processDelta.toFloat() / systemTotalDelta) * 100f * getCpuCount()
                    results[pid] = cpuPercent.coerceIn(0f, 100f)
                } else {
                    results[pid] = 0f
                }
            }
        }

        lastTotalTime = totalTime2
        lastIdleTime = idleTime2
        lastProcessTimes = processTimes2.toMutableMap()

        return results
    }

    private fun readSystemCpuTotal(): Long {
        return try {
            val content = File("/proc/stat").readText()
            val firstLine = content.lineSequence().firstOrNull() ?: return 0L
            val parts = firstLine.split(Regex("\\s+"))
            parts.drop(1).filter { it.isNotEmpty() }.sumOf { it.toLongOrNull() ?: 0L }
        } catch (e: Exception) {
            0L
        }
    }

    private fun readSystemCpuIdle(): Long {
        return try {
            val content = File("/proc/stat").readText()
            val firstLine = content.lineSequence().firstOrNull() ?: return 0L
            val parts = firstLine.split(Regex("\\s+"))
            if (parts.size > 4) {
                (parts[4].toLongOrNull() ?: 0L) + (parts.getOrNull(5)?.toLongOrNull() ?: 0L)
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun readProcessCpuTime(pid: Int): Long {
        return try {
            val content = File("/proc/$pid/stat").readText()
            val parts = content.split(Regex("\\s+"))
            if (parts.size >= 17) {
                val utime = parts[13].toLongOrNull() ?: 0L
                val stime = parts[14].toLongOrNull() ?: 0L
                val cutime = parts[15].toLongOrNull() ?: 0L
                val cstime = parts[16].toLongOrNull() ?: 0L
                utime + stime + cutime + cstime
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun getCpuCount(): Int {
        return try {
            Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            1
        }
    }
}
