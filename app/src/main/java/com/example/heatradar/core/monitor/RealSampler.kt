package com.example.heatradar.core.monitor

import android.util.Log
import com.example.heatradar.core.common.SettingsManager
import com.example.heatradar.core.database.HeatRadarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealSampler @Inject constructor(
    private val repository: HeatRadarRepository,
    private val appInfoProvider: AppInfoProvider,
    private val processScanner: ProcessScanner,
    private val deviceStateProvider: DeviceStateProvider,
    private val foregroundAppProvider: ForegroundAppProvider,
    private val settingsManager: SettingsManager
) {

    companion object {
        private const val TAG = "RealSampler"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val isAppInForeground = AtomicBoolean(true)

    private val foregroundIntervalMs: Long = 3_000L
    private val monitorServiceActiveIntervalMs: Long = 30_000L
    private val backgroundIntervalMs: Long = 30_000L
    private val cleanupEveryNSamples = 10

    private var appInfoSynced = false
    private var sampleCount = 0

    fun start() {
        if (started.getAndSet(true)) return

        scope.launch {
            while (isActive) {
                try {
                    if (!appInfoSynced) {
                        syncAppInfo()
                        appInfoSynced = true
                    }

                    val monitorRunning = MonitorService.isServiceRunning()

                    if (!monitorRunning) {
                        sampleOnce()
                        sampleDeviceState()
                        sampleCount++

                        if (sampleCount >= cleanupEveryNSamples) {
                            sampleCount = 0
                            cleanupOldData()
                        }
                    } else {
                        sampleCount = 0
                        if (System.currentTimeMillis() % (monitorServiceActiveIntervalMs * 2) < 5000) {
                            cleanupOldData()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sample loop error", e)
                }

                val interval = when {
                    MonitorService.isServiceRunning() -> monitorServiceActiveIntervalMs
                    isAppInForeground.get() -> foregroundIntervalMs
                    else -> backgroundIntervalMs
                }
                delay(interval)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    fun onAppForeground() {
        isAppInForeground.set(true)
    }

    fun onAppBackground() {
        isAppInForeground.set(false)
    }

    private suspend fun syncAppInfo() {
        val apps = appInfoProvider.getInstalledApps()
        repository.insertAppInfo(apps)
        Log.i(TAG, "syncAppInfo: synced ${apps.size} installed apps")
    }

    private suspend fun sampleOnce() {
        val showSystem = settingsManager.settings.first().showSystemProcesses
        val processes = processScanner.scanAllProcesses(showSystemProcesses = showSystem)

        if (processes.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val samples = processes.map { proc ->
            com.example.heatradar.core.database.ResourceSampleEntity(
                packageName = proc.packageName,
                timestamp = now,
                cpuPercent = proc.cpuPercent.coerceIn(0f, 100f),
                memoryBytes = proc.memoryKb * 1024L,
                activeMinutes = proc.foregroundMinutes
            )
        }
        repository.insertAllSamples(samples)
    }

    private suspend fun sampleDeviceState() {
        val state = deviceStateProvider.getDeviceState()
        val foreground = foregroundAppProvider.getForegroundPackageName()

        val entity = com.example.heatradar.core.database.DeviceStateEntity(
            timestamp = System.currentTimeMillis(),
            cpuFreqMhz = state.totalCpuFreqMhz,
            maxCpuFreqMhz = state.maxCpuFreqMhz,
            cpuUsagePercent = state.cpuUsagePercent,
            temperatureCelsius = state.temperatureCelsius,
            totalMemoryBytes = state.totalMemoryBytes,
            usedMemoryBytes = state.usedMemoryBytes,
            memoryUsagePercent = state.memoryUsagePercent,
            foregroundPackageName = foreground
        )
        repository.insertDeviceState(entity)
    }

    private suspend fun cleanupOldData() {
        try {
            repository.cleanupOldData()
        } catch (e: Exception) {
            Log.e(TAG, "cleanupOldData: error", e)
        }
    }
}
