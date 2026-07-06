package com.example.heatradar.core.database

import com.example.heatradar.core.common.AppResourceSnapshot
import com.example.heatradar.core.common.DeviceStateSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface HeatRadarRepository {
    suspend fun insertAppInfo(apps: List<AppInfoEntity>)
    suspend fun insertSample(sample: ResourceSampleEntity)
    suspend fun insertEvent(event: AnomalyEventEntity)
    suspend fun insertDeviceState(state: DeviceStateEntity)
    fun observeLatestSamples(): Flow<List<AppResourceSnapshot>>
    fun observeLatestDeviceState(): Flow<DeviceStateSnapshot>
    suspend fun getAppInfo(packageName: String): AppInfoEntity?
    suspend fun getLatestSample(packageName: String): ResourceSampleEntity?
    suspend fun cleanupOldData()
}

@Singleton
class DefaultHeatRadarRepository @Inject constructor(
    private val appInfoDao: AppInfoDao,
    private val resourceSampleDao: ResourceSampleDao,
    private val anomalyEventDao: AnomalyEventDao,
    private val deviceStateDao: DeviceStateDao
) : HeatRadarRepository {

    override suspend fun insertAppInfo(apps: List<AppInfoEntity>) {
        appInfoDao.insertAll(apps)
    }

    override suspend fun insertSample(sample: ResourceSampleEntity) {
        resourceSampleDao.insert(sample)
    }

    override suspend fun insertEvent(event: AnomalyEventEntity) {
        anomalyEventDao.insert(event)
    }

    override suspend fun insertDeviceState(state: DeviceStateEntity) {
        deviceStateDao.insert(state)
    }

    override fun observeLatestSamples(): Flow<List<AppResourceSnapshot>> {
        return resourceSampleDao.observeLatestWithAppInfo().map { samples ->
            samples.map { sample ->
                AppResourceSnapshot(
                    packageName = sample.packageName,
                    appName = sample.appName ?: sample.packageName.substringAfterLast("."),
                    cpuPercent = sample.cpuPercent,
                    memoryBytes = sample.memoryBytes,
                    timestamp = sample.timestamp,
                    activeMinutes = sample.activeMinutes
                )
            }
        }
    }

    override fun observeLatestDeviceState(): Flow<DeviceStateSnapshot> {
        return deviceStateDao.observeLatest().map { entity ->
            entity?.toSnapshot() ?: DeviceStateSnapshot.EMPTY
        }
    }

    override suspend fun getAppInfo(packageName: String): AppInfoEntity? {
        return appInfoDao.getByPackageName(packageName)
    }

    override suspend fun getLatestSample(packageName: String): ResourceSampleEntity? {
        return resourceSampleDao.getLatestForApp(packageName)
    }

    override suspend fun cleanupOldData() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        resourceSampleDao.deleteOlderThan(cutoff)
        deviceStateDao.deleteOlderThan(cutoff)
        anomalyEventDao.deleteOlderThan(cutoff)
    }
}

private fun DeviceStateEntity.toSnapshot(): DeviceStateSnapshot {
    return DeviceStateSnapshot(
        timestamp = timestamp,
        cpuFreqMhz = cpuFreqMhz,
        maxCpuFreqMhz = maxCpuFreqMhz,
        cpuUsagePercent = cpuUsagePercent,
        temperatureCelsius = temperatureCelsius,
        totalMemoryBytes = totalMemoryBytes,
        usedMemoryBytes = usedMemoryBytes,
        memoryUsagePercent = memoryUsagePercent,
        foregroundPackageName = foregroundPackageName
    )
}
