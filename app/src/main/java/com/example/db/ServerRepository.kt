package com.example.db

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ServerRepository(context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val logDao = db.logDao()
    private val configDao = db.configDao()

    val latestLogs: Flow<List<LogEntity>> = logDao.getLatestLogsFlow()

    suspend fun getConfig(): ConfigEntity {
        var config = configDao.getConfig() ?: ConfigEntity().also { 
            configDao.saveConfig(it)
        }
        if (config.discordToken.isBlank()) {
            config = config.copy(
                discordToken = "",
                discordChannelId = ""
            )
            configDao.saveConfig(config)
        }
        return config
    }

    suspend fun saveConfig(config: ConfigEntity) {
        configDao.saveConfig(config)
    }

    suspend fun clearLogs() {
        logDao.clearLogs()
    }
}
