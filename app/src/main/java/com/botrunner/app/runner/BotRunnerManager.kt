package com.botrunner.app.runner

import android.util.Log
import com.botrunner.app.data.BotConfig
import com.chaquo.python.Python
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the actual Python execution. One dedicated daemon thread per running
 * bot, calling straight into the embedded CPython via Chaquopy. State and
 * logs are exposed as StateFlows for the UI and the foreground service to
 * observe; nothing here depends on Android UI classes so it survives
 * Activity recreation and keeps running as long as the process (and its
 * foreground service) is alive.
 */
object BotRunnerManager {

    private const val TAG = "BotRunnerManager"

    private val stopCheckers = ConcurrentHashMap<Long, StopChecker>()
    private val threads = ConcurrentHashMap<Long, Thread>()

    private val _states = MutableStateFlow<Map<Long, BotRunState>>(emptyMap())
    val states: StateFlow<Map<Long, BotRunState>> = _states

    fun stateOf(botId: Long): BotRunState = _states.value[botId] ?: BotRunState(botId)

    fun hasAnyRunning(): Boolean =
        _states.value.values.any { it.status == BotStatus.RUNNING || it.status == BotStatus.STARTING }

    fun start(bot: BotConfig, env: Map<String, String>) {
        if (threads[bot.id]?.isAlive == true) return

        val checker = StopChecker()
        stopCheckers[bot.id] = checker
        setState(bot.id) { it.copy(status = BotStatus.STARTING, lastError = null) }

        val thread = Thread({
            runBotBlocking(bot, env, checker)
        }, "bot-${bot.id}-${bot.name}")
        thread.isDaemon = true
        threads[bot.id] = thread
        thread.start()
    }

    fun stop(botId: Long) {
        setState(botId) { it.copy(status = BotStatus.STOPPING) }
        stopCheckers[botId]?.requestStop()
    }

    fun restart(bot: BotConfig, env: Map<String, String>) {
        stop(bot.id)
        Thread {
            threads[bot.id]?.join(5_000)
            start(bot, env)
        }.start()
    }

    private fun runBotBlocking(bot: BotConfig, env: Map<String, String>, checker: StopChecker) {
        val buffer = LogBuffer.forBot(bot.id)
        val sink = LogSink { line -> buffer.append(line) }
        try {
            val py = Python.getInstance()
            val sys = py.getModule("sys")
            val previousOut = sys.get("stdout")
            val previousErr = sys.get("stderr")
            sys.put("stdout", sink)
            sys.put("stderr", sink)

            setState(bot.id) { it.copy(status = BotStatus.RUNNING, startedAtMillis = System.currentTimeMillis()) }
            buffer.append("[runner] starting ${bot.pythonModule}")

            try {
                val module = py.getModule(bot.pythonModule)
                module.callAttr("main", checker, env)
            } finally {
                sys.put("stdout", previousOut)
                sys.put("stderr", previousErr)
            }

            val wasStoppingOnPurpose = stateOf(bot.id).status == BotStatus.STOPPING
            buffer.append("[runner] stopped")
            setState(bot.id) {
                it.copy(
                    status = BotStatus.STOPPED,
                    lastError = if (wasStoppingOnPurpose) null else it.lastError
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bot ${bot.id} crashed", t)
            buffer.append("[runner] CRASHED: ${t.message}")
            val current = stateOf(bot.id)
            val restarts = current.restartCount + 1
            setState(bot.id) {
                it.copy(status = BotStatus.CRASHED, lastError = t.message ?: t.toString(), restartCount = restarts)
            }
            if (bot.autoRestartOnCrash && restarts <= bot.maxRestarts) {
                buffer.append("[runner] auto-restarting (attempt $restarts/${bot.maxRestarts})")
                Thread.sleep(2_000)
                start(bot, env)
            }
        } finally {
            threads.remove(bot.id)
            stopCheckers.remove(bot.id)
        }
    }

    private fun setState(botId: Long, update: (BotRunState) -> BotRunState) {
        _states.update { current ->
            val existing = current[botId] ?: BotRunState(botId)
            current + (botId to update(existing))
        }
    }
}
