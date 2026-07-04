package com.example.heatradar.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_state")
data class DeviceStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val cpuFreqMhz: Long,
    val maxCpuFreqMhz: Long,
    val cpuUsagePercent: Float,
    val temperatureCelsius: Float,
    val totalMemoryBytes: Long,
    val usedMemoryBytes: Long,
    val memoryUsagePercent: Float,
    val foregroundPackageName: String?
)
