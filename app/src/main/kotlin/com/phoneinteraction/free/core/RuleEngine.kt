package com.phoneinteraction.free.core

import com.phoneinteraction.free.data.db.AppDatabase
import com.phoneinteraction.free.data.model.Event
import com.phoneinteraction.free.data.model.EventType
import com.phoneinteraction.free.data.model.RuleEntity
import com.phoneinteraction.free.data.model.TriggerType

// Rule export/import is not implemented yet; the engine has no export or import API.
// Add it when rules need to move between devices.
class RuleEngine(private val db: AppDatabase) {

    suspend fun matchRule(event: Event): RuleEntity? {
        // Gestures and voice commands are the only event sources, so this mapping is exhaustive.
        val triggerType = when (event.eventType) {
            EventType.GESTURE -> TriggerType.GESTURE
            EventType.VOICE_COMMAND -> TriggerType.VOICE
        }
        return pickRule(db.ruleDao().getAll(), triggerType, event.value)
    }

    suspend fun addRule(rule: RuleEntity): Long =
        db.ruleDao().insert(rule.copy(updatedAt = System.currentTimeMillis()))

    // Update updatedAt on save to maintain "latest wins" determinism for same priority.
    suspend fun updateRule(rule: RuleEntity) {
        db.ruleDao().update(rule.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteRule(ruleId: Long) {
        db.ruleDao().deleteById(ruleId)
    }

    suspend fun getRules(): List<RuleEntity> = db.ruleDao().getAll()
}

// Matching filter and best-match logic sit in a top-level pure function for testability.
// Only one rule executes when multiple match; pick the highest priority, tie-breaking by the most recent updatedAt.
internal fun pickRule(
    candidates: List<RuleEntity>,
    triggerType: TriggerType,
    value: String
): RuleEntity? = candidates
    .filter { it.triggerType == triggerType && it.triggerValue == value }
    .maxWithOrNull(compareBy({ it.priority }, { it.updatedAt }))
