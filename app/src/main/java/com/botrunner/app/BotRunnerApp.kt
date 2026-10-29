package com.botrunner.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.Python

class BotRunnerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Running bots",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of bots currently running in the background"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "bot_runner_status"
    }
}
