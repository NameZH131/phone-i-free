package com.phoneinteraction.free.core

import com.phoneinteraction.free.data.model.RuleEntity
import com.phoneinteraction.free.data.model.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// pickRule is the core of rule matching: when several candidate rules share one trigger word,
// priority and then updatedAt decide the single winner. Both dimensions were previously
// uncontrolled (priority was always 0); they are the tie-break semantics beyond the
// duplicate-word check on the rules screen and must be locked by tests.
class RuleEnginePickTest {

    private fun rule(
        id: Long,
        type: TriggerType = TriggerType.GESTURE,
        value: String = "fist",
        priority: Int = 0,
        updatedAt: Long = 0L
    ) = RuleEntity(
        id = id,
        triggerType = type,
        triggerValue = value,
        actionId = null,
        actionQueueId = 1L,
        priority = priority,
        updatedAt = updatedAt
    )

    @Test
    fun emptyCandidatesReturnsNull() {
        assertNull(pickRule(emptyList(), TriggerType.GESTURE, "fist"))
    }

    @Test
    fun filtersByTypeAndValue() {
        val candidates = listOf(
            rule(1, TriggerType.VOICE, "fist"),
            rule(2, TriggerType.GESTURE, "palm"),
            rule(3, TriggerType.GESTURE, "fist")
        )
        assertEquals(3L, pickRule(candidates, TriggerType.GESTURE, "fist")?.id)
    }

    @Test
    fun higherPriorityWins() {
        val candidates = listOf(
            rule(1, priority = 0, updatedAt = 999L),
            rule(2, priority = 5, updatedAt = 1L)
        )
        assertEquals(2L, pickRule(candidates, TriggerType.GESTURE, "fist")?.id)
    }

    @Test
    fun equalPriorityNewerUpdatedAtWins() {
        val candidates = listOf(
            rule(1, priority = 0, updatedAt = 100L),
            rule(2, priority = 0, updatedAt = 200L)
        )
        assertEquals(2L, pickRule(candidates, TriggerType.GESTURE, "fist")?.id)
    }
}
