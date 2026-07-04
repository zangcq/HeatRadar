package com.example.heatradar.core.monitor

data class ProcessInfo(
    val pid: Int,
    val packageName: String,
    val memoryKb: Long,
    val cpuPercent: Float,
    val isForeground: Boolean
)
