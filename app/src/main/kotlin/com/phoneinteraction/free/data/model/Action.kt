package com.phoneinteraction.free.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "action")
data class ActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ActionType,
    val normalizedX: Float,
    val normalizedY: Float,
    val duration: Long,
    // Wait time before this action executes (shared by queue/multi-action)
    val delay: Long,
    // Action-level repeat count: same action repeats N times; 0 = infinite (overrides queue-level 0)
    // Cannot use @ColumnInfo(defaultValue = "1") here - existing installs have the column without DEFAULT.
    // Declaring a default causes Room migration schema validation to throw
    // IllegalStateException("Migration didn't properly handle: action") on startup.
    val repeatCount: Int = 1,
    val globalAction: GlobalAction? = null
)

@Entity(
    tableName = "action_step",
    foreignKeys = [
        ForeignKey(
            entity = ActionEntity::class,
            parentColumns = ["id"],
            childColumns = ["actionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("actionId")]
)
data class ActionStepEntity(
    @PrimaryKey(autoGenerate = true) val stepId: Long = 0,
    val actionId: Long,
    val type: StepType,
    val normalizedX: Float,
    val normalizedY: Float,
    val timestamp: Long
)

data class ActionWithSteps(
    @Embedded val action: ActionEntity,
    @Relation(parentColumn = "id", entityColumn = "actionId")
    val steps: List<ActionStepEntity>
)
