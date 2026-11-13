package com.phoneinteraction.free.data.db

import androidx.room.TypeConverter
import com.phoneinteraction.free.data.model.ActionType
import com.phoneinteraction.free.data.model.ExecutionResult
import com.phoneinteraction.free.data.model.GlobalAction
import com.phoneinteraction.free.data.model.QueueRunStatus
import com.phoneinteraction.free.data.model.StepType
import com.phoneinteraction.free.data.model.TriggerType

class Converters {

    @TypeConverter
    fun actionTypeToString(v: ActionType): String = v.name

    @TypeConverter
    fun stringToActionType(v: String): ActionType = ActionType.valueOf(v)

    @TypeConverter
    fun stepTypeToString(v: StepType): String = v.name

    @TypeConverter
    fun stringToStepType(v: String): StepType = StepType.valueOf(v)

    @TypeConverter
    fun triggerTypeToString(v: TriggerType): String = v.name

    @TypeConverter
    fun stringToTriggerType(v: String): TriggerType = TriggerType.valueOf(v)

    @TypeConverter
    fun executionResultToString(v: ExecutionResult): String = v.name

    @TypeConverter
    fun stringToExecutionResult(v: String): ExecutionResult = ExecutionResult.valueOf(v)

    @TypeConverter
    fun queueRunStatusToString(v: QueueRunStatus): String = v.name

    @TypeConverter
    fun stringToQueueRunStatus(v: String): QueueRunStatus = QueueRunStatus.valueOf(v)

    @TypeConverter
    fun globalActionToString(v: GlobalAction?): String? = v?.name

    @TypeConverter
    fun stringToGlobalAction(v: String?): GlobalAction? = v?.let { GlobalAction.valueOf(it) }
}
