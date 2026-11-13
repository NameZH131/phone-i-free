package com.phoneinteraction.free.data

import androidx.room.withTransaction
import com.phoneinteraction.free.data.db.AppDatabase
import com.phoneinteraction.free.data.model.ActionEntity
import com.phoneinteraction.free.data.model.ActionStepEntity
import com.phoneinteraction.free.data.model.ActionWithSteps

class ActionManager(private val db: AppDatabase) {

    suspend fun addAction(action: ActionEntity, steps: List<ActionStepEntity> = emptyList()): Long =
        db.withTransaction {
            val actionId = db.actionDao().insert(action)
            if (steps.isNotEmpty()) {
                db.actionDao().insertSteps(steps.map { it.copy(actionId = actionId) })
            }
            actionId
        }

    suspend fun updateAction(action: ActionEntity) {
        db.actionDao().update(action)
    }

    // For simple coordinate/duration edits, only action row changes. For SWIPE trajectory edits,
    // steps must be updated atomically with action row - update action then rebuild all steps
    // to avoid partial trajectory on failure.
    suspend fun updateActionWithSteps(action: ActionEntity, steps: List<ActionStepEntity>) {
        db.withTransaction {
            db.actionDao().update(action)
            db.actionDao().deleteStepsByActionId(action.id)
            if (steps.isNotEmpty()) {
                db.actionDao().insertSteps(steps.map { it.copy(actionId = action.id) })
            }
        }
    }

    suspend fun deleteAction(actionId: Long) {
        // Clear queue references before deleting the action. Steps cascade via FK.
        // withTransaction ensures "clear refs + delete action" atomicity - rollback on failure leaves no orphan queue items.
        db.withTransaction {
            db.queueDao().deleteItemsByActionId(actionId)
            // Rule table has no FK to action - must explicitly clean, otherwise rules become dangling.
            db.ruleDao().deleteByActionId(actionId)
            db.actionDao().deleteById(actionId)
        }
    }

    suspend fun getAction(actionId: Long): ActionWithSteps? =
        db.actionDao().getByIdWithSteps(actionId)

    suspend fun getAllActions(): List<ActionWithSteps> =
        db.actionDao().getAllWithSteps()

    // Find orphan actions (not referenced by any queue or rule) for cleanup preview.
    suspend fun getUnreferencedActions(): List<ActionWithSteps> =
        db.actionDao().getUnreferencedActionsWithSteps()

    // Single transaction to re-query then delete - prevents race where action becomes referenced
    // between preview and confirm but still gets deleted.
    // Only deletes action rows: steps and execution history cascade via FK. Returns deleted items for result toast.
    suspend fun deleteUnreferencedActions(): List<ActionWithSteps> =
        db.withTransaction {
            val orphans = db.actionDao().getUnreferencedActionsWithSteps()
            orphans.forEach { db.actionDao().deleteById(it.action.id) }
            orphans
        }
}
