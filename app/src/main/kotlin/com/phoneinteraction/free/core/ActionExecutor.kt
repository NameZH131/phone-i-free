package com.phoneinteraction.free.core

import android.content.Context
import android.graphics.Path
import com.phoneinteraction.free.data.db.ExecutionRecordDao
import com.phoneinteraction.free.data.model.ActionEntity
import com.phoneinteraction.free.data.model.ActionStepEntity
import com.phoneinteraction.free.data.model.ActionType
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.ExecutionRecordEntity
import com.phoneinteraction.free.data.model.ExecutionResult
import com.phoneinteraction.free.data.model.StepType
import com.phoneinteraction.free.service.AccessibilityBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

// Failure reason uses stable enum instead of Chinese strings.
// Users see a localized message instead of internal strings like "Action does not exist (deleted)"
// or raw enum names such as SWIPE and GLOBAL.
enum class ExecutionFailure {
    NONE,
    TIMEOUT,
    ACTION_MISSING,
    NOT_ACCEPTED,
    SWIPE_REQUIRES_STEPS,
    GLOBAL_MISSING,
    GLOBAL_NOT_ACCEPTED,
    UNKNOWN
}

// Use exception with failure reason when dispatch is rejected by system - avoids string-matching on message.
class DispatchFailureException(val failure: ExecutionFailure) : IllegalStateException(failure.name)

// Execution result = DB record + failure enum. Both needed by caller.
data class ActionOutcome(
    val record: ExecutionRecordEntity,
    val failure: ExecutionFailure
)

class ActionExecutor(
    private val context: Context,
    private val bridge: AccessibilityBridge,
    private val recordDao: ExecutionRecordDao
) {

    // Max record count to retain. Trim by time when exceeded to prevent unbounded growth from infinite loops.
    private val MAX_RECORDS = 500

    // Trim batch size: accumulate this many records before trimming instead of trimming on every insert.
    // May be called concurrently from queue loops, rule triggers, and manual execution - use AtomicInteger.
    private val TRIM_BATCH = 50
    private val insertsSinceTrim = AtomicInteger(0)

    // Timeout margin: effective timeout = max(configured, action duration + margin).
    private val TIMEOUT_MARGIN_MS = 2_000L

    // Effective timeout cannot be less than action duration. Default timeout 5s but duration can be 60s -
    // an 8s long-press would be incorrectly marked as timeout. With retry policy, new gestures would
    // overlap with unfinished old gestures causing misclicks.
    private fun effectiveTimeoutMs(action: ActionWithSteps, configuredMs: Long?): Long? {
        if (configuredMs == null) return null
        val actionMs = if (action.steps.isEmpty()) {
            action.action.duration
        } else {
            // Steps come from @Relation - order not guaranteed (dispatchSteps sorts internally).
            // Taking first/last difference can yield 0 or negative when the order is wrong, which
            // would collapse the effective timeout to the configured 5s and falsely time out
            // long-press or slow-swipe. Use min/max instead - order independent.
            (action.steps.maxOf { it.timestamp } - action.steps.minOf { it.timestamp }).coerceAtLeast(1L)
        }
        return maxOf(configuredMs, actionMs + TIMEOUT_MARGIN_MS)
    }

    suspend fun executeAction(
        action: ActionWithSteps,
        queueId: Long? = null,
        queueRunId: Long? = null,
        timeoutMs: Long? = null
    ): ActionOutcome {
        val startTime = System.currentTimeMillis()
        var result = ExecutionResult.SUCCESS
        var failure = ExecutionFailure.NONE
        try {
            // Delay by the action's delay parameter. Interval precision is handled by the upstream scheduler.
            delay(action.action.delay)
            // Use effective timeout (includes action duration + margin) to avoid false timeout on long actions.
            val timeout = effectiveTimeoutMs(action, timeoutMs)
            if (timeout != null) {
                withTimeout(timeout) { dispatch(action) }
            } else {
                dispatch(action)
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout logged as failure. TimeoutCancellationException must be caught before CancellationException.
            result = ExecutionResult.FAILURE
            failure = ExecutionFailure.TIMEOUT
        } catch (e: CancellationException) {
            // Cancellation must propagate (pause/stop semantics), not treated as failure.
            throw e
        } catch (e: DispatchFailureException) {
            result = ExecutionResult.FAILURE
            failure = e.failure
        } catch (e: Exception) {
            // Don't expose exception class name or raw message to users (implementation detail). Map to unknown failure.
            result = ExecutionResult.FAILURE
            failure = ExecutionFailure.UNKNOWN
        }
        val record = ExecutionRecordEntity(
            actionId = action.action.id,
            queueId = queueId,
            queueRunId = queueRunId,
            executionTime = startTime,
            result = result,
            duration = System.currentTimeMillis() - startTime,
            // Store stable error code in DB (English), for audit/debug. Not shown in user-facing UI text.
            errorMessage = if (failure == ExecutionFailure.NONE) null else failure.name
        )
        // If the action is deleted during execution, execution_record's FK cascade fails on insert,
        // which throws up to the scheduler and marks the entire queue ERROR. The gesture was already
        // dispatched, so a database failure should not become a queue-level failure; swallow it and
        // continue with the next step.
        runCatching { recordDao.insert(record) }
        // Infinite loops write records continuously - keep only recent N to prevent unbounded growth.
        // Batch trim instead of per-action to reduce DELETE write amplification.
        if (insertsSinceTrim.incrementAndGet() >= TRIM_BATCH) {
            insertsSinceTrim.set(0)
            runCatching { recordDao.trimTo(MAX_RECORDS) }
        }
        return ActionOutcome(record, failure)
    }

    private suspend fun dispatch(action: ActionWithSteps) {
        if (action.steps.isEmpty()) {
            dispatchSimple(action.action)
        } else {
            dispatchSteps(action.steps)
        }
    }

    private suspend fun dispatchSimple(action: ActionEntity) {
        val (w, h) = screenSize()
        val x = action.normalizedX * w
        val y = action.normalizedY * h
        when (action.type) {
            // Click hold duration = action duration, user-editable (default system tap timeout).
            ActionType.CLICK -> {
                val ok = bridge.dispatchClick(x, y, action.duration)
                if (!ok) throw DispatchFailureException(ExecutionFailure.NOT_ACCEPTED)
            }
            ActionType.LONG_PRESS ->
                if (!bridge.dispatchLongPress(x, y, action.duration)) {
                    throw DispatchFailureException(ExecutionFailure.NOT_ACCEPTED)
                }
            // Don't expose internal strings like "SWIPE" to users.
            ActionType.SWIPE -> throw DispatchFailureException(ExecutionFailure.SWIPE_REQUIRES_STEPS)
            ActionType.GLOBAL -> {
                val ga = action.globalAction
                    ?: throw DispatchFailureException(ExecutionFailure.GLOBAL_MISSING)
                if (!bridge.performGlobalAction(ga)) {
                    throw DispatchFailureException(ExecutionFailure.GLOBAL_NOT_ACCEPTED)
                }
            }
        }
    }

    private suspend fun dispatchSteps(steps: List<ActionStepEntity>) {
        // Single-finger recorded streams segmented by POINTER_UP - rebuild each segment as one
        // continuous gesture dispatch. Avoids misclicks from independent DOWN/UP per segment.
        // Multi-finger interleaved streams have scrambled paths after sorting (not yet supported, will pair by pointer id).
        val (w, h) = screenSize()
        val segments = mutableListOf<List<ActionStepEntity>>()
        var segment = mutableListOf<ActionStepEntity>()
        for (step in steps.sortedBy { it.timestamp }) {
            segment.add(step)
            if (step.type == StepType.POINTER_UP) {
                segments.add(segment.toList())
                segment = mutableListOf()
            }
        }
        if (segment.isNotEmpty()) segments.add(segment.toList())
        for (seg in segments) {
            if (seg.size == 1) {
                if (!bridge.dispatchClick(seg[0].normalizedX * w, seg[0].normalizedY * h)) {
                    throw DispatchFailureException(ExecutionFailure.NOT_ACCEPTED)
                }
            } else {
                val path = Path().apply {
                    moveTo(seg.first().normalizedX * w, seg.first().normalizedY * h)
                    seg.drop(1).forEach { lineTo(it.normalizedX * w, it.normalizedY * h) }
                }
                val duration = (seg.last().timestamp - seg.first().timestamp).coerceAtLeast(1)
                if (!bridge.dispatchStroke(path, duration)) {
                    throw DispatchFailureException(ExecutionFailure.NOT_ACCEPTED)
                }
            }
        }
    }

    private fun screenSize(): Pair<Float, Float> {
        // Must match drawing layer base (physical full screen). See ScreenMetrics doc.
        return ScreenMetrics.size(context)
    }
}
