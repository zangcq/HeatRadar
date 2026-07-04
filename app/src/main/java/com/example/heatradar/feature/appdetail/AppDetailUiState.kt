package com.example.heatradar.feature.appdetail

data class AppDetailUiState(
    val packageName: String = "",
    val appName: String = "",
    val cpuPercent: Float = 0f,
    val memoryBytes: Long = 0L,
    val timestamp: Long = 0L,
    val isLoading: Boolean = true
)
