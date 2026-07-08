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

    @Query("DELETE FROM resource_sample")
    suspend fun deleteAll()

    @Query("SELECT * FROM resource_sample WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getSamplesSince(since: Long): List<ResourceSampleEntity>

    @Query(
        """
        SELECT s.packageName, a.appName, AVG(s.cpuPercent) as avgCpu, MAX(s.cpuPercent) as maxCpu,
               AVG(s.memoryBytes) as avgMem, MAX(s.memoryBytes) as maxMem,
               MAX(s.activeMinutes) as activeMinutes
        FROM resource_sample s
        LEFT JOIN app_info a ON s.packageName = a.packageName
        WHERE s.timestamp >= :since
        GROUP BY s.packageName
        ORDER BY maxCpu DESC
        """
    )
    suspend fun getAggregatedSince(since: Long): List<SampleAggregate>
}

data class SampleWithAppInfo(
    val packageName: String,
    val appName: String?,
    val timestamp: Long,
    val cpuPercent: Float,
    val memoryBytes: Long,
    val activeMinutes: Long
)

data class SampleAggregate(
    val packageName: String,
    val appName: String?,
    val avgCpu: Float,
    val maxCpu: Float,
    val avgMem: Long,
    val maxMem: Long,
    val activeMinutes: Long
)
