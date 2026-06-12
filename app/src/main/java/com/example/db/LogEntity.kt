package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "request_logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val endpoint: String,
    val ip: String,
    val responseBody: String,
    val statusCode: Int,
    val durationMs: Long
)
