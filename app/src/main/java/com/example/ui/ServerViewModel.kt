package com.example.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.ConfigEntity
import com.example.db.LogEntity
import com.example.db.ServerRepository
import com.example.service.ServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServerRepository(application)

    // Configuration state
    private val _configState = MutableStateFlow(ConfigEntity())
    val configState: StateFlow<ConfigEntity> = _configState.asStateFlow()

    // Battery Optimization disabled check
    private val _isBatteryOptimizationDisabled = MutableStateFlow(true)
    val isBatteryOptimizationDisabled: StateFlow<Boolean> = _isBatteryOptimizationDisabled.asStateFlow()

    // DB request log flow (last 200 entries)
    val latestLogs: StateFlow<List<LogEntity>> = repository.latestLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Binding live values from Background Foreground Service
    val isRunning: StateFlow<Boolean> = ServerService.isRunning
    val requestCount: StateFlow<Int> = ServerService.requestCount
    val currentPort: StateFlow<Int> = ServerService.port
    val localIp: StateFlow<String> = ServerService.localIp
    val uptimeStart: StateFlow<Long> = ServerService.uptimeStart

    init {
        loadConfig()
        checkBatteryOptimization()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _configState.value = repository.getConfig()
        }
    }

    fun saveConfig(port: Int, telegramToken: String, telegramChatId: String, discordToken: String, discordChannelId: String, apiKey: String, rateLimitEnabled: Boolean) {
        viewModelScope.launch {
            val updatedConfig = _configState.value.copy(
                port = port,
                telegramToken = telegramToken,
                telegramChatId = telegramChatId,
                discordToken = discordToken,
                discordChannelId = discordChannelId,
                apiKey = apiKey,
                rateLimitEnabled = rateLimitEnabled
            )
            repository.saveConfig(updatedConfig)
            _configState.value = updatedConfig

            // If the service is running, suggest restart by restarting if they modified values
            if (isRunning.value) {
                toggleServer(forceRestart = true)
            }
        }
    }

    fun saveAiConfig(aiProvider: String, aiApiKey: String, aiCustomEndpoint: String, aiLocalModelPath: String) {
        viewModelScope.launch {
            val updatedConfig = _configState.value.copy(
                aiProvider = aiProvider,
                aiApiKey = aiApiKey,
                aiCustomEndpoint = aiCustomEndpoint,
                aiLocalModelPath = aiLocalModelPath
            )
            repository.saveConfig(updatedConfig)
            _configState.value = updatedConfig
        }
    }

    fun savePythonCode(code: String, path: String) {
        viewModelScope.launch {
            val updatedConfig = _configState.value.copy(pythonCode = code, pythonPath = path)
            repository.saveConfig(updatedConfig)
            _configState.value = updatedConfig
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun checkBatteryOptimization() {
        val context = getApplication<Application>()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _isBatteryOptimizationDisabled.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun toggleServer(forceRestart: Boolean = false) {
        val context = getApplication<Application>()
        if (isRunning.value && !forceRestart) {
            ServerService.stopService(context)
        } else {
            if (forceRestart && isRunning.value) {
                ServerService.stopService(context)
            }
            ServerService.startService(context)
        }
    }
}
