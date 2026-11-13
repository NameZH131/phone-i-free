package com.phoneinteraction.free.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rule")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerType: TriggerType,
    val triggerValue: String,
    val actionId: Long?,
    val actionQueueId: Long?,
    val priority: Int,
    val updatedAt: Long
)
