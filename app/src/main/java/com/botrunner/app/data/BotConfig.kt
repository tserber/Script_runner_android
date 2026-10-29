package com.botrunner.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One configured bot. [pythonModule] must be an importable module under
 * app/src/main/python (e.g. "examples.telegram_echo_bot") that defines:
 *
 *     def main(stop_checker, env: dict) -> None
 *
 * and returns when stop_checker.isStopped() becomes true. Secrets referenced
 * by name in [envKeys] are resolved from SecretsStore at launch time, never
 * stored on this entity.
 */
@Entity(tableName = "bots")
data class BotConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pythonModule: String,
    val envKeys: List<String> = emptyList(),
    val autoRestartOnCrash: Boolean = true,
    val startOnBoot: Boolean = false,
    val maxRestarts: Int = 5
)
