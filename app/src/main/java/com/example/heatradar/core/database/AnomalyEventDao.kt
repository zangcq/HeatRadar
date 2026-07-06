package com.example.heatradar.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnomalyEventDao {

    @Insert
    suspend fun insert(event: AnomalyEventEntity)

    @Query("SELECT * FROM anomaly_event ORDER BY timestamp DESC LIMIT 50")
    fun observeRecent(): Flow<List<AnomalyEventEntity>>

    @Query("DELETE FROM anomaly_event WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM anomaly_event")
    suspend fun deleteAll()
}
