package com.example.heatradar.core.monitor

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

data class WakelockEntry(
    val name: String,
    val packageName: String,
    val activeTimeMs: Long = 0L,
    val wakeCount: Int = 0,
    val isKernel: Boolean = false
)

@Singleton
class WakelockSampler @Inject constructor(
    private val shizukuManager: ShizukuServiceManager
) {
    companion object {
        private const val TAG = "WakelockSampler"
        private const val MIN_SAMPLE_INTERVAL_MS = 10_000L
    }

    private var lastSampleTime = 0L
    private var cachedEntries: List<WakelockEntry> = emptyList()

    fun sample(forceRefresh: Boolean = false): List<WakelockEntry> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastSampleTime < MIN_SAMPLE_INTERVAL_MS) {
            return cachedEntries
        }
        lastSampleTime = now

        return try {
            val output = shizukuManager.executeCommandWithRetry(
                "dumpsys power | grep -A 1000 'Wake Locks:' | head -200"
            )
            if (output.isNullOrBlank()) {
                cachedEntries
            } else {
                val entries = parseWakelocks(output)
                if (entries.isNotEmpty()) {
                    cachedEntries = entries
                }
                cachedEntries
            }
        } catch (e: Exception) {
            Log.w(TAG, "sample failed: ${e.message}")
            cachedEntries
        }
    }

    private fun parseWakelocks(output: String): List<WakelockEntry> {
        val entries = mutableListOf<WakelockEntry>()
        val kernelWakelocks = mutableListOf<WakelockEntry>()

        var inWakeLockSection = false
        var inKernelSection = false

        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("Wake Locks:", ignoreCase = true) && !trimmed.contains("kernel", ignoreCase = true)) {
                inWakeLockSection = true
                inKernelSection = false
                continue
            }
            if (trimmed.contains("Kernel Wake", ignoreCase = true) || trimmed.contains("kernel_wake", ignoreCase = true)) {
                inWakeLockSection = false
                inKernelSection = true
                continue
            }
            if (trimmed.startsWith("Suspend") || trimmed.startsWith("Display") || trimmed.startsWith("Power")) {
                if (inWakeLockSection || inKernelSection) break
            }

            if (inWakeLockSection) {
                parseJavaWakelock(trimmed)?.let { entries.add(it) }
            } else if (inKernelSection) {
                parseKernelWakelock(trimmed)?.let { kernelWakelocks.add(it) }
            }
        }

        return (entries.sortedByDescending { it.activeTimeMs }.take(10) +
                kernelWakelocks.sortedByDescending { it.activeTimeMs }.take(5))
    }

    private fun parseJavaWakelock(line: String): WakelockEntry? {
        return try {
            val patterns = listOf(
                Regex("(?:PARTIAL_WAKE_LOCK|WAKELOCK)\\s+'([^']+)'(?:.*?(?:activated|held|active))?.*?(\\d+)ms.*?(\\d+)\\s*times", RegexOption.IGNORE_CASE),
                Regex("'([^']+)'.*?(\\d+)ms.*?(\\d+)\\s*times", RegexOption.IGNORE_CASE),
                Regex("([\\w.]+/[\\w.$]+).*?(\\d+)ms", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val match = pattern.find(line) ?: continue
                val fullName = match.groupValues[1]
                val timeMs = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
                val count = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

                val pkgName = extractPackageName(fullName)
                return WakelockEntry(
                    name = fullName.takeLast(40),
                    packageName = pkgName,
                    activeTimeMs = timeMs,
                    wakeCount = count,
                    isKernel = false
                )
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseKernelWakelock(line: String): WakelockEntry? {
        return try {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                val name = parts[0]
                val timeMs = parts[1].toLongOrNull() ?: return null
                val count = parts[2].toIntOrNull() ?: 0
                if (name.isNotBlank() && !name.startsWith("[")) {
                    WakelockEntry(
                        name = name,
                        packageName = "kernel",
                        activeTimeMs = timeMs,
                        wakeCount = count,
                        isKernel = true
                    )
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPackageName(fullName: String): String {
        val idx = fullName.indexOf('/')
        return if (idx > 0) fullName.substring(0, idx) else {
            val dotIdx = fullName.indexOf('.')
            if (dotIdx > 0 && dotIdx < fullName.length - 1) fullName.substring(0, fullName.indexOf('.', dotIdx + 1).takeIf { it > 0 } ?: fullName.length)
            else fullName
        }
    }
}
