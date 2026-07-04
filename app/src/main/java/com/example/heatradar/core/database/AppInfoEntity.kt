package com.example.heatradar.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_info")
data class AppInfoEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isSystem: Boolean = false
)
