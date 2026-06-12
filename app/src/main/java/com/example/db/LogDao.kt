package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Query("SELECT * FROM request_logs ORDER BY timestamp DESC LIMIT 200")
    fun getLatestLogsFlow(): Flow<List<LogEntity>>

    @Insert
    suspend fun insertLog(log: LogEntity)

    @Query("DELETE FROM request_logs")
    suspend fun clearLogs()
}
