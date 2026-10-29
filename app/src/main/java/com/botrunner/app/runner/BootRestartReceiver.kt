package com.botrunner.app.runner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.botrunner.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            AppDatabase.get(appContext).botDao().bootBots().forEach { bot ->
                BotRunnerService.startBot(appContext, bot.id)
            }
        }
    }
}
