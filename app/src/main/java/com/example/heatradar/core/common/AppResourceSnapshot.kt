package com.example.heatradar.core.common

data class AppResourceSnapshot(
    val packageName: String,
    val appName: String,
    val cpuPercent: Float,
    val memoryBytes: Long,
    val timestamp: Long,
    val activeMinutes: Long = 0
)

data class DeviceStateSnapshot(
    val timestamp: Long,
    val cpuFreqMhz: Long,
    val maxCpuFreqMhz: Long,
    val cpuUsagePercent: Float,
    val temperatureCelsius: Float,
    val totalMemoryBytes: Long,
    val usedMemoryBytes: Long,
    val memoryUsagePercent: Float,
    val foregroundPackageName: String?
) {
    companion object {
        val EMPTY = DeviceStateSnapshot(
            timestamp = 0L,
            cpuFreqMhz = 0L,
            maxCpuFreqMhz = 0L,
            cpuUsagePercent = 0f,
            temperatureCelsius = 0f,
            totalMemoryBytes = 0L,
            usedMemoryBytes = 0L,
            memoryUsagePercent = 0f,
            foregroundPackageName = null
        )
    }
}
