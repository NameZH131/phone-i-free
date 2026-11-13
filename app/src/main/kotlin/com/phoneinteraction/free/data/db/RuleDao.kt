package com.phoneinteraction.free.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.phoneinteraction.free.data.model.RuleEntity

@Dao
interface RuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rule WHERE id = :ruleId")
    suspend fun deleteById(ruleId: Long)

    @Query("SELECT * FROM rule ORDER BY priority DESC, updatedAt DESC")
    suspend fun getAll(): List<RuleEntity>

    // Delete rules referencing this action when action is deleted
    @Query("DELETE FROM rule WHERE actionId = :actionId")
    suspend fun deleteByActionId(actionId: Long)

    // Delete rules referencing this queue when queue is deleted
    @Query("DELETE FROM rule WHERE actionQueueId = :queueId")
    suspend fun deleteByQueueId(queueId: Long)

    // Count rules referencing an action (to warn about sync-before-edit scope)
    @Query("SELECT COUNT(*) FROM rule WHERE actionId = :actionId")
    suspend fun countByActionId(actionId: Long): Int
}
