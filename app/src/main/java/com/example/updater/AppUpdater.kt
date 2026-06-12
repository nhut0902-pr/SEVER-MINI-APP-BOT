package com.example.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {

    // Please replace with your actual Github Repository path here!
    private const val GITHUB_REPO = "nhut0902-pr/SEVER-MINI-APP-BOT" 
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                
                val tagName = json.getString("tag_name")
                val newVersion = tagName.removePrefix("v")
                
                val currentVersionClean = currentVersion.removePrefix("v")
                
                if (newVersion != currentVersionClean) {
                    val body = json.getString("body")
                    val assets = json.getJSONArray("assets")
                    if (assets.length() > 0) {
                        val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
                        return@withContext UpdateInfo(newVersion, body, downloadUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AppUpdater", "Error checking for updates", e)
        }
        null
    }

    fun downloadAndInstallUpdate(context: Context, downloadUrl: String, newVersion: String) {
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
        request.setTitle("Cập nhật ứng dụng - v$newVersion")
        request.setDescription("Đang tải xuống bản cập nhật mới...")
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update_$newVersion.apk")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(context, downloadId)
                    context.unregisterReceiver(this)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, downloadId: Long) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                if (uriIndex >= 0) {
                    val uriString = cursor.getString(uriIndex)
                    val file = File(Uri.parse(uriString).path!!)
                    
                    val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    
                    val install = Intent(Intent.ACTION_VIEW)
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    install.setDataAndType(contentUri, "application/vnd.android.package-archive")
                    
                    context.startActivity(install)
                }
            }
        }
        cursor.close()
    }
}

data class UpdateInfo(val version: String, val releaseNotes: String, val downloadUrl: String)
