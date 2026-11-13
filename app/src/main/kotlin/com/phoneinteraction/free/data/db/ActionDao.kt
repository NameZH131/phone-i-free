package com.phoneinteraction.free.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.phoneinteraction.free.data.model.ActionEntity
import com.phoneinteraction.free.data.model.ActionStepEntity
import com.phoneinteraction.free.data.model.ActionWithSteps
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionDao {

    @Insert
    suspend fun insert(action: ActionEntity): Long

    @Insert
    suspend fun insertSteps(steps: List<ActionStepEntity>): List<Long>

    @Update
    suspend fun update(action: ActionEntity)

    @Query("DELETE FROM action WHERE id = :actionId")
    suspend fun deleteById(actionId: Long)

    // Rebuild steps when floating window drag changes SWIPE path
    @Query("DELETE FROM action_step WHERE actionId = :actionId")
    suspend fun deleteStepsByActionId(actionId: Long)

    @Transaction
    @Query("SELECT * FROM action WHERE id = :actionId")
    suspend fun getByIdWithSteps(actionId: Long): ActionWithSteps?

    @Transaction
    @Query("SELECT * FROM action ORDER BY id DESC")
    suspend fun getAllWithSteps(): List<ActionWithSteps>

    // Sync across screens: floating window action edits should reflect in action library immediately
    @Transaction
    @Query("SELECT * FROM action ORDER BY id DESC")
    fun observeAllWithSteps(): Flow<List<ActionWithSteps>>

    // Performance: batch query by ids instead of reading entire action table
    @Transaction
    @Query("SELECT * FROM action WHERE id IN (:ids)")
    suspend fun getByIdsWithSteps(ids: List<Long>): List<ActionWithSteps>

    // Orphan cleanup: actions not referenced by any queue or rule
    // Must exclude NULL actionId in rule table - NULL would make NOT IN always true per SQL 3VL
    @Transaction
    @Query(
        "SELECT * FROM action " +
            "WHERE id NOT IN (SELECT actionId FROM queue_item) " +
            "AND id NOT IN (SELECT actionId FROM rule WHERE actionId IS NOT NULL) " +
            "ORDER BY id DESC"
    )
    suspend fun getUnreferencedActionsWithSteps(): List<ActionWithSteps>
}
