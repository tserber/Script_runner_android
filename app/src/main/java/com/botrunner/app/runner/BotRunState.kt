package com.botrunner.app.runner

enum class BotStatus { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

data class BotRunState(
    val botId: Long,
    val status: BotStatus = BotStatus.STOPPED,
    val startedAtMillis: Long? = null,
    val restartCount: Int = 0,
    val lastError: String? = null
)
