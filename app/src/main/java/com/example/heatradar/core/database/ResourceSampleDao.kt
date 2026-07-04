package com.example.heatradar.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ResourceSampleDao {

    @Insert
    suspend fun insert(sample: ResourceSampleEntity)

    @Query("SELECT * FROM resource_sample ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ResourceSampleEntity>>

    @Query(
        """
        SELECT s.* FROM resource_sample s
        INNER JOIN (
            SELECT packageName, MAX(timestamp) AS maxTimestamp
            FROM resource_sample
            GROUP BY packageName
        ) latest ON s.packageName = latest.packageName AND s.timestamp = latest.maxTimestamp
        ORDER BY s.cpuPercent DESC
        """
    )
    fun observeLatestPerApp(): Flow<List<ResourceSampleEntity>>

    @Query(
        """
        SELECT s.packageName, a.appName, s.timestamp, s.cpuPercent, s.memoryBytes, s.activeMinutes
        FROM resource_sample s
        INNER JOIN (
            SELECT packageName, MAX(timestamp) AS maxTimestamp
            FROM resource_sample
            GROUP BY packageName
        ) latest ON s.packageName = latest.packageName AND s.timestamp = latest.maxTimestamp
        LEFT JOIN app_info a ON s.packageName = a.packageName
        ORDER BY s.activeMinutes DESC
        """
    )
    fun observeLatestWithAppInfo(): Flow<List<SampleWithAppInfo>>

    @Query("SELECT * FROM resource_sample WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForApp(packageName: String): ResourceSampleEntity?

    @Query("DELETE FROM resource_sample WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

data class SampleWithAppInfo(
    val packageName: String,
    val appName: String?,
    val timestamp: Long,
    val cpuPercent: Float,
    val memoryBytes: Long,
    val activeMinutes: Long
)
