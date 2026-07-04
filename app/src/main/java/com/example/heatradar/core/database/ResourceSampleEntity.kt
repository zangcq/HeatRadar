package com.example.heatradar.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "resource_sample")
data class ResourceSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val cpuPercent: Float,
    val memoryBytes: Long,
    val activeMinutes: Long = 0
)
