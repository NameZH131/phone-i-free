package com.phoneinteraction.free.service

import androidx.compose.ui.geometry.Offset
import com.phoneinteraction.free.data.db.AppDatabase
import com.phoneinteraction.free.data.model.ActionType
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.StepType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Two overlay modes: DRAW mode is touchable for capture/drag, PASSTHROUGH mode shows only markers without intercepting input
enum class OverlayMode { DRAW, PASSTHROUGH }

data class OverlayMarker(
    val itemId: Long,
    val actionId: Long,
    val orderId: Int, // 1-based display ID = queue execution order
    val type: ActionType, // CLICK / SWIPE only
    val points: List<Offset>, // normalized 0..1; CLICK single point, SWIPE polyline
    val durationMs: Long,
    // Wait before each repeat execution (maps to Action.delay), defaults to 0
    val delayMs: Long = 0L,
    val repeatCount: Int,
    // Action alias (name from the action library) used for panel display and renaming
    val name: String = ""
)

object OverlayState {

    // Overlay-specific queue id, set by OverlayManager.show; used by panel execute button
    var overlayQueueId: Long = -1L

    // Edit target to open, written by queue page/action library, consumed by OverlayManager.show and cleared
    var pendingEditQueueId: Long = -1L
    var pendingEditActionId: Long = -1L

    // Single action uses fixed itemId as stable drag/select key (negative avoids auto-increment PK conflicts)
    const val SINGLE_ACTION_ITEM_ID: Long = -100L

    // Whether in single-action mode - panel hides queue-level controls, canvas disables add
    private val _targetActionId = MutableStateFlow(-1L)
    val targetActionId: StateFlow<Long> = _targetActionId

    private val _markers = MutableStateFlow<List<OverlayMarker>>(emptyList())
    val markers: StateFlow<List<OverlayMarker>> = _markers

    private val _mode = MutableStateFlow(OverlayMode.DRAW)
    val mode: StateFlow<OverlayMode> = _mode

    private val _panelExpanded = MutableStateFlow(false)
    val panelExpanded: StateFlow<Boolean> = _panelExpanded

    private val _selectedItemId = MutableStateFlow<Long?>(null)
    val selectedItemId: StateFlow<Long?> = _selectedItemId

    // Current queue metadata (name/loop count/start delay) for panel display and edit
    private val _queueName = MutableStateFlow("")
    val queueName: StateFlow<String> = _queueName

    private val _queueLoopCount = MutableStateFlow(1)
    val queueLoopCount: StateFlow<Int> = _queueLoopCount

    private val _queueStartDelay = MutableStateFlow(0L)
    val queueStartDelay: StateFlow<Long> = _queueStartDelay

    // Whether to render markers during execution; in-memory, rendered by default
    private val _renderDuringRun = MutableStateFlow(true)
    val renderDuringRun: StateFlow<Boolean> = _renderDuringRun

    fun setRenderDuringRun(render: Boolean) {
        _renderDuringRun.value = render
    }

    // Action IDs pending actual deletion (in undo window)
    // Kept in global state so the marker renderer can hide the marker at once,
    // even before the list updates. Real deletion never reuses IDs (Room auto-increment).
    private val _pendingDeleteIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDeleteIds: StateFlow<Set<Long>> = _pendingDeleteIds

    fun markPendingDelete(itemId: Long) {
        _pendingDeleteIds.value = _pendingDeleteIds.value + itemId
    }

    fun unmarkPendingDelete(itemId: Long) {
        _pendingDeleteIds.value = _pendingDeleteIds.value - itemId
    }

    fun setMode(mode: OverlayMode) {
        _mode.value = mode
    }

    fun setPanelExpanded(expanded: Boolean) {
        _panelExpanded.value = expanded
    }

    fun select(itemId: Long?) {
        _selectedItemId.value = itemId
    }

    // Real-time preview during drag (in-memory); persisted by OverlayManager.persistMarkerPoints on drag end
    fun updateMarkerPoints(itemId: Long, points: List<Offset>) {
        _markers.value = _markers.value.map {
            if (it.itemId == itemId) it.copy(points = points) else it
        }
    }

    // markers = floating window queue action projection; caller refreshes after each add/delete/edit
    suspend fun refresh(db: AppDatabase, queueId: Long) {
        // Loading queue exits single-action mode to keep the two modes mutually exclusive
        _targetActionId.value = -1L
        // Performance: only read this queue + actions it references instead of all queues + all actions
        val withItems = db.queueDao().getQueueWithItems(queueId)
        if (withItems == null) {
            _markers.value = emptyList()
            _queueName.value = ""
            _queueLoopCount.value = 1
            _queueStartDelay.value = 0L
            // Queue was externally deleted - invalidate id, OverlayManager will re-ensure on next draw
            overlayQueueId = -1L
            return
        }
        _queueName.value = withItems.queue.name
        _queueLoopCount.value = withItems.queue.loopCount
        _queueStartDelay.value = withItems.queue.startDelay
        val enabledItems = withItems.items
            .filter { it.enabled }          // ID = execution order; disabled items don't execute and don't occupy a number
            .sortedBy { it.order }
        val actionIds = enabledItems.map { it.actionId }.distinct()
        val actions = if (actionIds.isEmpty()) {
            emptyMap()
        } else {
            db.actionDao().getByIdsWithSteps(actionIds).associateBy { it.action.id }
        }
        _markers.value = enabledItems
            .mapIndexedNotNull { index, item ->
                val a = actions[item.actionId] ?: return@mapIndexedNotNull null
                // GLOBAL actions have no screen coordinates; floating window only renders CLICK/SWIPE markers
                if (a.action.type == ActionType.GLOBAL) return@mapIndexedNotNull null
                val pts = if (a.steps.isNotEmpty()) {
                    a.steps.sortedBy { it.timestamp }
                        .map { Offset(it.normalizedX, it.normalizedY) }
                } else {
                    listOf(Offset(a.action.normalizedX, a.action.normalizedY))
                }
                OverlayMarker(
                    itemId = item.itemId,
                    actionId = a.action.id,
                    orderId = index + 1,
                    type = a.action.type,
                    points = pts,
                    durationMs = a.action.duration,
                    delayMs = a.action.delay,
                    repeatCount = a.action.repeatCount,
                    name = a.action.name
                )
            }
    }

    // Load single action as only marker, entering single-action mode; clear and exit if action doesn't exist
    suspend fun refreshAction(db: AppDatabase, actionId: Long) {
        val a = db.actionDao().getByIdWithSteps(actionId)
        if (a == null) {
            _targetActionId.value = -1L
            _markers.value = emptyList()
            _queueName.value = ""
            _queueLoopCount.value = 1
            _queueStartDelay.value = 0L
            return
        }
        _targetActionId.value = actionId
        // Lock to DRAW mode - previous session might have stopped in PASSTHROUGH, persisting window flags would make it untouchable
        _mode.value = OverlayMode.DRAW
        _queueName.value = a.action.name
        _queueLoopCount.value = 1
        _queueStartDelay.value = 0L
        _markers.value = listOf(
            toMarker(a, itemId = SINGLE_ACTION_ITEM_ID, orderId = 1)
        )
        // Select on panel entry, parameters (duration/delay/repeat) visible immediately
        _selectedItemId.value = SINGLE_ACTION_ITEM_ID
    }

    // Panel internal changes reload based on current target - single-action reloads the action, queue mode reloads the queue
    suspend fun refreshCurrent(db: AppDatabase) {
        val actionId = _targetActionId.value
        if (actionId >= 0L) refreshAction(db, actionId) else refresh(db, overlayQueueId)
    }

    // Clear single-action state when hiding floating window to prevent stale target on next normal open
    fun exitEditTarget() {
        _targetActionId.value = -1L
        // Single action uses sentinel itemId; clear selection on exit to prevent residue in queue mode
        _selectedItemId.value = null
    }

    // Project action with steps into floating marker (same coord/step rules as refresh for consistency)
    fun toMarker(action: ActionWithSteps, itemId: Long, orderId: Int): OverlayMarker {
        val pts = if (action.steps.isNotEmpty()) {
            action.steps.sortedBy { it.timestamp }
                .map { Offset(it.normalizedX, it.normalizedY) }
        } else {
            listOf(Offset(action.action.normalizedX, action.action.normalizedY))
        }
        return OverlayMarker(
            itemId = itemId,
            actionId = action.action.id,
            orderId = orderId,
            type = action.action.type,
            points = pts,
            durationMs = action.action.duration,
            delayMs = action.action.delay,
            repeatCount = action.action.repeatCount,
            name = action.action.name
        )
    }

    // Floating action -> ActionEntity + steps (dual-track: SWIPE requires steps)
    fun toActionEntities(
        marker: OverlayMarker,
        name: String
    ): Pair<com.phoneinteraction.free.data.model.ActionEntity, List<com.phoneinteraction.free.data.model.ActionStepEntity>> {
        val action = com.phoneinteraction.free.data.model.ActionEntity(
            id = marker.actionId,
            name = name,
            type = marker.type,
            normalizedX = marker.points.first().x,
            normalizedY = marker.points.first().y,
            duration = marker.durationMs,
            delay = marker.delayMs,
            repeatCount = marker.repeatCount
        )
        val steps = if (marker.type == ActionType.SWIPE) {
            val pts = if (marker.points.size < 3) {
                // Two points would be treated as single-step approximate click - add midpoint to force multi-step Path
                listOf(
                    marker.points[0],
                    Offset(
                        (marker.points[0].x + marker.points[1].x) / 2f,
                        (marker.points[0].y + marker.points[1].y) / 2f
                    ),
                    marker.points[1]
                )
            } else {
                marker.points
            }
            val n = pts.size
            pts.mapIndexed { i, p ->
                com.phoneinteraction.free.data.model.ActionStepEntity(
                    stepId = 0,
                    actionId = marker.actionId,
                    type = when (i) {
                        0 -> StepType.POINTER_DOWN
                        n - 1 -> StepType.POINTER_UP
                        else -> StepType.POINTER_MOVE
                    },
                    normalizedX = p.x,
                    normalizedY = p.y,
                    timestamp = (marker.durationMs * i) / (n - 1).coerceAtLeast(1)
                )
            }
        } else {
            emptyList()
        }
        return action to steps
    }
}
