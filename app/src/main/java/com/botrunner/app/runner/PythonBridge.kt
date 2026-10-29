package com.botrunner.app.runner

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Passed into the bot's `main(stop_checker, env)` as `stop_checker`. Chaquopy
 * exposes any public Kotlin/Java method to Python by duck typing, so the bot
 * script just calls `stop_checker.isStopped()` in its loop condition — no
 * Python-side threading primitives need to cross the JNI boundary.
 */
class StopChecker {
    private val stopped = AtomicBoolean(false)
    fun isStopped(): Boolean = stopped.get()
    fun requestStop() = stopped.set(true)
}

/**
 * Passed into the bot script as a stdout/stderr replacement:
 *     sys.stdout = sys.stderr = log_sink
 * Python's `print()` calls `.write(str)` then occasionally `.flush()`, both of
 * which Chaquopy resolves straight onto this object's public methods.
 */
class LogSink(private val onLine: (String) -> Unit) {
    fun write(s: String) {
        if (s.isNotEmpty()) onLine(s)
    }
    fun flush() { /* no-op, lines are forwarded immediately */ }
}
