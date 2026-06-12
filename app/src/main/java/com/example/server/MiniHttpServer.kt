package com.example.server

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class MiniHttpServer(
    private val port: Int,
    private val apiKey: String?,
    private val rateLimiter: RateLimiter,
    private val onLogReceived: (method: String, endpoint: String, ip: String, responseBody: String, statusCode: Int, durationMs: Long) -> Unit,
    private val systemStatusProvider: () -> JSONObject,
    private val onMessageReceived: (message: String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        serverScope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isActive && isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverScope.cancel()
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val ip = socket.inetAddress.hostAddress ?: "Unknown"
        var method = ""
        var path = ""
        var responseCode = 200
        var responseBody = ""

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output: OutputStream = socket.outputStream

            // Read absolute first request header line
            val reqLine = reader.readLine()
            if (reqLine.isNullOrBlank()) {
                socket.close()
                return@withContext
            }

            val parts = reqLine.split(" ")
            if (parts.size < 2) {
                sendResponse(output, 400, "Bad Request", "text/plain")
                socket.close()
                return@withContext
            }
            method = parts[0]
            path = parts[1]

            // Parse individual headers
            var line: String?
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val key = headerParts[0].trim().lowercase()
                    val value = headerParts[1].trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // Read response content body payload
            var body = ""
            if (contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val read = reader.read(charBuffer, bytesRead, contentLength - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                body = String(charBuffer)
            }

            // Check security rate limiter status
            if (!rateLimiter.allowRequest(ip)) {
                responseCode = 429
                responseBody = JSONObject()
                    .put("error", "Too Many Requests")
                    .put("message", "Rate limit exceeded. Max 10 reqs/min.")
                    .toString()
                sendResponse(output, 429, responseBody, "application/json")
                val duration = System.currentTimeMillis() - startTime
                onLogReceived(method, path, ip, "Rate Limit Triggered", 429, duration)
                socket.close()
                return@withContext
            }

            // Check custom X-API-Key middleware security rules
            if (!apiKey.isNullOrBlank()) {
                val providedKey = headers["x-api-key"]
                if (providedKey != apiKey) {
                    responseCode = 401
                    responseBody = JSONObject()
                        .put("error", "Unauthorized")
                        .put("message", "Missing or invalid security API Key in X-API-Key header.")
                        .toString()
                    sendResponse(output, 401, responseBody, "application/json")
                    val duration = System.currentTimeMillis() - startTime
                    onLogReceived(method, path, ip, "Unauthorized Access Blocked", 401, duration)
                    socket.close()
                    return@withContext
                }
            }

            // Process dynamic routing parameters
            val cleanPath = path.substringBefore("?")

            when {
                // GET /
                method == "GET" && cleanPath == "/" -> {
                    responseCode = 200
                    responseBody = JSONObject()
                        .put("appName", "Android Mini Server")
                        .put("status", "ONLINE")
                        .put("endpoints", JSONObject()
                            .put("GET /", "Chào mừng và trợ giúp")
                            .put("GET /health", "Xem sức khỏe hệ thống")
                            .put("GET /status", "Xem thông tin chi tiết RAM/CPU/PIN/Uptime")
                            .put("POST /message", "Gửi tin nhắn Telegram và Database Logs")
                        )
                        .toString()
                    sendResponse(output, responseCode, responseBody, "application/json")
                }

                // GET /health
                method == "GET" && cleanPath == "/health" -> {
                    responseCode = 200
                    responseBody = JSONObject()
                        .put("status", "UP")
                        .put("timestamp", System.currentTimeMillis())
                        .toString()
                    sendResponse(output, responseCode, responseBody, "application/json")
                }

                // GET /status
                method == "GET" && cleanPath == "/status" -> {
                    responseCode = 200
                    val stats = systemStatusProvider()
                    responseBody = stats.toString()
                    sendResponse(output, responseCode, responseBody, "application/json")
                }

                // POST /message
                method == "POST" && cleanPath == "/message" -> {
                    var parsedMsg = ""
                    try {
                        val json = JSONObject(body)
                        parsedMsg = json.optString("message", body)
                    } catch (e: Exception) {
                        parsedMsg = body
                    }

                    if (parsedMsg.isBlank()) {
                        responseCode = 400
                        responseBody = JSONObject()
                            .put("error", "Bad Request")
                            .put("message", "Post body message payload must not be blank.")
                            .toString()
                        sendResponse(output, responseCode, responseBody, "application/json")
                    } else {
                        onMessageReceived(parsedMsg)
                        responseCode = 200
                        responseBody = JSONObject()
                            .put("status", "SUCCESS")
                            .put("message", "Message received and dispatched processing tasks.")
                            .toString()
                        sendResponse(output, responseCode, responseBody, "application/json")
                    }
                }

                // 404 NOT FOUND
                else -> {
                    responseCode = 404
                    responseBody = JSONObject()
                        .put("error", "Not Found")
                        .put("message", "Endpoint matches no route registrations.")
                        .toString()
                    sendResponse(output, responseCode, responseBody, "application/json")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                responseCode = 500
                responseBody = JSONObject()
                    .put("error", "Internal Server Error")
                    .put("message", e.localizedMessage ?: "Unknown handler exception.")
                    .toString()
                sendResponse(socket.outputStream, responseCode, responseBody, "application/json")
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val duration = System.currentTimeMillis() - startTime
            if (method.isNotEmpty()) {
                onLogReceived(method, path, ip, responseBody, responseCode, duration)
            }
        }
    }

    private fun sendResponse(out: OutputStream, code: Int, body: String, contentType: String) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType; charset=UTF-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
