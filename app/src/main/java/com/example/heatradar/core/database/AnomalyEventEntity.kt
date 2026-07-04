package com.example.heatradar.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "anomaly_event")
data class AnomalyEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val type: String,
    val description: String
)
