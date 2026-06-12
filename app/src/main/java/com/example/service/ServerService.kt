package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.ConfigEntity
import com.example.db.LogEntity
import com.example.server.MiniHttpServer
import com.example.server.RateLimiter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ServerService : Service() {

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + job)

    private var httpServer: MiniHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld = false
    private var telegramJob: Job? = null
    private var discordJob: Job? = null
    private var discordWebSocket: WebSocket? = null
    private var discordHeartbeatJob: Job? = null
    private var discordReconnectJob: Job? = null
    private var pythonProcess: Process? = null
    private var pythonProcessJob: Job? = null

    companion object {
        private const val NOTIFICATION_ID = 2026
        private const val CHANNEL_ID = "server_service_channel"

        // Live stats for UI binding
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _port = MutableStateFlow(8080)
        val port = _port.asStateFlow()

        private val _requestCount = MutableStateFlow(0)
        val requestCount = _requestCount.asStateFlow()

        private val _localIp = MutableStateFlow("127.0.0.1")
        val localIp = _localIp.asStateFlow()

        private val _uptimeStart = MutableStateFlow(0L)
        val uptimeStart = _uptimeStart.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _localIp.value = getWifiIpAddress(this)
        _uptimeStart.value = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Build notification channel and start foreground immediately to comply with Android OS
        createNotificationChannel()
        val notification = buildNotification("Initializing Server...")
        startForeground(NOTIFICATION_ID, notification)

        _isRunning.value = true

        // 1. Maintain CPU power via WakeLock safely on the Service main thread where package context is fully resolved
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ServerService::PowerLock")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        wakeLock?.let {
            try {
                if (!isWakeLockHeld) {
                    it.acquire()
                    isWakeLockHeld = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        serviceScope.launch {
            try {
                // Read and set SQLite configurations
                val db = AppDatabase.getInstance(this@ServerService)
                var config = db.configDao().getConfig()
                if (config == null) {
                    config = ConfigEntity()
                    db.configDao().saveConfig(config)
                }

                _port.value = config.port

                // 2. Initialize security controls
                val rateLimiter = RateLimiter()
                val apiSecret = if (config.apiKey.isNotBlank()) config.apiKey else null

                // 3. Spawns HTTP engine
                httpServer = MiniHttpServer(
                    port = config.port,
                    apiKey = apiSecret,
                    rateLimiter = rateLimiter,
                    onLogReceived = { method, endpoint, ip, respBody, code, duration ->
                        serviceScope.launch {
                            val logEntity = LogEntity(
                                method = method,
                                endpoint = endpoint,
                                ip = ip,
                                responseBody = respBody.take(150),
                                statusCode = code,
                                durationMs = duration
                            )
                            db.logDao().insertLog(logEntity)
                            _requestCount.value += 1
                        }
                    },
                    systemStatusProvider = {
                        getSystemStatusJson()
                    },
                    onMessageReceived = { msg ->
                        serviceScope.launch {
                            forwardMessageToTelegram(msg)
                            forwardMessageToDiscord(msg)
                        }
                    }
                ).apply {
                    start()
                }

                _localIp.value = getWifiIpAddress(this@ServerService)
                updateNotification("Server is running on: ${_localIp.value}:${config.port}")

                // Update isStarted status in db config
                db.configDao().saveConfig(config.copy(serverStarted = true))

                // 4. Polling telegram APIs 
                if (config.telegramToken.isNotBlank() && config.telegramChatId.isNotBlank()) {
                    startTelegramPolling(config.telegramToken, config.telegramChatId)
                }

                // 5. Polling discord APIs
                if (config.discordToken.isNotBlank() && config.discordChannelId.isNotBlank()) {
                    startDiscordPolling(config.discordToken, config.discordChannelId)
                }

                // 6. Launch active Python Bot Workspace in real background process
                if (config.pythonCode.isNotBlank()) {
                    startPythonBotProcess(config.pythonCode, config.pythonPath)
                }

                // 7. Dynamic hot-reload configuration on changes
                serviceScope.launch {
                    var currentDiscordToken = config.discordToken
                    var currentDiscordChannelId = config.discordChannelId
                    var currentTelegramToken = config.telegramToken
                    var currentTelegramChatId = config.telegramChatId
                    var currentPythonCode = config.pythonCode
                    var currentPythonPath = config.pythonPath
                    
                    db.configDao().getConfigFlow().collect { updatedConfig ->
                        if (updatedConfig != null) {
                            // Check Discord
                            if (updatedConfig.discordToken != currentDiscordToken || updatedConfig.discordChannelId != currentDiscordChannelId) {
                                currentDiscordToken = updatedConfig.discordToken
                                currentDiscordChannelId = updatedConfig.discordChannelId
                                if (currentDiscordToken.isNotBlank() && currentDiscordChannelId.isNotBlank()) {
                                    insertSystemLog("SYSTEM", "[Config Reload]", "🔄 Phát hiện thay đổi cấu hình Discord! Đang tự động nạp lại Bot...", 200)
                                    startDiscordPolling(currentDiscordToken, currentDiscordChannelId)
                                } else {
                                    discordJob?.cancel()
                                }
                            }
                            // Check Telegram
                            if (updatedConfig.telegramToken != currentTelegramToken || updatedConfig.telegramChatId != currentTelegramChatId) {
                                currentTelegramToken = updatedConfig.telegramToken
                                currentTelegramChatId = updatedConfig.telegramChatId
                                if (currentTelegramToken.isNotBlank() && currentTelegramChatId.isNotBlank()) {
                                    insertSystemLog("SYSTEM", "[Config Reload]", "🔄 Phát hiện thay đổi cấu hình Telegram! Đang tự động nạp lại Bot...", 200)
                                    startTelegramPolling(currentTelegramToken, currentTelegramChatId)
                                } else {
                                    telegramJob?.cancel()
                                }
                            }
                            // Check Python Code
                            if (updatedConfig.pythonCode != currentPythonCode || updatedConfig.pythonPath != currentPythonPath) {
                                currentPythonCode = updatedConfig.pythonCode
                                currentPythonPath = updatedConfig.pythonPath
                                if (currentPythonCode.isNotBlank()) {
                                    insertSystemLog("SYSTEM", "[Config Reload]", "🔄 Phát hiện cấu hình Python thay đổi! Đang khởi động lại Bot Python...", 200)
                                    startPythonBotProcess(currentPythonCode, currentPythonPath)
                                } else {
                                    stopPythonBotProcess()
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("Start failed: ${e.localizedMessage}")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        httpServer?.stop()
        telegramJob?.cancel()
        discordJob?.cancel()
        discordHeartbeatJob?.cancel()
        discordReconnectJob?.cancel()
        try {
            discordWebSocket?.close(1000, "Service destroyed")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        discordWebSocket = null
        stopPythonBotProcess()
        wakeLock?.let {
            try {
                if (isWakeLockHeld && it.isHeld) {
                    it.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isWakeLockHeld = false
                wakeLock = null
            }
        }
        
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@ServerService)
                val config = db.configDao().getConfig()
                if (config != null) {
                    db.configDao().saveConfig(config.copy(serverStarted = false))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                job.cancel()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getWifiIpAddress(context: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val host = address.hostAddress
                        if (!host.isNullOrBlank()) return host
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun getSystemStatusJson(): JSONObject {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val maxMem = runtime.maxMemory()
        val usedMem = totalMem - freeMem

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val uptimeSec = (System.currentTimeMillis() - _uptimeStart.value) / 1000

        return JSONObject().apply {
            put("uptimeSeconds", uptimeSec)
            put("ramUsedMb", usedMem / (1024 * 1024))
            put("ramTotalAvailableMb", maxMem / (1024 * 1024))
            put("cpuCount", runtime.availableProcessors())
            put("batteryChargePercent", batteryPct)
            put("osVersion", "Android " + Build.VERSION.RELEASE)
            put("deviceBrand", Build.BRAND)
            put("deviceModel", Build.MODEL)
        }
    }

    private suspend fun forwardMessageToTelegram(message: String) {
        val db = AppDatabase.getInstance(this@ServerService)
        val config = db.configDao().getConfig() ?: return
        if (config.telegramToken.isBlank() || config.telegramChatId.isBlank()) return

        withContext(Dispatchers.IO) {
            try {
                val text = "🔔 [ALERT] POST /message:\n$message"
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val urlString = "https://api.telegram.org/bot${config.telegramToken}/sendMessage?chat_id=${config.telegramChatId}&text=$encodedText"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startTelegramPolling(token: String, chatId: String) {
        telegramJob?.cancel()
        telegramJob = serviceScope.launch(Dispatchers.IO) {
            var lastUpdateId = 0
            while (isActive) {
                try {
                    val urlString = "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
                    val url = URL(urlString)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 35000
                    conn.readTimeout = 35000

                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val response = reader.readText()
                        reader.close()

                        val json = JSONObject(response)
                        if (json.optBoolean("ok", false)) {
                            val results = json.optJSONArray("result") ?: continue
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val updateId = item.getInt("update_id")
                                if (updateId > lastUpdateId) {
                                    lastUpdateId = updateId
                                }

                                val message = item.optJSONObject("message") ?: continue
                                val from = message.optJSONObject("from") ?: continue
                                val senderId = from.optLong("id").toString()

                                // Verify sender ID matches config
                                if (senderId != chatId) {
                                    continue
                                }

                                val text = message.optString("text", "")
                                if (text.startsWith("/")) {
                                    handleTelegramCommand(text, token, chatId)
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    e.printStackTrace()
                    delay(6000)
                }
                delay(1200)
            }
        }
    }

    private suspend fun handleTelegramCommand(command: String, token: String, chatId: String) {
        val reply = when {
            command.startsWith("/start") -> {
                "🤖 Chào mừng bạn đến với Server Mini Bot! 🚀\nGửi lệnh /ping hoặc /status để kiểm tra hoạt động."
            }
            command.startsWith("/ping") -> {
                "Pong! Server mini đang chạy bình thường trên thiết bị Android của bạn. 📡"
            }
            command.startsWith("/status") -> {
                val status = getSystemStatusJson()
                val df = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
                val formattedDate = df.format(Date(uptimeStart.value))

                "📊 *TRẠNG THÁI SERVER MINI* 📊\n\n" +
                "🔋 *Pin:* ${status.getInt("batteryChargePercent")}%\n" +
                "🧠 *RAM:* ${status.getLong("ramUsedMb")}MB / ${status.getLong("ramTotalAvailableMb")}MB\n" +
                "⚡ *CPU:* ${status.getInt("cpuCount")} Cores\n" +
                "⏱️ *Uptime:* ${status.getLong("uptimeSeconds")} giây\n" +
                "📅 *Online từ:* $formattedDate\n" +
                "📱 *Thiết bị:* ${status.getString("deviceBrand")} ${status.getString("deviceModel")}\n" +
                "🔌 *Địa chỉ IP:* ${localIp.value}:${port.value}"
            }
            else -> {
                "Lệnh không được hỗ trợ! Vui lòng sử dụng: /start, /ping, /status"
            }
        }

        sendTelegramReply(reply, token, chatId)
    }

    private suspend fun sendTelegramReply(text: String, token: String, chatId: String) {
        withContext(Dispatchers.IO) {
            try {
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val urlString = "https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encodedText&parse_mode=markdown"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun forwardMessageToDiscord(message: String) {
        val db = AppDatabase.getInstance(this@ServerService)
        val config = db.configDao().getConfig() ?: return
        if (config.discordToken.isBlank() || config.discordChannelId.isBlank()) return

        withContext(Dispatchers.IO) {
            try {
                val text = "🔔 [ALERT] POST /message:\n$message"
                sendDiscordReply(text, config.discordToken, config.discordChannelId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startDiscordPolling(token: String, channelId: String) {
        discordJob?.cancel()
        discordHeartbeatJob?.cancel()
        discordReconnectJob?.cancel()
        try {
            discordWebSocket?.close(1000, "Reconnecting or stopping")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        discordWebSocket = null

        discordJob = serviceScope.launch(Dispatchers.IO) {
            // Confirm the token is valid with a direct call first
            var botName = "Discord Bot"
            var checkConn: HttpURLConnection? = null
            try {
                val url = URL("https://discord.com/api/v10/users/@me")
                checkConn = url.openConnection() as HttpURLConnection
                checkConn.requestMethod = "GET"
                checkConn.setRequestProperty("Authorization", "Bot $token")
                checkConn.setRequestProperty("User-Agent", "AndroidMiniServer (1.0)")
                checkConn.connectTimeout = 10000
                checkConn.readTimeout = 10000
                if (checkConn.responseCode == 200) {
                    val r = BufferedReader(InputStreamReader(checkConn.inputStream))
                    val resp = r.readText()
                    r.close()
                    val meJson = JSONObject(resp)
                    botName = "${meJson.optString("username")}#${meJson.optString("discriminator", "0000")}"
                    insertSystemLog("DISCORD", "[Bot Online]", "🤖 Bot Discord [$botName] trực tuyến thành công! Đang kết nối Gateway...", 200)
                    
                    sendDiscordReply("🟢 **[Hệ Thống]** Discord Bot **$botName** đã kích hoạt thành công trên Mini Server Android!\n🔄 **Trạng thái:** LUÔN LUÔN ONLINE (Daemon Service với CPU WakeLock và WebSocket Gateway)\n📡 Hãy gõ `!ping` hoặc `ping` để kiểm tra độ trễ!", token, channelId)
                } else {
                    insertSystemLog("DISCORD", "[Bot Error]", "❌ Lỗi xác lập Token Discord (Mã HTTP: ${checkConn.responseCode})", 500)
                    return@launch
                }
            } catch (e: Exception) {
                insertSystemLog("DISCORD", "[Bot Error]", "❌ Lỗi kết nối API khởi tạo Discord: ${e.message}", 500)
                return@launch
            } finally {
                checkConn?.disconnect()
            }

            // Establish OkHttp WebSocket connection
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("wss://gateway.discord.gg/?v=10&encoding=json")
                .build()

            val listener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    insertSystemLog("DISCORD", "[Gateway Open]", "⚡ Kết nối thành công tới Discord Gateway WebSocket!", 200)
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val op = json.optInt("op", -1)
                        when (op) {
                            10 -> { // Hello
                                val d = json.optJSONObject("d")
                                val heartbeatInterval = d?.optLong("heartbeat_interval", 41250L) ?: 41250L
                                
                                // Start heartbeat loop
                                discordHeartbeatJob?.cancel()
                                discordHeartbeatJob = serviceScope.launch(Dispatchers.IO) {
                                    while (isActive) {
                                        delay(heartbeatInterval)
                                        try {
                                            val hb = JSONObject().apply {
                                                put("op", 1)
                                                put("d", JSONObject.NULL)
                                            }.toString()
                                            webSocket.send(hb)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }

                                // Send Identify Payload
                                val identify = JSONObject().apply {
                                    put("op", 2)
                                    put("d", JSONObject().apply {
                                        put("token", token)
                                        put("intents", 33281) // Guilds (1) + Guild Messages (512) + Message Content (32768)
                                        put("properties", JSONObject().apply {
                                            put("os", "android")
                                            put("browser", "AndroidMiniServer")
                                            put("device", "AndroidMiniServer")
                                        })
                                    })
                                }.toString()
                                webSocket.send(identify)
                            }
                            0 -> { // Dispatch
                                val t = json.optString("t", "")
                                if (t == "MESSAGE_CREATE") {
                                    val d = json.optJSONObject("d") ?: return
                                    val channelIdFromMsg = d.optString("channel_id", "")
                                    if (channelIdFromMsg == channelId) {
                                        val author = d.optJSONObject("author")
                                        val isBot = author?.optBoolean("bot", false) ?: false
                                        if (!isBot) {
                                            val content = d.optString("content", "").trim()
                                            val authorName = author?.optString("username", "Ai đó") ?: "Ai đó"
                                            
                                            val contentLower = content.lowercase()
                                            val isCommand = contentLower == "ping" || contentLower == "!ping" || contentLower.startsWith("/ping") ||
                                                            contentLower == "status" || contentLower == "!status" || contentLower.startsWith("/status") ||
                                                            contentLower == "start" || contentLower == "!start" || contentLower.startsWith("/start") ||
                                                            contentLower == "help" || contentLower == "!help" || contentLower.startsWith("/help")

                                            if (isCommand) {
                                                insertSystemLog("DISCORD", "[Incoming]", "Nhận lệnh '$content' từ $authorName", 200)
                                                serviceScope.launch {
                                                    handleDiscordCommand(content, token, channelId, authorName)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    insertSystemLog("DISCORD", "[Gateway Closed]", "🔌 Discord Gateway closed ($code: $reason)", 200)
                    scheduleDiscordReconnect(token, channelId)
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    insertSystemLog("DISCORD", "[Gateway Error]", "❌ Lỗi Discord Gateway: ${t.message}", 500)
                    scheduleDiscordReconnect(token, channelId)
                }
            }

            discordWebSocket = client.newWebSocket(request, listener)
        }
    }

    private fun scheduleDiscordReconnect(token: String, channelId: String) {
        discordReconnectJob?.cancel()
        discordReconnectJob = serviceScope.launch {
            delay(8000) // Delay 8 seconds before retrying
            insertSystemLog("DISCORD", "[Reconnecting]", "🔄 Đang tự động kết nối lại tới Discord Gateway...", 200)
            startDiscordPolling(token, channelId)
        }
    }

    private suspend fun handleDiscordCommand(command: String, token: String, channelId: String, authorName: String) {
        val cleanCommand = command.lowercase().trim()
        val reply = when {
            cleanCommand == "start" || cleanCommand == "!start" || cleanCommand.startsWith("/start") -> {
                "🤖 Chào mừng bạn đến với Server Mini Discord Bot! 🚀\nGửi lệnh `!ping` hoặc `!status` để kiểm tra hệ thống."
            }
            cleanCommand == "ping" || cleanCommand == "!ping" || cleanCommand.startsWith("/ping") -> {
                "🏓 **Pong!**\n📶 **Đường truyền:** 🟢 Ổn định\n📱 **Server OS:** Android Mini Server\n🔋 **Tình trạng:** Hoạt động liên tục (Always-on Daemon mode)"
            }
            cleanCommand == "status" || cleanCommand == "!status" || cleanCommand.startsWith("/status") -> {
                val status = getSystemStatusJson()
                val df = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
                val formattedDate = df.format(Date(uptimeStart.value))

                "📊 **TRẠNG THÁI SERVER MINI** 📊\n\n" +
                "🔋 **Pin:** ${status.optInt("batteryChargePercent", 100)}%\n" +
                "🧠 **RAM:** ${status.optLong("ramUsedMb", 0)}MB / ${status.optLong("ramTotalAvailableMb", 3000)}MB\n" +
                "⚡ **CPU:** ${status.optInt("cpuCount", 8)} Cores\n" +
                "⏱️ **Uptime:** ${status.optLong("uptimeSeconds", 0)} giây\n" +
                "📅 **Online từ:** $formattedDate\n" +
                "📱 **Thiết bị:** ${status.optString("deviceBrand", "Android")} ${status.optString("deviceModel", "Device")}\n" +
                "🔌 **Địa chỉ IP:** ${localIp.value}:${port.value}"
            }
            cleanCommand == "help" || cleanCommand == "!help" || cleanCommand.startsWith("/help") -> {
                "🛠️ **DANH SÁCH LỆNH COMMANDS** 🛠️\n\n" +
                "👉 `!ping` hoặc `ping`: Kiểm tra tốc độ & phản hồi của Bot.\n" +
                "👉 `!status` hoặc `status`: Xem thông tin tài nguyên cấu hình Server, RAM, CPU, Pin.\n" +
                "👉 `!start`/`!help`: Hiển thị bảng trợ giúp này."
            }
            else -> {
                "Lệnh không được hỗ trợ! Vui lòng sử dụng: `!ping`, `!status`, `!help`"
            }
        }
        
        insertSystemLog("DISCORD", "[Outgoing]", "Đã phản hồi cho $authorName", 200)
        sendDiscordReply(reply, token, channelId)
    }

    private suspend fun sendDiscordReply(text: String, token: String, channelId: String) {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val urlString = "https://discord.com/api/v10/channels/$channelId/messages"
                val url = URL(urlString)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bot $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "AndroidMiniServer (1.0)")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("content", text)
                }.toString()

                val os = conn.outputStream
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()

                conn.responseCode
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mini Server Network Service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val clickIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Server Mini Đang Chạy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(text)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startPythonBotProcess(code: String, pythonPath: String) {
        stopPythonBotProcess()
        pythonProcessJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(filesDir, "bot_script.py")
                file.writeText(code)

                insertSystemLog("SYSTEM", "[Launcher]", "Đang chuẩn bị khởi chạy Bot Python...", 200)

                val executable = if (pythonPath.isBlank()) "python3" else pythonPath
                // Execute via shell 'sh -c' because direct binary invocation can have env/permission challenges on Android
                val cmdString = "$executable ${file.absolutePath}"
                val builder = ProcessBuilder("sh", "-c", cmdString)
                builder.directory(filesDir)
                builder.redirectErrorStream(true)

                val process = builder.start()
                pythonProcess = process

                insertSystemLog("SYSTEM", "[Launcher]", "Bot Python đã khởi chạy thành công! (Lệnh: $cmdString)", 200)

                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line: String?
                while (isActive) {
                    line = reader.readLine()
                    if (line == null) break
                    if (line.isNotBlank()) {
                        insertSystemLog("PYTHON", "[Bot Out]", line, 200)
                    }
                }

                val exitVal = process.waitFor()
                insertSystemLog("SYSTEM", "[Launcher]", "Bot Python dừng hoạt động. (Mã thoát: $exitVal)", if (exitVal == 0) 200 else 500)
            } catch (e: Exception) {
                e.printStackTrace()
                val msg = e.localizedMessage ?: "Unknown error"
                insertSystemLog("SYSTEM", "[Python Error]", "❌ Lỗi khởi chạy Bot: $msg", 500)
            }
        }
    }

    private fun getProcessPid(process: Process): String {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.get(process).toString()
        } catch (e: Exception) {
            "Active"
        }
    }

    private fun stopPythonBotProcess() {
        pythonProcessJob?.cancel()
        pythonProcessJob = null
        try {
            pythonProcess?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pythonProcess = null
    }

    private fun insertSystemLog(method: String, endpoint: String, text: String, statusCode: Int) {
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@ServerService)
                val logEntity = LogEntity(
                    method = method,
                    endpoint = endpoint,
                    ip = "127.0.0.1",
                    responseBody = text,
                    statusCode = statusCode,
                    durationMs = 0L
                )
                db.logDao().insertLog(logEntity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
