package com.phoneinteraction.free.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.phoneinteraction.free.data.model.ActionQueueEntity

@Entity(
    tableName = "execution_record",
    foreignKeys = [
        ForeignKey(
            entity = ActionEntity::class,
            parentColumns = ["id"],
            childColumns = ["actionId"],
            onDelete = ForeignKey.CASCADE
        ),
        // SET_NULL preserves execution history for audit; queue deletion only nullifies reference
        ForeignKey(
            entity = ActionQueueEntity::class,
            parentColumns = ["id"],
            childColumns = ["queueId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    // Index on executionTime for efficient trim queries
    indices = [Index("actionId"), Index("queueId"), Index("executionTime")]
)
data class ExecutionRecordEntity(
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0,
    val actionId: Long,
    val queueId: Long?,
    val queueRunId: Long?,
    val executionTime: Long,
    val result: ExecutionResult,
    val duration: Long,
    val errorMessage: String?
)

@Entity(tableName = "queue_run_state")
data class QueueRunStateEntity(
    @PrimaryKey val queueId: Long,
    val state: QueueRunStatus,
    val currentIndex: Int,
    val runId: Long,
    val updatedAt: Long
)
