package com.phoneinteraction.free.data.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "action_queue")
data class ActionQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    // Queue-level loop count, 0 = infinite loop; default 1
    // Explicit defaultValue ensures entity schema matches migration DEFAULT
    @ColumnInfo(defaultValue = "1") val loopCount: Int = 1,
    // One-time wait before queue starts (ms), default 0
    @ColumnInfo(defaultValue = "0") val startDelay: Long = 0
)

@Entity(
    tableName = "queue_item",
    foreignKeys = [
        ForeignKey(
            entity = ActionQueueEntity::class,
            parentColumns = ["id"],
            childColumns = ["queueId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ActionEntity::class,
            parentColumns = ["id"],
            childColumns = ["actionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("queueId"), Index("actionId")]
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val itemId: Long = 0,
    val queueId: Long,
    val actionId: Long,
    val order: Int,
    val enabled: Boolean
)

data class QueueWithItems(
    @Embedded val queue: ActionQueueEntity,
    @Relation(parentColumn = "id", entityColumn = "queueId")
    val items: List<QueueItemEntity>
)
