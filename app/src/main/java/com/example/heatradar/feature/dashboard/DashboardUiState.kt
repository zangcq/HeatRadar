package com.example.heatradar.feature.dashboard

import com.example.heatradar.core.common.AppResourceSnapshot
import com.example.heatradar.core.common.DeviceStateSnapshot

data class DashboardUiState(
    val cpuTop: List<AppResourceSnapshot> = emptyList(),
    val memoryTop: List<AppResourceSnapshot> = emptyList(),
    val deviceState: DeviceStateSnapshot = DeviceStateSnapshot.EMPTY,
    val isLoading: Boolean = true
)
