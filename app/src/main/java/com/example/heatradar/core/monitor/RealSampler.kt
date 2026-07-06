package com.example.heatradar.core.monitor

import android.util.Log
import com.example.heatradar.core.common.SettingsManager
import com.example.heatradar.core.database.DeviceStateEntity
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.database.ResourceSampleEntity
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

    private val TAG = "RealSampler"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val isAppInForeground = AtomicBoolean(true)

    private val foregroundIntervalMs: Long = 3_000L
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

                    sampleOnce()
                    sampleDeviceState()
                    sampleCount++

                    if (sampleCount >= cleanupEveryNSamples) {
                        sampleCount = 0
                        cleanupOldData()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sample loop error", e)
                }

                val interval = if (isAppInForeground.get()) foregroundIntervalMs else backgroundIntervalMs
                delay(interval)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    fun onAppForeground() {
        isAppInForeground.set(true)
        Log.i(TAG, "onAppForeground: sampling at ${foregroundIntervalMs}ms interval")
    }

    fun onAppBackground() {
        isAppInForeground.set(false)
        Log.i(TAG, "onAppBackground: sampling reduced to ${backgroundIntervalMs}ms interval")
    }

    private suspend fun syncAppInfo() {
        val apps = appInfoProvider.getInstalledApps()
        repository.insertAppInfo(apps)
        Log.i(TAG, "syncAppInfo: synced ${apps.size} installed apps")
    }

    private suspend fun sampleOnce() {
        val now = System.currentTimeMillis()
        val showSystem = settingsManager.settings.first().showSystemProcesses
        val processes = processScanner.scanAllProcesses(showSystemProcesses = showSystem)

        if (processes.isEmpty()) {
            Log.w(TAG, "sampleOnce: no processes found")
            return
        }

        Log.i(TAG, "sampleOnce: ${processes.size} processes found")

        val now2 = System.currentTimeMillis()
        processes.forEach { proc ->
            val sample = ResourceSampleEntity(
                packageName = proc.packageName,
                timestamp = now2,
                cpuPercent = proc.cpuPercent.coerceIn(0f, 100f),
                memoryBytes = proc.memoryKb * 1024L,
                activeMinutes = proc.foregroundMinutes
            )
            repository.insertSample(sample)
        }
    }

    private suspend fun sampleDeviceState() {
        val state = deviceStateProvider.getDeviceState()
        val foreground = foregroundAppProvider.getForegroundPackageName()

        val entity = DeviceStateEntity(
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
            Log.i(TAG, "cleanupOldData: cleaned data older than 7 days")
        } catch (e: Exception) {
            Log.e(TAG, "cleanupOldData: error", e)
        }
    }
}
