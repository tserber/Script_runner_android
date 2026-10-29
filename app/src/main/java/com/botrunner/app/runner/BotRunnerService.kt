package com.botrunner.app.runner

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.botrunner.app.BotRunnerApp
import com.botrunner.app.MainActivity
import com.botrunner.app.data.AppDatabase
import com.botrunner.app.data.SecretsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps bots running after the user leaves the app. All actual execution
 * happens in BotRunnerManager (a plain singleton); this service's job is to
 * (a) hold a foreground notification so Android doesn't kill the process,
 * and (b) react to ACTION_START/STOP/RESTART intents fired from the UI or
 * from the notification's own action buttons.
 */
class BotRunnerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcherJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        watcherJob = scope.launch {
            BotRunnerManager.states.collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> scope.launch { startBot(intent.getLongExtra(EXTRA_BOT_ID, -1)) }
            ACTION_STOP -> BotRunnerManager.stop(intent.getLongExtra(EXTRA_BOT_ID, -1))
            ACTION_RESTART -> scope.launch { restartBot(intent.getLongExtra(EXTRA_BOT_ID, -1)) }
            ACTION_STOP_ALL -> BotRunnerManager.states.value.keys.forEach { BotRunnerManager.stop(it) }
        }

        // If nothing ends up running shortly after a one-off command, let the
        // service (and its notification) go away instead of lingering.
        scope.launch {
            kotlinx.coroutines.delay(3_000)
            if (!BotRunnerManager.hasAnyRunning()) stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        watcherJob?.cancel()
        super.onDestroy()
    }

    private suspend fun startBot(botId: Long) {
        if (botId < 0) return
        val bot = AppDatabase.get(applicationContext).botDao().get(botId) ?: return
        val env = SecretsStore(applicationContext).resolveEnv(botId, bot.envKeys)
        BotRunnerManager.start(bot, env)
    }

    private suspend fun restartBot(botId: Long) {
        if (botId < 0) return
        val bot = AppDatabase.get(applicationContext).botDao().get(botId) ?: return
        val env = SecretsStore(applicationContext).resolveEnv(botId, bot.envKeys)
        BotRunnerManager.restart(bot, env)
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val running = BotRunnerManager.states.value.values.count {
            it.status == BotStatus.RUNNING || it.status == BotStatus.STARTING
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAllIntent = PendingIntent.getService(
            this, 1, Intent(this, BotRunnerService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, BotRunnerApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Bot Runner")
            .setContentText(if (running > 0) "$running bot(s) running" else "No bots running")
            .setOngoing(running > 0)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop all", stopAllIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.botrunner.app.action.START"
        const val ACTION_STOP = "com.botrunner.app.action.STOP"
        const val ACTION_RESTART = "com.botrunner.app.action.RESTART"
        const val ACTION_STOP_ALL = "com.botrunner.app.action.STOP_ALL"
        const val EXTRA_BOT_ID = "bot_id"
        private const val NOTIFICATION_ID = 42

        private fun intent(context: Context, action: String, botId: Long?): Intent =
            Intent(context, BotRunnerService::class.java).apply {
                this.action = action
                if (botId != null) putExtra(EXTRA_BOT_ID, botId)
            }

        fun startBot(context: Context, botId: Long) =
            context.startForegroundService(intent(context, ACTION_START, botId))

        fun stopBot(context: Context, botId: Long) =
            context.startForegroundService(intent(context, ACTION_STOP, botId))

        fun restartBot(context: Context, botId: Long) =
            context.startForegroundService(intent(context, ACTION_RESTART, botId))
    }
}

private typealias NotificationManager = android.app.NotificationManager
