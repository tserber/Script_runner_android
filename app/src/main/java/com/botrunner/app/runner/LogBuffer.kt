package com.botrunner.app.runner

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Per-bot in-memory ring buffer of recent stdout/stderr lines, newest last. */
class LogBuffer(private val capacity: Int = 500) {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>(capacity)
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow

    @Synchronized
    fun append(raw: String) {
        raw.split("\n").filter { it.isNotEmpty() }.forEach { line ->
            if (lines.size >= capacity) lines.removeFirst()
            lines.addLast("${timeFmt.format(Date())}  $line")
        }
        _flow.value = lines.toList()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _flow.value = emptyList()
    }

    companion object {
        private val buffers = ConcurrentHashMap<Long, LogBuffer>()
        fun forBot(botId: Long): LogBuffer = buffers.computeIfAbsent(botId) { LogBuffer() }
    }
}
