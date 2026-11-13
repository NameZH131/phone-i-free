package com.phoneinteraction.free.core

import com.phoneinteraction.free.data.db.AppDatabase
import com.phoneinteraction.free.data.model.ActionQueueEntity
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.QueueRunStatus
import com.phoneinteraction.free.data.model.ExecutionResult
import com.phoneinteraction.free.data.model.QueueItemEntity
import com.phoneinteraction.free.data.model.QueueRunStateEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FailurePolicy { SKIP, STOP, RETRY }

// Explicit start result distinguishes failure reasons for accurate user feedback ("accessibility not enabled / already running")
// Without it, start returns silently and the user gets no feedback
enum class StartOutcome { STARTED, ALREADY_RUNNING, ENVIRONMENT_NOT_READY }

// Running progress (round N / total, current action N / total) for real-time overlay panel display,
// letting the user see "still running" instead of thinking it is unresponsive
data class QueueProgress(
    val round: Int,
    val totalRounds: Int,
    val index: Int,
    val total: Int
)

class QueueScheduler(
    private val db: AppDatabase,
    private val executor: ActionExecutor,
    private val scope: CoroutineScope,
    // action_timeout_ms and failure policy are configurable at runtime.
    // The settings page writes them to DataStore and AppServices reads them back, persisting after restart.
    @Volatile var actionTimeoutMs: Long = 5_000L,
    private val retryCount: Int = 3,
    // Failure policy for a failed action; skip by default.
    @Volatile var failurePolicy: FailurePolicy = FailurePolicy.SKIP
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(QueueRunStatus.IDLE)
    val state: StateFlow<QueueRunStatus> = _state

    @Volatile
    private var currentQueueId: Long? = null

    @Volatile
    private var runId: Long = 0

    // Run generation number. Each start/stop increments it; the loop holds a snapshot at start.
    // Snapshot expiry causes self-exit, preventing an old loop from reading the new RUNNING state and parallel-executing the same queue after stop + immediate start
    @Volatile
    private var runGen: Long = 0

    @Volatile
    private var currentIndex: Int = 0

    // Completed rounds. runLoop would otherwise reset round to 0 on each entry,
    // so "mid-pause resume / error retry / skip" would treat the remaining half-round as a new round, exceeding total rounds.
    @Volatile
    private var currentRound: Int = 0

    private var runJob: Job? = null

    // Performance optimization: persistence on every action advance in RUNNING state is write amplification.
    // Record last persist time; throttle "index advance" for RUNNING by PERSIST_INTERVAL_MS.
    // State transitions (pause/stop/error/idle) still persist immediately.
    @Volatile
    private var lastPersistAt = 0L

    // The scheduler itself doesn't know if the accessibility service is enabled; AppServices injects a probe function.
    // When not ready, every dispatch fails instantly; without interception an infinite queue becomes
    // an unthrottled fail loop (full CPU, log spam, ANR potential).
    @Volatile
    var environmentReady: () -> Boolean = { true }

    // The default SKIP policy would otherwise swallow a dispatch failure silently, leaving the user with no effect and no feedback.
    // Report most recent failure cause to UI layer; only stable enum transmitted, localization by UI (prevents core layer hardcoding Chinese)
    private val _lastFailure = MutableStateFlow<ExecutionFailure?>(null)
    val lastFailure: StateFlow<ExecutionFailure?> = _lastFailure

    fun clearLastFailure() {
        _lastFailure.value = null
    }

    // Running progress exposed to the UI layer
    private val _progress = MutableStateFlow(QueueProgress(0, 0, 0, 0))
    val progress: StateFlow<QueueProgress> = _progress

    // ===== Orchestration =====

    suspend fun createQueue(name: String): Long =
        db.queueDao().insertQueue(
            ActionQueueEntity(name = name, createdAt = System.currentTimeMillis())
        )

    // Delete queue; if deleting the currently running queue, invalidate the generation first and stop to prevent the old loop from continuing dispatch
    // Clear currentQueueId before delete to ensure subsequent persist doesn't write run state to deleted queue
    suspend fun deleteQueue(queueId: Long) {
        mutex.withLock {
            // Regardless of current state, if deleting the current queue invalidate the generation and stop to prevent the old loop from continuing
            if (currentQueueId == queueId) {
                runGen += 1
                currentQueueId = null
                _state.value = QueueRunStatus.STOPPED
            }
        }
        // Clear the persisted run state row, otherwise recoverFromDisk on the next start will re-launch the deleted queue
        db.queueRunStateDao().deleteByQueueId(queueId)
        // The rule table has no FK to queue, so it must be cleaned up explicitly; otherwise dangling rules silently do nothing when matched
        db.ruleDao().deleteByQueueId(queueId)
        db.queueDao().deleteQueueById(queueId)
    }

    suspend fun addItem(queueId: Long, actionId: Long, order: Int) {
        db.queueDao().insertItem(
            QueueItemEntity(queueId = queueId, actionId = actionId, order = order, enabled = true)
        )
    }

    suspend fun removeItem(queueItemId: Long) {
        db.queueDao().deleteItem(queueItemId)
    }

    suspend fun reorderItems(queueId: Long, newOrder: List<Long>) {
        // @Relation returns items with no order guarantee, reorder based on newOrder itemId sequence
        val byId = db.queueDao().getItemsForQueue(queueId).associateBy { it.itemId }
        db.queueDao().updateItems(
            newOrder.mapIndexedNotNull { index, itemId -> byId[itemId]?.copy(order = index) }
        )
    }

    // ===== Execution control =====

    suspend fun startQueue(queueId: Long, resumeFrom: Int = 0): StartOutcome {
        return mutex.withLock {
            if (_state.value == QueueRunStatus.RUNNING) {
                return@withLock StartOutcome.ALREADY_RUNNING
            }
            // Dispatch must fail instantly when accessibility is not connected, and start must be rejected.
            // Return reason for caller to prompt user, avoid "clicked execute but nothing happens"
            if (!environmentReady()) {
                return@withLock StartOutcome.ENVIRONMENT_NOT_READY
            }
            // Re-clicking execute means start fresh: bump the generation so a loop left over from the
            // paused run exits, then continue from a clean IDLE state.
            if (_state.value == QueueRunStatus.PAUSED) {
                runGen += 1
                _state.value = QueueRunStatus.IDLE
            }
            currentQueueId = queueId
            runId = System.currentTimeMillis()
            // Increment generation and snapshot; the old coroutine will exit due to generation expiry
            runGen += 1
            val gen = runGen
            // Each execute click starts from a clean state; discard previous residuals
            // (failure hints, progress, index), avoid "stuck in old flow causing actions not to execute". resumeFrom defaults to 0 (start from beginning).
            _lastFailure.value = null
            _progress.value = QueueProgress(0, 0, 0, 0)
            currentRound = 0
            // Performance optimization: reset throttle timestamp to ensure this start's RUNNING initial state is definitely persisted
            lastPersistAt = 0L
            setState(QueueRunStatus.RUNNING, resumeFrom)
            // Assign runJob inside the lock to prevent startQueue and a fast pause+resume from launching dual loops
            runJob = scope.launch {
                // Queue-level start delay; wait once before the loop truly starts. Pause/stop during the delay is handled at the runLoop top
                val startDelay = db.queueDao().getQueueById(queueId)?.startDelay ?: 0L
                if (startDelay > 0) delay(startDelay)
                runLoop(resumeFrom, 0, gen)
            }
            StartOutcome.STARTED
        }
    }

    suspend fun pauseQueue() {
        // Pause = no more execution of the next action after the current one completes; runLoop checks state after each action completes
        mutex.withLock {
            if (_state.value != QueueRunStatus.RUNNING) return
            setState(QueueRunStatus.PAUSED, currentIndex)
        }
    }

    suspend fun resumeQueue() {
        mutex.withLock {
            if (_state.value != QueueRunStatus.PAUSED) return
            setState(QueueRunStatus.RUNNING, currentIndex)
            if (runJob?.isActive != true) {
                // Service kill recovery has no live loop, needs re-launch; normal pause keeps loop alive with suspension, state flip wakes it
                // Carry completed rounds to avoid the remaining half-round becoming a new round after recovery
                runJob = scope.launch { runLoop(currentIndex, currentRound, runGen) }
            }
        }
    }

    suspend fun stopQueue() {
        // Stop means "stop after the current action completes"; don't cancel runJob, just set state
        mutex.withLock {
            if (_state.value == QueueRunStatus.IDLE || _state.value == QueueRunStatus.STOPPED) return
            // Invalidate the generation so in-flight loops (including those in startDelay) exit at the next check, avoiding a race with restart
            runGen += 1
            _progress.value = QueueProgress(0, 0, 0, 0)
            setState(QueueRunStatus.STOPPED, currentIndex)
        }
    }

    // Foreground service destruction needs a fallback stop; the execution scope is process-level,
    // otherwise service gone but queue still dispatching gestures
    fun stopNow() {
        scope.launch { stopQueue() }
    }

    suspend fun retryCurrent() {
        mutex.withLock {
            if (_state.value != QueueRunStatus.ERROR) return
            // Retry/skip means the user is aware of the failure; not clearing would let a new successful run end still pop the old failure hint
            // startQueue also clears lastFailure for the same reason
            _lastFailure.value = null
            setState(QueueRunStatus.RUNNING, currentIndex)
            runJob = scope.launch { runLoop(currentIndex, currentRound, runGen) }
        }
    }

    suspend fun skipCurrent() {
        mutex.withLock {
            if (_state.value != QueueRunStatus.ERROR) return
            // Same as retryCurrent: skip means the user is aware, clear to prevent the old hint from appearing
            _lastFailure.value = null
            setState(QueueRunStatus.RUNNING, currentIndex + 1)
            runJob = scope.launch { runLoop(currentIndex, currentRound, runGen) }
        }
    }

    // Reset = clear error, return to "not started" and start fresh from beginning. Sets IDLE, not READY,
    // because READY and IDLE look the same to the user, so READY would only confuse.
    suspend fun resetQueue() {
        mutex.withLock {
            setState(QueueRunStatus.IDLE, 0)
        }
    }

    suspend fun abandonQueue() {
        // Abandon is just a transfer from ERROR; calling it in RUNNING must not set STOPPED
        mutex.withLock {
            if (_state.value != QueueRunStatus.ERROR) return
            setState(QueueRunStatus.STOPPED, currentIndex)
        }
    }

    fun getQueueState(): QueueRunStatus = _state.value

    // Expose the current running queue id so the UI can determine if "loading copy while running" would interrupt execution
    fun currentRunningQueueId(): Long? = currentQueueId

    // ===== Recovery after process kill =====

    suspend fun recoverFromDisk() {
        // When the process already has a live loop (RUNNING/PAUSED), the disk record IS that loop's own write.
        // MUST NOT degrade and overwrite here; otherwise opening the app while a queue is running (or paused) would silently abort the loop and stop gestures mid-flow.
        if (_state.value == QueueRunStatus.RUNNING || _state.value == QueueRunStatus.PAUSED) return
        if (runJob?.isActive == true) return
        val saved = db.queueRunStateDao().getLatest() ?: return
        when (saved.state) {
            QueueRunStatus.RUNNING -> {
                // A RUNNING record must not unconditionally startQueue and auto-replay the last queue;
                // that would make gestures start randomly when the user opens the app and enter a fail loop if accessibility is not enabled.
                // Degrade to STOPPED and preserve progress info; whether to rerun is entirely the user's choice via "execute"
                mutex.withLock {
                    currentQueueId = saved.queueId
                    setState(QueueRunStatus.STOPPED, saved.currentIndex)
                }
            }
            QueueRunStatus.PAUSED -> {
                // After a process restart no live loop exists, so a saved PAUSED cannot resume by itself.
                // Degrade to IDLE and persist; the user's next execute starts from the beginning.
                mutex.withLock {
                    currentQueueId = saved.queueId
                    setState(QueueRunStatus.IDLE, 0)
                }
            }
            else -> {
                // STOPPED/ERROR can prompt the user about the last abnormal end;
                // preserve state for UI display; user resets/abandons or next startQueue overwrites via upsert
            }
        }
    }

    // ===== Internal execution loop =====

    // Outer catch-all: any unexpected exception in the execution loop (DB exception, accessibility layer exception) cannot
    // leave state permanently stuck at RUNNING (otherwise UI keeps showing running, next execute blocked by "already running", can only restart process)
    private suspend fun runLoop(startIndex: Int, startRound: Int, gen: Long) {
        try {
            runLoopInner(startIndex, startRound, gen)
        } catch (e: CancellationException) {
            // Cancellation (pause/stop/service destroy) must propagate up, not counted as failure
            throw e
        } catch (e: Exception) {
            _lastFailure.value = ExecutionFailure.UNKNOWN
            runCatching { setState(QueueRunStatus.ERROR, currentIndex) }
        }
    }

    private suspend fun runLoopInner(startIndex: Int, startRound: Int, gen: Long) {
        val queueId = currentQueueId ?: return
        // Queue-level loop count, 0 = infinite loop entire queue
        val loopCount = db.queueDao().getQueueById(queueId)?.loopCount ?: 1
        val items = db.queueDao().getItemsForQueue(queueId)
            .filter { it.enabled }          // Disabled items skipped during execution
            .sortedBy { it.order }          // @Relation has no order guarantee, sort by order
        // Snapshot semantics: edits to actions during a run don't take effect (edit hot-update is not yet supported)
        if (items.isEmpty()) {
            _progress.value = QueueProgress(0, 0, 0, 0)
            setState(QueueRunStatus.IDLE, 0)
            return
        }
        // Performance optimization: action snapshot; pre-fetch actions and steps referenced by this queue once, so no more DB queries happen inside the loop.
        // Snapshot semantics consistent with items: edits during run don't take effect.
        val actionIds = items.map { it.actionId }.distinct()
        val actionsById = HashMap<Long, ActionWithSteps?>(actionIds.size)
        for (id in actionIds) actionsById[id] = db.actionDao().getByIdWithSteps(id)
        var index = startIndex
        // Resume from completed rounds so mid-pause/retry/skip does not treat the remaining half-round as a new round
        var round = startRound
        // Consecutive failure count: a SKIP-policy infinite queue with sustained failures would otherwise run at full speed in an empty loop.
        // Threshold triggers ERROR to hand to user instead of silently running forever
        var consecutiveFailures = 0
        // Start index out of bounds (typical: skip the last action when ERROR)
        // Must wrap to the next round per loopCount, not end the entire queue; otherwise "skip" is treated as "end".
        // Wrap rule consistent with "round end" branch: first add completed rounds, then check if another round exists.
        if (index >= items.size) {
            round += 1
            if (loopCount == 0 || round < loopCount) {
                currentRound = round
                index = 0
                currentIndex = 0
                _progress.value = QueueProgress(round + 1, loopCount, 1, items.size)
            } else {
                _progress.value = QueueProgress(0, 0, 0, 0)
                setState(QueueRunStatus.IDLE, index)
                return
            }
        }
        while (true) {
            // Gen expiry (after stop/restart): exit immediately to avoid parallel execution
            if (gen != runGen) return
            when (_state.value) {
                QueueRunStatus.STOPPED, QueueRunStatus.IDLE -> return
                QueueRunStatus.PAUSED -> {
                    // Suspend waiting for resume signal, avoid race on "resume after pause"
                    _state.first { it != QueueRunStatus.PAUSED }
                    if (gen != runGen) return
                }
                QueueRunStatus.RUNNING -> {
                    if (index >= items.size) {
                        // Round end: loopCount 0 = infinite, else stop at loopCount
                        round += 1
                        // Completed rounds visible to process; resume/retry continues from current round
                        currentRound = round
                        if (loopCount == 0 || round < loopCount) {
                            // Don't set RUNNING directly - go through advanceOrYield guard to avoid overwriting concurrent pause/stop
                            index = 0
                            _progress.value = QueueProgress(round + 1, loopCount, 1, items.size)
                            advanceOrYield(0)
                        } else {
                            _progress.value = QueueProgress(0, 0, 0, 0)
                            setState(QueueRunStatus.IDLE, index)
                            return
                        }
                    } else {
                        val item = items[index]
                        // Update progress (round internally 0-based, display +1)
                        _progress.value = QueueProgress(round + 1, loopCount, index + 1, items.size)
                        val action: ActionWithSteps? = actionsById[item.actionId]
                        // Action missing: cannot write execution record since execution_record.actionId is NOT NULL FK to action table
                        // Writing to deleted action would throw SQLiteConstraintException and crash app
                        var succeeded = false
                        if (action == null) {
                            _lastFailure.value = ExecutionFailure.ACTION_MISSING
                        } else {
                            val outcome = executeWithPolicy(action, queueId, gen)
                            succeeded = outcome.record.result == ExecutionResult.SUCCESS
                            // Report all failures to UI (including SKIP policy), avoid silent skip
                            if (!succeeded) _lastFailure.value = outcome.failure
                        }
                        if (succeeded) {
                            consecutiveFailures = 0
                            index += 1
                            currentIndex = index
                            advanceOrYield(index)
                        } else {
                            // Circuit breaker: consecutive failures threshold triggers ERROR, avoids infinite queue spinning at full speed in failed state
                            consecutiveFailures += 1
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                setState(QueueRunStatus.ERROR, index)
                                return
                            }
                            // Backoff after failure prevents instant full-speed retry from saturating CPU
                            delay(FAILURE_BACKOFF_MS)
                            when (failurePolicy) {
                                FailurePolicy.SKIP -> {
                                    index += 1
                                    currentIndex = index
                                    advanceOrYield(index)
                                }
                                FailurePolicy.STOP -> {
                                    setState(QueueRunStatus.STOPPED, index)
                                    return
                                }
                                FailurePolicy.RETRY -> {
                                    // Retry count exhausted in executeWithPolicy, enter ERROR
                                    setState(QueueRunStatus.ERROR, index)
                                    return
                                }
                            }
                        }
                    }
                }
                else -> return
            }
        }
    }

    private suspend fun executeWithPolicy(
        action: ActionWithSteps,
        queueId: Long,
        gen: Long
    ): ActionOutcome {
        // Action-level repeat count, 0 = infinite (overrides queue-level 0)
        val repeat = action.action.repeatCount
        var attempt = 0
        while (true) {
            var outcome = executor.executeAction(action, queueId, runId, actionTimeoutMs)
            var done = 1
            while (outcome.record.result == ExecutionResult.SUCCESS &&
                (repeat == 0 || done < repeat)
            ) {
                // Check state during repeat gap - pause/stop takes effect immediately
                // Also validate gen to exit old repeat chain after stop+restart
                if (gen != runGen || _state.value != QueueRunStatus.RUNNING) return outcome
                outcome = executor.executeAction(action, queueId, runId, actionTimeoutMs)
                done += 1
            }
            if (outcome.record.result == ExecutionResult.SUCCESS) return outcome
            attempt += 1
            when (failurePolicy) {
                FailurePolicy.RETRY -> if (attempt >= retryCount) return outcome
                else -> return outcome
            }
        }
    }

    private suspend fun advanceOrYield(index: Int) {
        // Only emit RUNNING if still RUNNING; otherwise preserve user-set state (PAUSED/STOPPED handled by loop top)
        // Don't write RUNNING again if already RUNNING (StateFlow skips equal values)
        // This avoids race with concurrent pauseQueue/stopQueue overwriting user's recent pause
        currentIndex = index
        val current = _state.value
        persist(if (current == QueueRunStatus.RUNNING) QueueRunStatus.RUNNING else current, index)
    }

    private suspend fun setState(status: QueueRunStatus, index: Int) {
        currentIndex = index
        _state.value = status
        persist(status, index)
    }

    private suspend fun persist(status: QueueRunStatus, index: Int) {
        val qid = currentQueueId ?: return
        // RUNNING index advances are throttled (not sensitive to crash recovery)
        // State transitions (pause/stop/error/idle) persist immediately for correct restart state
        if (status == QueueRunStatus.RUNNING) {
            val now = System.currentTimeMillis()
            if (now - lastPersistAt < PERSIST_INTERVAL_MS) return
            lastPersistAt = now
        } else {
            lastPersistAt = System.currentTimeMillis()
        }
        db.queueRunStateDao().upsert(
            QueueRunStateEntity(
                queueId = qid,
                state = status,
                currentIndex = index,
                runId = runId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        // Consecutive failure circuit breaker threshold and minimum backoff
        private const val MAX_CONSECUTIVE_FAILURES = 10
        private const val FAILURE_BACKOFF_MS = 200L
        // Minimum persist interval for RUNNING index advances (avoid per-action DB writes for fast queues)
        private const val PERSIST_INTERVAL_MS = 500L
    }
}
