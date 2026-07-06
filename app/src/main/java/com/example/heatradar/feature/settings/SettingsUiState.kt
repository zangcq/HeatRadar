package com.example.heatradar.feature.settings

import com.example.heatradar.core.monitor.DaemonStatus

data class SettingsUiState(
    val highFrequencySampling: Boolean = false,
    val longDataRetention: Boolean = false,
    val anomalyAlerts: Boolean = true,
    val showSystemProcesses: Boolean = false,
    val daemonStatus: DaemonStatus = DaemonStatus.NOT_RUNNING
)
