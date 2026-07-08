package com.example.heatradar.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceStateDao {
    @Insert
    suspend fun insert(state: DeviceStateEntity)

    @Query("SELECT * FROM device_state ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<DeviceStateEntity?>

    @Query("SELECT * FROM device_state ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): DeviceStateEntity?

    @Query("DELETE FROM device_state WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM device_state")
    suspend fun deleteAll()

    @Query("SELECT * FROM device_state WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getStatesSince(since: Long): List<DeviceStateEntity>

    @Query("SELECT AVG(cpuUsagePercent) FROM device_state WHERE timestamp >= :since")
    suspend fun getAvgCpuSince(since: Long): Float?

    @Query("SELECT MAX(cpuUsagePercent) FROM device_state WHERE timestamp >= :since")
    suspend fun getMaxCpuSince(since: Long): Float?

    @Query("SELECT AVG(temperatureCelsius) FROM device_state WHERE timestamp >= :since")
    suspend fun getAvgTempSince(since: Long): Float?

    @Query("SELECT MAX(temperatureCelsius) FROM device_state WHERE timestamp >= :since")
    suspend fun getMaxTempSince(since: Long): Float?

    @Query("SELECT AVG(memoryUsagePercent) FROM device_state WHERE timestamp >= :since")
    suspend fun getAvgMemSince(since: Long): Float?
}
