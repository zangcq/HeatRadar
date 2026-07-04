package com.example.heatradar.feature.appdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heatradar.core.database.HeatRadarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: HeatRadarRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppDetailUiState(packageName = savedStateHandle.get<String>("packageName") ?: "")
    )
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val current = _uiState.value
            val info = repository.getAppInfo(current.packageName)
            val sample = repository.getLatestSample(current.packageName)
            _uiState.update {
                it.copy(
                    appName = info?.appName ?: current.packageName,
                    cpuPercent = sample?.cpuPercent ?: 0f,
                    memoryBytes = sample?.memoryBytes ?: 0L,
                    timestamp = sample?.timestamp ?: 0L,
                    isLoading = false
                )
            }
        }
    }
}
