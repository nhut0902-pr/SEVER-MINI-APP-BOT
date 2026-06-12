package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_config")
data class ConfigEntity(
    @PrimaryKey val id: Int = 1,
    val port: Int = 8080,
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val discordToken: String = "",
    val discordChannelId: String = "",
    val apiKey: String = "",
    val rateLimitEnabled: Boolean = true,
    val serverStarted: Boolean = false,
    
    // AI Brain configurations
    val aiProvider: String = "gemini",
    val aiApiKey: String = "",
    val aiCustomEndpoint: String = "",
    val aiLocalModelPath: String = "",
    
    // Python local workspace code
    val pythonCode: String = "# Viết mã Python của bạn ở đây\nprint(\"Xin chào từ Server Android Mini!\")\n",
    val pythonPath: String = "python3"
)
