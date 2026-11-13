package com.phoneinteraction.free.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.phoneinteraction.free.data.model.ExecutionRecordEntity
import com.phoneinteraction.free.data.model.QueueRunStateEntity

@Dao
interface ExecutionRecordDao {

    @Insert
    suspend fun insert(record: ExecutionRecordEntity): Long

    // Query recent execution records for diagnostic page
    @Query("SELECT * FROM execution_record ORDER BY executionTime DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ExecutionRecordEntity>

    // Remove unused queries (getByActionId, getRecent had zero call sites)
    // Keep only the most recent N records to prevent unbounded growth
    @Query(
        "DELETE FROM execution_record WHERE recordId NOT IN " +
            "(SELECT recordId FROM execution_record ORDER BY executionTime DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM execution_record")
    suspend fun deleteAll()
}

@Dao
interface QueueRunStateDao {

    @Upsert
    suspend fun upsert(state: QueueRunStateEntity)

    @Query("SELECT * FROM queue_run_state WHERE queueId = :queueId")
    suspend fun getByQueueId(queueId: Long): QueueRunStateEntity?

    @Query("SELECT * FROM queue_run_state ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(): QueueRunStateEntity?

    @Query("DELETE FROM queue_run_state WHERE queueId = :queueId")
    suspend fun deleteByQueueId(queueId: Long)
}
