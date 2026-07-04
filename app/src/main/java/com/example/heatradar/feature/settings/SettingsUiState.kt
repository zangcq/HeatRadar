package com.example.heatradar.feature.settings

data class SettingsUiState(
    val highFrequencySampling: Boolean = false,
    val longDataRetention: Boolean = false,
    val anomalyAlerts: Boolean = true
)
