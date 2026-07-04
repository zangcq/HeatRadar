package com.example.heatradar.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun toggleHighFrequency() {
        _uiState.update { it.copy(highFrequencySampling = !it.highFrequencySampling) }
    }

    fun toggleRetention() {
        _uiState.update { it.copy(longDataRetention = !it.longDataRetention) }
    }

    fun toggleAlerts() {
        _uiState.update { it.copy(anomalyAlerts = !it.anomalyAlerts) }
    }
}
