package com.botrunner.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BotDao {
    @Query("SELECT * FROM bots ORDER BY name")
    fun observeAll(): Flow<List<BotConfig>>

    @Query("SELECT * FROM bots WHERE id = :id")
    suspend fun get(id: Long): BotConfig?

    @Query("SELECT * FROM bots WHERE startOnBoot = 1")
    suspend fun bootBots(): List<BotConfig>

    @Insert
    suspend fun insert(bot: BotConfig): Long

    @Update
    suspend fun update(bot: BotConfig)

    @Delete
    suspend fun delete(bot: BotConfig)
}
