package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val db = AppDatabase.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                val config = db.configDao().getConfig()
                // If the server config indicates it should be started, auto-launch
                if (config != null && config.serverStarted) {
                    ServerService.startService(context)
                }
            }
        }
    }
}
