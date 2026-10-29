package com.botrunner.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys / tokens live here only, keyed as "<botId>:<envKeyName>", backed by
 * AES256-GCM via Android Keystore. Never logged, never put in BotConfig, never
 * included in backups (see backup_rules.xml / data_extraction_rules.xml).
 */
class SecretsStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "bot_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun set(botId: Long, key: String, value: String) {
        prefs.edit().putString("$botId:$key", value).apply()
    }

    fun get(botId: Long, key: String): String? = prefs.getString("$botId:$key", null)

    fun resolveEnv(botId: Long, keys: List<String>): Map<String, String> =
        keys.associateWith { get(botId, it) ?: "" }

    fun delete(botId: Long, key: String) {
        prefs.edit().remove("$botId:$key").apply()
    }

    fun deleteAllForBot(botId: Long, keys: List<String>) {
        val editor = prefs.edit()
        keys.forEach { editor.remove("$botId:$it") }
        editor.apply()
    }
}
