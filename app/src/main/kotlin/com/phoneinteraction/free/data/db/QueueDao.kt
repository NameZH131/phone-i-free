package com.phoneinteraction.free.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.phoneinteraction.free.data.model.ActionQueueEntity
import com.phoneinteraction.free.data.model.QueueItemEntity
import com.phoneinteraction.free.data.model.QueueWithItems
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueue(queue: ActionQueueEntity): Long

    @Update
    suspend fun updateQueue(queue: ActionQueueEntity)

    @Query("SELECT * FROM action_queue WHERE id = :queueId")
    suspend fun getQueueById(queueId: Long): ActionQueueEntity?

    @Transaction
    @Query("SELECT * FROM action_queue ORDER BY id DESC")
    suspend fun getAllQueuesWithItems(): List<QueueWithItems>

    // Observe all queues with Flow for cross-screen sync
    @Transaction
    @Query("SELECT * FROM action_queue ORDER BY id DESC")
    fun observeAllQueuesWithItems(): Flow<List<QueueWithItems>>

    // Single queue query for performance (avoid full-table reads)
    @Transaction
    @Query("SELECT * FROM action_queue WHERE id = :queueId")
    suspend fun getQueueWithItems(queueId: Long): QueueWithItems?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: QueueItemEntity): Long

    @Update
    suspend fun updateItems(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue_item WHERE itemId = :itemId")
    suspend fun deleteItem(itemId: Long)

    // Check if action is still referenced by any queue before deletion
    @Query("SELECT COUNT(*) FROM queue_item WHERE actionId = :actionId")
    suspend fun countItemsByActionId(actionId: Long): Int

    // New queue items use max(order)+1 to avoid duplicate orders after deletions
    @Query("SELECT MAX(\"order\") FROM queue_item WHERE queueId = :queueId")
    suspend fun maxOrderForQueue(queueId: Long): Int?

    @Query("DELETE FROM action_queue WHERE id = :queueId")
    suspend fun deleteQueueById(queueId: Long)

    @Query("DELETE FROM queue_item WHERE actionId = :actionId")
    suspend fun deleteItemsByActionId(actionId: Long): Int

    @Query("SELECT * FROM queue_item WHERE queueId = :queueId ORDER BY \"order\" ASC")
    suspend fun getItemsForQueue(queueId: Long): List<QueueItemEntity>
}
