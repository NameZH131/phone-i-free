package com.phoneinteraction.free.service

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import android.view.ViewConfiguration
import com.phoneinteraction.free.core.ScreenMetrics
import com.phoneinteraction.free.ui.failureText
// Animation imports for the panel and the progress bar: tween drives the panel fade-in,
// and the run progress fill animates to its new value.
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneinteraction.free.AppServices
import com.phoneinteraction.free.R
import com.phoneinteraction.free.data.model.ActionQueueEntity
import com.phoneinteraction.free.data.model.ActionType
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.QueueRunStatus
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Throttle failure toasts to avoid flooding the screen when failures are frequent
private const val FAILURE_TOAST_THROTTLE_MS = 3_000L

@Composable
fun MarkerOverlayContent(manager: OverlayManager) {
    val markers by OverlayState.markers.collectAsState()
    val mode by OverlayState.mode.collectAsState()
    // Single-action mode: targetActionId >= 0 means canvas disallows new markers, panel hides queue-level controls
    val targetActionId by OverlayState.targetActionId.collectAsState()
    val singleAction = targetActionId >= 0L
    val context = LocalContext.current
    val noAddLabel = stringResource(R.string.panel_single_action_no_add)
    // Single-action mode: cannot add new markers on blank area - show hint only once per session to avoid repeated disruption
    var noAddHintShown by remember { mutableStateOf(false) }
    val selectedId by OverlayState.selectedItemId.collectAsState()
    val queueState by AppServices.queueScheduler.state.collectAsState()
    val renderDuringRun by OverlayState.renderDuringRun.collectAsState()
    // Markers pending deletion in the undo window are also hidden in the marker layer, not just the list.
    val pendingDeleteIds by OverlayState.pendingDeleteIds.collectAsState()
    // Hide markers while a run is active (RUNNING or PAUSED, honoring the render switch) so nothing
    // flashes on screen during pause transitions.
    val hideMarkers = !renderDuringRun &&
        (queueState == QueueRunStatus.RUNNING || queueState == QueueRunStatus.PAUSED)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val draw = singleAction || mode == OverlayMode.DRAW
    val markerColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    // Direction arrow uses a contrasting color (secondary) to distinguish it from the line's primary/selected color for clearer direction indication
    val arrowColor = MaterialTheme.colorScheme.secondary
    val badgeColor = MaterialTheme.colorScheme.onPrimary
    val badgeColorSelected = MaterialTheme.colorScheme.onTertiary
    val textMeasurer = rememberTextMeasurer()
    val hitRadiusPx = with(density) { 28.dp.toPx() }
    // Real-time preview for drag drawing: a straight line from start to the current point, cleared on release
    var previewLine by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    // Real-time preview when dragging existing markers (itemId to a canvas coordinate polyline).
    // Pointer moves are held in local state, so the full-screen marker layer and the panel
    // do not recompose on every move; the result is committed to the DB only on release.
    var dragPreview by remember { mutableStateOf<Pair<Long, List<Offset>>?>(null) }
    // On some ROMs (for example Vivo/Android 14) the marker window is restricted below the status bar.
    // Normalizing by canvas size gives an overall offset, so full-screen normalization is used.
    // ActionExecutor uses the same physical full-screen size (ScreenMetrics) for a consistent baseline.
    val (screenW, screenH) = ScreenMetrics.size(LocalContext.current)

    Box(Modifier.fillMaxSize()) {
        // Marker rendering layer: full-screen Canvas, click = circle + ID badge, swipe = line + start/end circles + direction arrow + ID badge
        Canvas(Modifier.matchParentSize()) {
            if (!hideMarkers) markers.filter { it.itemId !in pendingDeleteIds }.forEach { m ->
                // Performance: markers being dragged use local preview coordinates to avoid writing to global markers
                val previewPts = dragPreview?.takeIf { it.first == m.itemId }?.second
                val pts = previewPts
                    ?: m.points.map { Offset(it.x * screenW, it.y * screenH - (screenH - size.height)) }
                if (pts.isEmpty()) return@forEach
                val color = if (m.itemId == selectedId) selectedColor else markerColor
                when (m.type) {
                    ActionType.SWIPE -> {
                        // Start circle = draggable grab point; end point only draws a contrasting-color direction arrow (no overlapping dot).
                        // Both sized to match: start radius = arrow half-width = 12dp, arrow length = diameter 24dp
                        val handle = 12.dp.toPx()
                        val hasDir = pts.size >= 2
                        val last = pts.last()
                        val dir = if (hasDir) last - pts[pts.size - 2] else Offset.Zero
                        val dLen = dir.getDistance()
                        val validDir = hasDir && dLen > 1e-3f
                        // Arrow length/half-width shrinks with the line segment (cap at 24dp/12dp); the line is only drawn to the arrow root,
                        // so arrow tip sits exactly at line end and 14dp rounded cap doesn't exceed tip; degrades to pure arrow if shorter.
                        val arrowLen = if (validDir) minOf(dLen, handle * 2f) else 0f
                        val arrowHalf = if (arrowLen > 0f) arrowLen / 2f else handle
                        val term = if (validDir) last - dir / dLen * arrowLen else last
                        val path = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            if (pts.size > 2) {
                                pts.subList(1, pts.size - 1).forEach { lineTo(it.x, it.y) }
                            }
                            lineTo(term.x, term.y)
                        }
                        drawPath(
                            path,
                            color = color.copy(alpha = 0.7f),
                            // dp conversion for consistent cross-device visual appearance
                            style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                        )
                        if (hasDir) {
                            drawCircle(color = color, radius = arrowHalf, center = pts.first())
                            // Start circle gets a white stroke for visibility against any background
                            drawCircle(
                                color = Color.White,
                                radius = arrowHalf,
                                center = pts.first(),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            if (validDir) {
                                drawArrowHead(
                                    color = arrowColor,
                                    tip = last,
                                    direction = dir,
                                    halfWidth = arrowHalf,
                                    length = arrowLen
                                )
                            }
                        }
                    }
                    else -> {
                        // 0.55 opacity with a white overlay gives only ~2.2:1 contrast, hard to see; use 0.85 with a white stroke
                        drawCircle(
                            color = color.copy(alpha = 0.85f),
                            radius = 26.dp.toPx(),
                            center = pts.first()
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 26.dp.toPx() - 1.5.dp.toPx(),
                            center = pts.first(),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
                // ID badge (= execution order): number only; drawn at circle center for clicks, at the polyline midpoint for swipes, centered by actual text size
                val badgeAt = if (m.type == ActionType.SWIPE) {
                    (pts.first() + pts.last()) / 2f
                } else {
                    pts.first()
                }
                val badgeTextColor = if (m.itemId == selectedId) badgeColorSelected else badgeColor
                val badgeLayout = textMeasurer.measure(
                    text = "${m.orderId}",
                    style = TextStyle(fontSize = 12.sp, color = badgeTextColor)
                )
                // White text directly on a semi-transparent circle gives only ~2.2:1 contrast, almost unreadable.
                // Use a solid-fill circle + white text + white stroke for clear readability on any background.
                val badgeFill = if (m.itemId == selectedId) selectedColor else markerColor
                val badgeRadius = maxOf(badgeLayout.size.width, badgeLayout.size.height) / 2f + 5.dp.toPx()
                drawCircle(color = badgeFill, radius = badgeRadius, center = badgeAt)
                drawCircle(
                    color = Color.White,
                    radius = badgeRadius,
                    center = badgeAt,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawText(
                    textLayoutResult = badgeLayout,
                    topLeft = Offset(
                        badgeAt.x - badgeLayout.size.width / 2f,
                        badgeAt.y - badgeLayout.size.height / 2f
                    )
                )
            }
            // Real-time preview line during drawing; it becomes a formal marker on release
            previewLine?.let { (s, e) ->
                // Preview uses the same structure as the formal marker; start circle + end contrasting arrow, line drawn to the arrow root,
                // arrow shrinks with line segment length
                val handle = 12.dp.toPx()
                val dir = e - s
                val dLen = dir.getDistance()
                val validDir = dLen > 1e-3f
                val arrowLen = if (validDir) minOf(dLen, handle * 2f) else 0f
                val arrowHalf = if (arrowLen > 0f) arrowLen / 2f else handle
                val term = if (validDir) e - dir / dLen * arrowLen else e
                drawLine(
                    color = markerColor.copy(alpha = 0.7f),
                    start = s,
                    end = term,
                    strokeWidth = 14.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(color = markerColor, radius = arrowHalf, center = s)
                if (validDir) {
                    drawArrowHead(
                        color = arrowColor,
                        tip = e,
                        direction = dir,
                        halfWidth = arrowHalf,
                        length = arrowLen
                    )
                }
            }
        }
        // Drawing/drag collection layer - only in draw mode; tap existing marker = drag, tap blank = new marker (user confirms both states)
        if (draw) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        // awaitEachGesture block is already inside awaitPointerEventScope - cannot nest (suspend check restriction)
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // Markers pending deletion in the undo window are not visible, so hit detection must exclude them,
                            // otherwise you could "drag invisible markers". Reading StateFlow directly avoids capturing stale values.
                            val fresh = OverlayState.markers.value
                                .filter { it.itemId !in OverlayState.pendingDeleteIds.value }
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            // Window top offset relative to the screen (= screen height - canvas height), used for full-screen normalization
                            val originY = screenH - h
                            // Hit detection - prioritize the nearest editable vertex (point = itself, line = start/end),
                            // otherwise hitting line body = move entire line; each point/vertex is independently editable
                            var target: OverlayMarker? = null
                            var vertexIndex = -1
                            for (m in fresh) {
                                val logical = logicalPoints(m, screenW, screenH, originY)
                                val vi = nearestVertex(logical, down.position, hitRadiusPx)
                                if (vi >= 0) {
                                    target = m
                                    vertexIndex = vi
                                    break
                                }
                                if (m.type == ActionType.SWIPE && logical.size >= 2 &&
                                    distanceToSegment(down.position, logical.first(), logical.last()) <= hitRadiusPx
                                ) {
                                    target = m
                                    vertexIndex = -1
                                    break
                                }
                            }
                            val hit = target
                            if (hit != null) {
                                OverlayState.select(hit.itemId)
                                var current = logicalPoints(hit, screenW, screenH, originY)
                                var last = down.position
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val delta = change.position - last
                                        last = change.position
                                        // Clamp current to the screen before the next iteration,
                                        // otherwise after dragging to edge, visual stops but current keeps accumulating, causing "dead zone" when dragging back
                                        current = (if (vertexIndex >= 0) {
                                            current.toMutableList().also { it[vertexIndex] = it[vertexIndex] + delta }
                                        } else {
                                            current.map { it + delta }
                                        }).map { Offset(it.x.coerceIn(0f, w), it.y.coerceIn(0f, h)) }
                                        // Only update the local preview, not the global markers, so the panel does not recompose
                                        dragPreview = hit.itemId to current
                                        change.consume()
                                    }
                                } finally {
                                    // Clear the preview on normal lift or coroutine cancellation to avoid leftover ghost lines
                                    dragPreview = null
                                }
                                // Drag end: commit final coordinates to global state and persist in one shot (panel and persistence read the new values afterwards)
                                val final = current.map {
                                    Offset(
                                        (it.x / screenW).coerceIn(0f, 0.999f),
                                        ((it.y + originY) / screenH).coerceIn(0f, 0.999f)
                                    )
                                }
                                OverlayState.updateMarkerPoints(hit.itemId, final)
                                scope.launch { manager.persistMarkerPoints(hit, final) }
                            } else if (singleAction) {
                                // Single-action mode disallows new markers: keep the action selected and hint only once per session
                                OverlayState.select(OverlayState.SINGLE_ACTION_ITEM_ID)
                                if (!noAddHintShown) {
                                    noAddHintShown = true
                                    Toast.makeText(context, noAddLabel, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                OverlayState.select(null)
                                val startAt = SystemClock.elapsedRealtime()
                                val start = down.position
                                var current = start
                                previewLine = start to current
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        current = change.position
                                        previewLine = start to current
                                        change.consume()
                                    }
                                } finally {
                                    // Clear the preview on lift or cancellation (mode switch / node removal) to avoid ghost lines
                                    previewLine = null
                                }
                                val duration = SystemClock.elapsedRealtime() - startAt
                                scope.launch {
                                    if ((current - start).getDistance() > viewConfiguration.touchSlop) {
                                        // A drag beyond touch slop records a straight-line swipe from start to end (direction shown by the arrow)
                                        manager.addDrawnMarker(
                                            listOf(
                                                Offset(
                                                    (start.x / screenW).coerceIn(0f, 0.999f),
                                                    ((start.y + originY) / screenH).coerceIn(0f, 0.999f)
                                                ),
                                                Offset(
                                                    (current.x / screenW).coerceIn(0f, 0.999f),
                                                    ((current.y + originY) / screenH).coerceIn(0f, 0.999f)
                                                )
                                            ),
                                            duration
                                        )
                                    } else {
                                        // Tap = click marker
                                        manager.addDrawnMarker(
                                            listOf(
                                                Offset(
                                                    (start.x / screenW).coerceIn(0f, 0.999f),
                                                    ((start.y + originY) / screenH).coerceIn(0f, 0.999f)
                                                )
                                            ),
                                            duration
                                        )
                                    }
                                }
                            }
                        }
                    }
            )
        }
    }
}

@Composable
fun PanelContent(manager: OverlayManager) {
    val expanded by OverlayState.panelExpanded.collectAsState()
    val mode by OverlayState.mode.collectAsState()
    val markers by OverlayState.markers.collectAsState()
    val selectedId by OverlayState.selectedItemId.collectAsState()
    // Single-action mode hides queue-level controls and mode switching; the title becomes "edit action"
    val targetActionId by OverlayState.targetActionId.collectAsState()
    val singleAction = targetActionId >= 0L
    val queueState by AppServices.queueScheduler.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Handle drag uses the system touchSlop; PanelDragState is a plain object to avoid recomposition overhead during gestures
    val touchSlop = remember { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    // Read the label during composition so gesture callbacks can use it (lint: LocalContextGetResourceValueCall)
    val loadedCopyLabel = stringResource(R.string.panel_queue_loaded)
    val stopFirstLabel = stringResource(R.string.panel_stop_before_load)
    // Surface the scheduler's last failure as a toast and clear it after consuming; the SKIP strategy
    // otherwise drops failed actions silently and the user never learns which step had no effect.
    val lastFailure by AppServices.queueScheduler.lastFailure.collectAsState()
    // Use LocalResources instead of querying Context resources during composition (lint: LocalContextGetResourceValueCall)
    val resources = LocalResources.current
    // Dense failures (SKIP can fail many times in a row) would queue a flood of toasts, and while running
    // the progress capsule already shows the reason - this panel only notifies while it is visible.
    var lastFailureToastAt by remember { mutableStateOf(0L) }
    // The capsule and this panel share one StateFlow; consuming while a run is active would erase the failure
    // text the capsule is still showing. Consume only after leaving RUNNING/PAUSED, then toast (throttled) and clear.
    val failureConsumable = queueState != QueueRunStatus.RUNNING && queueState != QueueRunStatus.PAUSED
    LaunchedEffect(lastFailure, failureConsumable) {
        val failure = lastFailure ?: return@LaunchedEffect
        if (!failureConsumable) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailureToastAt >= FAILURE_TOAST_THROTTLE_MS) {
            lastFailureToastAt = now
            // The core layer only reports a failure enum; map it to localized text here so it never leaks into the UI
            Toast.makeText(
                context,
                resources.getString(R.string.panel_action_failed, failureText(resources, failure)),
                Toast.LENGTH_SHORT
            ).show()
        }
        // Clear only after showing, so the same failure is not re-toasted on the next run-state change
        AppServices.queueScheduler.clearLastFailure()
    }
    val drag = remember { PanelDragState() }
    // Each delete runs in its own coroutine so consecutive deletes don't cancel each other (a single
    // LaunchedEffect would be overridden by the next delete); pending-delete persistence lives in OverlayManager.
    val deletedMarkerLabel = stringResource(R.string.panel_deleted_marker)
    val undoLabel = stringResource(R.string.panel_undo)
    val snackbarHostState = remember { SnackbarHostState() }
    val pendingDeleteIds by OverlayState.pendingDeleteIds.collectAsState()
    val requestDelete: (OverlayMarker) -> Unit = { m ->
        if (m.itemId !in pendingDeleteIds) {
            // Deletion is registered with the manager (independent scope); the panel only shows the undo entry.
            // Commit timing is driven by this snackbar's result, with hide() committing as a fallback on close.
            manager.scheduleMarkerDelete(m)
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = deletedMarkerLabel,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short
                )
                // Only the undo action cancels; timeout or dismissal commits immediately. Drive it by the snackbar
                // result, not a timer: SnackbarHostState shows one snackbar at a time, so a timed commit could
                // persist a queued delete while its undo is still on screen.
                if (result == SnackbarResult.ActionPerformed) {
                    manager.undoScheduledDelete(m.itemId)
                } else {
                    manager.commitScheduledDelete(m.itemId)
                }
            }
        }
    }

    // The SnackbarHost must stay composed in both states: removing it in the collapsed state cancels the
    // auto-dismiss timer and leaves showSnackbar suspended forever (deletes then neither commit nor undo).
    Box {
    if (!expanded) {
        // Collapsed state is a circular handle: transparent background with an outline ring and themed paw icon.
        // Dragging starts only after touchSlop is exceeded, then the window follows the finger frame-by-frame.
        // During a run/pause the whole panel goes GONE and is replaced by the non-touchable progress capsule,
        // so the collapsed paw is never visible then; no run-state styling or tap-to-pause belongs here.
        val pawColor = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .drawBehind {
                    // Inset the ring by half the stroke width so CircleShape clipping doesn't visually halve it
                    val stroke = 1.5.dp.toPx()
                    val ring = pawColor.copy(alpha = 0.55f)
                    drawCircle(
                        color = ring,
                        radius = size.minDimension / 2f - stroke / 2f,
                        style = Stroke(width = stroke)
                    )
                }
                // Drive window movement from raw (screen) coordinates: Compose local coords shift as the window
                // moves, creating a move -> shift -> negative delta -> rebound feedback loop (stutter);
                // raw deltas always equal the finger's true displacement.
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // Lock onto the active pointer id: rawX/rawY always describe index 0, which reorders after lifts
                            drag.activeId = event.getPointerId(0)
                            drag.lastRawX = event.rawX
                            drag.lastRawY = event.rawY
                            drag.accumX = 0f
                            drag.accumY = 0f
                            drag.carryX = 0f
                            drag.carryY = 0f
                            drag.started = false
                            drag.aborted = false
                            true
                        }
                        // Ignore extra fingers and keep following the original one to avoid cross-pointer coordinate jumps
                        MotionEvent.ACTION_POINTER_DOWN -> true
                        MotionEvent.ACTION_MOVE -> {
                            val idx = event.findPointerIndex(drag.activeId)
                            if (idx >= 0) {
                                val rawX = event.getRawX(idx)
                                val rawY = event.getRawY(idx)
                                val dx = rawX - drag.lastRawX
                                val dy = rawY - drag.lastRawY
                                drag.lastRawX = rawX
                                drag.lastRawY = rawY
                                if (!drag.started) {
                                    drag.accumX += dx
                                    drag.accumY += dy
                                    if (drag.accumX * drag.accumX + drag.accumY * drag.accumY >
                                        touchSlop * touchSlop
                                    ) {
                                        drag.started = true
                                        // On first exceeding slop, apply all displacement since press-down so the handle lands under the finger
                                        drag.carryX = drag.accumX
                                        drag.carryY = drag.accumY
                                    }
                                } else {
                                    drag.carryX += dx
                                    drag.carryY += dy
                                }
                                if (drag.started) {
                                    // Carry sub-pixel remainders so slow drags don't lose movement to integer truncation
                                    val ix = drag.carryX.toInt()
                                    val iy = drag.carryY.toInt()
                                    if (ix != 0 || iy != 0) {
                                        drag.carryX -= ix
                                        drag.carryY -= iy
                                        manager.movePanelBy(ix, iy)
                                    }
                                }
                            }
                            true
                        }
                        // Lifting the active pointer ends the drag and must not register as a click-to-expand
                        MotionEvent.ACTION_POINTER_UP -> {
                            if (event.getPointerId(event.actionIndex) == drag.activeId) {
                                drag.activeId = -1
                                drag.started = false
                                drag.aborted = true
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!drag.started && !drag.aborted) {
                                // The paw only expands the panel and moves it. Pause/stop live in the expanded panel;
                                // overloading this control made pause nearly unreachable (any movement reads as a drag).
                                OverlayState.setPanelExpanded(true)
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            drag.activeId = -1
                            drag.aborted = true
                            true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_paw),
                contentDescription = stringResource(R.string.overlay_handle_desc),
                colorFilter = ColorFilter.tint(pawColor),
                modifier = Modifier
                    .size(30.dp)
            )
        }
    } else {
        // ===== Expanded state: switch content wholesale by mode (draw page / pass-through page), each showing only its own controls =====
        val queueName by OverlayState.queueName.collectAsState()
        val loopCount by OverlayState.queueLoopCount.collectAsState()
        val startDelay by OverlayState.queueStartDelay.collectAsState()
        val renderDuringRun by OverlayState.renderDuringRun.collectAsState()
        // Overlay windows cannot host Android Dialogs (service context has no window token), so modals are in-panel pages
        var modal by remember { mutableStateOf<PanelModal?>(null) }

        // Fixed panel height (70% of screen, capped at 560dp for tall/folded screens): a WRAP_CONTENT window
        // clips overflow, and a content-sized height would resize on every page/modal switch and shake controls.
        val panelHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.7f).coerceAtMost(560.dp)
        // The undo snackbar host is pinned to the bottom of the Box instead of the end of the scrollable
        // Column, so it stays visible and tappable no matter how long the panel content is.
        Box(
            Modifier
                // Panel width comes from OverlayManager via fixed window LayoutParams, not a fraction: an
                // overlay's width constraint is "screen - x", so fillMaxWidth would shrink it while dragging right.
                .fillMaxWidth()
                .height(panelHeight)
                .clip(RoundedCornerShape(16.dp))
                // Opaque background + 1dp outline keep the panel bounds legible over any app; an elevated
                // container color keeps the edge distinguishable in dark mode, where surfaces share the same gray.
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // Scroll inside the background but outside the padding, so the background always fills the
                    // panel and the bottom padding scrolls fully into view.
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Attach dragging to the title row so the expanded panel can be repositioned too; taps on
                    // child controls still work because movement only starts after touchSlop.
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            manager.movePanelBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        }
                    }
                ) {
                    Text(
                        // Title states the editing scope (single action vs gesture/queue) and the current page
                        if (singleAction) {
                            stringResource(R.string.panel_title_edit_action, queueName)
                        } else {
                            stringResource(
                                if (mode == OverlayMode.DRAW) R.string.panel_title_edit
                                else R.string.panel_title_run
                            )
                        },
                        style = MaterialTheme.typography.titleSmall,
                        // weight(1f) + ellipsis so long titles don't push the collapse/close buttons off the panel
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { OverlayState.setPanelExpanded(false) }) {
                        Text(stringResource(R.string.panel_collapse))
                    }
                    // In-panel close entry: when editing was opened from a subpage, only the paw remains on the
                    // launcher with no other way to dismiss; this closes the overlay via the foreground service.
                    TextButton(onClick = {
                        MainForegroundService.start(context, MainForegroundService.ACTION_OVERLAY_OFF)
                    }) {
                        Text(stringResource(R.string.panel_close))
                    }
                }

                when (val mm = modal) {
                    null -> {
                        // Single-action mode has no draw/passthrough switch; markers stay drag-editable
                        if (!singleAction) {
                            Row {
                                FilterChip(
                                    selected = mode == OverlayMode.DRAW,
                                    onClick = { manager.setDrawMode(true) },
                                    label = { Text(stringResource(R.string.panel_mode_draw)) }
                                )
                                Spacer(Modifier.width(8.dp))
                                FilterChip(
                                    selected = mode == OverlayMode.PASSTHROUGH,
                                    onClick = { manager.setDrawMode(false) },
                                    label = { Text(stringResource(R.string.panel_mode_pass)) }
                                )
                            }
                        }
                        // Keep if/else so only one page is composed at a time: crossfade/animated content would
                        // compose both, size to the larger, and overlap them. The wrapper must be a Column (the
                        // pages emit multiple siblings; a Box would stack them), preserving the outer 8dp spacing.
                        val pageAlpha = remember { Animatable(0f) }
                        LaunchedEffect(mode) {
                            pageAlpha.snapTo(0f)
                            pageAlpha.animateTo(1f, tween(durationMillis = 180))
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = pageAlpha.value },
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        if (mode == OverlayMode.DRAW || singleAction) {
                            // Draw page (record / edit)
                            DrawPage(
                                manager = manager,
                                markers = markers,
                                selectedId = selectedId,
                                queueName = queueName,
                                singleAction = singleAction,
                                pendingDeleteIds = pendingDeleteIds,
                                onDelete = requestDelete,
                                onRenameMarker = { modal = PanelModal.RenameMarker(it) },
                                onReorder = { scope.launch { manager.reorderMarkers(it) } },
                                onLoadQueue = { modal = PanelModal.LoadQueue },
                                onLoadAction = { modal = PanelModal.LoadAction }
                            )
                        } else {
                            // Pass-through page (operate / execute)
                            PassPage(
                                manager = manager,
                                queueName = queueName,
                                loopCount = loopCount,
                                startDelay = startDelay,
                                renderDuringRun = renderDuringRun,
                                onRenameQueue = { modal = PanelModal.RenameQueue },
                                onLoadQueue = { modal = PanelModal.LoadQueue }
                            )
                        }
                        }
                    }
                    is PanelModal.RenameMarker -> RenameModal(
                        title = stringResource(R.string.rename_action_title),
                        initial = mm.marker.name,
                        onConfirm = { nm ->
                            scope.launch { manager.renameMarker(mm.marker, nm) }
                            modal = null
                        },
                        onCancel = { modal = null }
                    )
                    PanelModal.RenameQueue -> RenameModal(
                        title = stringResource(R.string.rename_queue_title),
                        initial = queueName,
                        onConfirm = { nm ->
                            scope.launch { manager.renameQueue(OverlayState.overlayQueueId, nm) }
                            modal = null
                        },
                        onCancel = { modal = null }
                    )
                    PanelModal.LoadQueue -> QueuePickerModal(
                        manager = manager,
                        onPick = { id ->
                            scope.launch {
                                val ok = manager.loadQueue(id)
                                Toast.makeText(
                                    context,
                                    if (ok) loadedCopyLabel else stopFirstLabel,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            modal = null
                        },
                        onCancel = { modal = null }
                    )
                    PanelModal.LoadAction -> ActionPickerModal(
                        manager = manager,
                        onPick = { id ->
                            scope.launch { manager.addActionToOverlay(id) }
                            modal = null
                        },
                        onCancel = { modal = null }
                    )
                }

                Text(queueStateLabel(queueState), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // Undo snackbar pinned to the panel bottom so it doesn't scroll away, and kept composed even when
    // collapsed - removing it would cancel the auto-dismiss timer and suspend showSnackbar forever.
    SnackbarHost(
        snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
    )
    }
}

// In-panel modal pages: overlay windows have no window token, so Android Dialogs would crash
private sealed interface PanelModal {
    data class RenameMarker(val marker: OverlayMarker) : PanelModal
    object RenameQueue : PanelModal
    object LoadQueue : PanelModal
    object LoadAction : PanelModal
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawPage(
    manager: OverlayManager,
    markers: List<OverlayMarker>,
    selectedId: Long?,
    queueName: String,
    singleAction: Boolean,
    pendingDeleteIds: Set<Long>,
    onDelete: (OverlayMarker) -> Unit,
    onRenameMarker: (OverlayMarker) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onLoadQueue: () -> Unit,
    onLoadAction: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // LocalContext resource reads don't invalidate on configuration changes; use LocalResources (lint rule)
    val resources = LocalResources.current
    // Persistent single-action hint (drag to reposition, adding is disabled) instead of the tap-to-add guidance
    Text(
        stringResource(if (singleAction) R.string.panel_single_action_hint else R.string.panel_draw_hint),
        style = MaterialTheme.typography.bodySmall
    )
    // A single action has no ordering or deletion semantics, so skip the list entirely
    if (!singleAction) {
        if (markers.isEmpty()) {
            Text(stringResource(R.string.panel_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            // Undo handling lives in PanelContent; this list only hides items pending deletion
            MarkerList(
                markers = markers.filter { it.itemId !in pendingDeleteIds },
                selectedId = selectedId,
                onSelect = { OverlayState.select(it) },
                onRename = onRenameMarker,
                onDelete = onDelete,
                onReorder = onReorder
            )
        }
    }
    // Selected action parameter editing (drag position on the overlay)
    val selected = markers.firstOrNull { it.itemId == selectedId }
    if (selected != null) {
        // Group heading separating per-action parameters from whole-queue settings
        Text(stringResource(R.string.panel_group_action), style = MaterialTheme.typography.titleSmall)
        // Key on the actual values: after applying, persistMarkerConfig clamps and persists, and the refreshed
        // marker changes these values, resetting the fields to the effective stored numbers instead of stale text.
        var durText by remember(selected.itemId, selected.durationMs) { mutableStateOf(selected.durationMs.toString()) }
        var repText by remember(selected.itemId, selected.repeatCount) { mutableStateOf(selected.repeatCount.toString()) }
        var delayText by remember(selected.itemId, selected.delayMs) { mutableStateOf(selected.delayMs.toString()) }
        OutlinedTextField(
            value = durText,
            onValueChange = { durText = it },
            label = { Text(stringResource(R.string.panel_duration)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // One-tap presets for common durations instead of typing milliseconds
                        MsPresets(listOf(300L, 500L, 1000L, 2000L)) { durText = it.toString() }
        OutlinedTextField(
            value = repText,
            onValueChange = { repText = it.filter { ch -> ch.isDigit() } },
            label = { Text(stringResource(R.string.panel_repeat_zero_hint)) },
            supportingText = { Text(stringResource(R.string.hint_zero_infinite)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = delayText,
            onValueChange = { delayText = it.filter { ch -> ch.isDigit() } },
            label = { Text(stringResource(R.string.panel_interval)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
                        MsPresets(listOf(100L, 300L, 500L, 1000L)) { delayText = it.toString() }
        TextButton(onClick = {
            val dur = durText.toLongOrNull()
            // Invalid input must give visible feedback instead of silently returning, which reads as "apply does nothing"
            if (dur == null) {
                Toast.makeText(context, resources.getString(R.string.panel_invalid_duration), Toast.LENGTH_SHORT).show()
                return@TextButton
            }
            // 0 repeats means infinite
            val rep = (repText.toIntOrNull() ?: 0).coerceIn(0, 999)
            val interval = (delayText.toLongOrNull() ?: 0L).coerceIn(0, 60_000)
            scope.launch {
                val ok = manager.persistMarkerConfig(selected, dur, rep, interval)
                Toast.makeText(
                    context,
                    resources.getString(if (ok) R.string.panel_applied else R.string.panel_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }) {
            Text(stringResource(R.string.panel_apply))
        }
    }
    // Action-level editing has no whole-queue semantics: hide queue naming/save/load/clear entirely
    if (!singleAction) {
    // Queue naming control + save as a named queue (panel content is kept after saving)
    Text(stringResource(R.string.panel_group_queue), style = MaterialTheme.typography.titleSmall)
    var nameInput by remember(queueName) { mutableStateOf(queueName) }
    // Inline confirmation before clearing so an accidental tap can't wipe all drawn markers
    var confirmClear by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = nameInput,
        onValueChange = { nameInput = it },
        label = { Text(stringResource(R.string.panel_queue_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    // Group save and load together so related library actions are not scattered
    Text(stringResource(R.string.panel_group_library), style = MaterialTheme.typography.titleSmall)
    // FlowRow instead of a single Row: long labels would push the last button past the panel edge, making save/load unreachable
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val nm = nameInput.trim()
            if (nm.isEmpty()) {
                // An empty name must not fail silently - without feedback users assume the button is broken
                Toast.makeText(context, resources.getString(R.string.panel_name_required), Toast.LENGTH_SHORT).show()
                return@TextButton
            }
            scope.launch {
                manager.saveOverlayQueueAs(nm)
                Toast.makeText(context, resources.getString(R.string.panel_queue_saved, nm), Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(stringResource(R.string.panel_save_queue))
        }
        TextButton(onClick = onLoadAction) { Text(stringResource(R.string.panel_load_action)) }
        TextButton(onClick = onLoadQueue) { Text(stringResource(R.string.panel_load_queue)) }
    }
    // Clearing wipes all markers irreversibly, so require one inline confirmation
    if (confirmClear) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.panel_clear_confirm),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = {
                confirmClear = false
                scope.launch { manager.clearMarkers() }
            }) { Text(stringResource(R.string.dialog_confirm), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.dialog_cancel)) }
        }
    } else {
        TextButton(onClick = { confirmClear = true }) {
            Text(stringResource(R.string.panel_clear))
        }
    }
    }
}

// Millisecond presets shown as readable seconds/ms text; a FlowRow wraps chips that a single Row would
// squeeze or overflow, and every chip keeps a 48dp minimum touch target.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MsPresets(values: List<Long>, onPick: (Long) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEach { v ->
            val label = presetLabel(v)
            // Keep a 48dp minimum touch target or the chip is hard to hit accurately
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onPick(v) }
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// Multiples of 1000 display as seconds to avoid mental math; units come from string resources, not hardcoded s/ms
@Composable
private fun presetLabel(v: Long): String =
    if (v > 0 && v % 1000L == 0L) {
        stringResource(R.string.unit_seconds, (v / 1000L).toString())
    } else {
        stringResource(R.string.unit_ms, v.toString())
    }

// The round placeholder must be the current round, not the total round count (passing the total showed
// "round 3" immediately for a 3-round run). totalRounds == 0 means infinite.
@Composable
private fun progressLabel(round: Int, totalRounds: Int, index: Int, total: Int): String =
    when {
        totalRounds == 0 ->
            // Spell out "infinite" instead of the infinity glyph, which some fonts lack
            stringResource(
                R.string.panel_progress_infinite,
                round.toString(),
                index.toString(),
                total.toString()
            )
        // A single round makes "round 1/1" redundant; show only the action progress
        totalRounds == 1 ->
            stringResource(
                R.string.panel_progress_single,
                index.toString(),
                total.toString()
            )
        else ->
            stringResource(
                R.string.panel_progress,
                round.toString(),
                totalRounds.toString(),
                index.toString(),
                total.toString()
            )
    }

// Compact progress capsule shown while a run is active. It is intentionally read-only: its window is
// non-touchable so it never swallows injected gestures. The user stops the run by tapping the screen
// (tap-watch window) or via the notification button.
@Composable
fun RunningProgressContent() {
    val progress by AppServices.queueScheduler.progress.collectAsState()
    val runState by AppServices.queueScheduler.state.collectAsState()
    val resources = LocalResources.current
    // Progress numbers change in discrete steps, so animate the fraction for smooth motion. Track overall
    // progress when the round count is known (>0); fall back to within-round progress for infinite rounds.
    val targetFraction = when {
        progress.total <= 0 -> 0f
        progress.totalRounds == 0 -> progress.index.toFloat() / progress.total
        else -> {
            val done = (progress.round - 1).coerceAtLeast(0) * progress.total + progress.index
            done.toFloat() / (progress.totalRounds * progress.total)
        }
    }.coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 300),
        label = "runProgress"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_paw),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = Modifier.size(18.dp)
            )
            // progress is (0,0,0,0) for a frame at start/end; without this guard the text flashes "0 of 0"
            if (progress.total > 0) {
                Text(
                    text = progressLabel(progress.round, progress.totalRounds, progress.index, progress.total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (runState == QueueRunStatus.PAUSED) {
                Text(
                    text = stringResource(R.string.queue_state_paused),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        // Thin 4dp bar so the capsule size barely changes; for infinite rounds it tracks within-round progress
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f),
                strokeCap = StrokeCap.Round
            )
        }
        Text(
            text = stringResource(R.string.panel_tap_to_stop),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        // Keep the failure reason visible on the run screen; a transient toast alone is easily missed
        val failure by AppServices.queueScheduler.lastFailure.collectAsState()
        failure?.let { f ->
            Text(
                text = stringResource(R.string.panel_action_failed, failureText(resources, f)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PassPage(
    manager: OverlayManager,
    queueName: String,
    loopCount: Int,
    startDelay: Long,
    renderDuringRun: Boolean,
    onRenameQueue: () -> Unit,
    onLoadQueue: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Without accessibility enabled every dispatched gesture fails silently, so warn up front
    val a11yConnected by AccessibilityBridge.serviceConnected.collectAsState()
    // Run state drives the text and enabled state of the run/resume/stop buttons
    val runState by AppServices.queueScheduler.state.collectAsState()
    // The double-zero (infinite loops x infinite repeat) warning is shown once; a second tap counts as confirmation
    var zeroInfiniteWarned by remember { mutableStateOf(false) }
    // Run progress (round / action index) for display in the panel
    val progress by AppServices.queueScheduler.progress.collectAsState()
    if (!a11yConnected) {
        Text(
            stringResource(R.string.panel_a11y_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.panel_current_queue, queueName),
            style = MaterialTheme.typography.bodySmall,
            // weight(1f) + ellipsis so long queue names don't crowd out the rename button
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Visible rename entry instead of a hidden tap-the-text interaction
        TextButton(onClick = onRenameQueue) { Text(stringResource(R.string.action_rename)) }
    }
    // Whole-queue settings get their own group
    Text(stringResource(R.string.panel_group_queue), style = MaterialTheme.typography.titleSmall)
    var loopInput by remember(loopCount) { mutableStateOf(loopCount.toString()) }
    var delayInput by remember(startDelay) { mutableStateOf(startDelay.toString()) }
    OutlinedTextField(
        value = loopInput,
        onValueChange = { loopInput = it.filter { ch -> ch.isDigit() } },
        label = { Text(stringResource(R.string.panel_queue_loop)) },
        supportingText = { Text(stringResource(R.string.hint_zero_infinite)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = delayInput,
        onValueChange = { delayInput = it.filter { ch -> ch.isDigit() } },
        label = { Text(stringResource(R.string.panel_queue_start_delay)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    // One-tap presets for common start delays
    MsPresets(listOf(0L, 1000L, 3000L, 5000L, 10000L)) { delayInput = it.toString() }
    TextButton(onClick = {
        val lc = (loopInput.toIntOrNull() ?: 1).coerceIn(0, 999)
        val sd = (delayInput.toLongOrNull() ?: 0L).coerceIn(0, 3_600_000)
        scope.launch { manager.updateOverlayQueueSettings(lc, sd) }
    }) {
        Text(stringResource(R.string.panel_apply))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.panel_render_during_run),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = renderDuringRun, onCheckedChange = { OverlayState.setRenderDuringRun(it) })
    }
    // Show queue progress while running so an active run doesn't look unresponsive; skip rendering while
    // progress is not ready yet (total == 0) to avoid flashing "0 of 0".
    if (runState == QueueRunStatus.RUNNING && progress.total > 0) {
        Text(
            text = progressLabel(progress.round, progress.totalRounds, progress.index, progress.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Row {
        TextButton(onClick = {
            // Execution is a no-op without accessibility; block here and guide the user to enable it rather than fail silently
            if (!AccessibilityBridge.serviceConnected.value) {
                Toast.makeText(context, R.string.panel_a11y_required, Toast.LENGTH_LONG).show()
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@TextButton
            }
            // Validate before anything collapses: an empty/disabled queue or an already-running queue must
            // give feedback instead of silently doing nothing.
            scope.launch {
                val id = OverlayState.overlayQueueId
                // While paused, tapping run resumes the current queue instead of restarting from the beginning
                if (AppServices.queueScheduler.state.value == QueueRunStatus.PAUSED) {
                    manager.setDrawMode(false)
                    AppServices.queueScheduler.resumeQueue()
                    return@launch
                }
                val enabledCount = if (id > 0) {
                    AppServices.db.queueDao().getItemsForQueue(id).count { it.enabled }
                } else {
                    0
                }
                if (enabledCount == 0) {
                    Toast.makeText(context, R.string.panel_queue_empty_run, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (AppServices.queueScheduler.state.value == QueueRunStatus.RUNNING) {
                    Toast.makeText(context, R.string.panel_queue_running, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Action-level 0 (infinite) inside queue-level 0 (infinite) never ends and never advances;
                // warn once before the first start, and treat a second tap as confirmation.
                if (loopCount == 0 && !zeroInfiniteWarned) {
                    val enabledItems = if (id > 0) {
                        AppServices.db.queueDao().getItemsForQueue(id).filter { it.enabled }
                    } else {
                        emptyList()
                    }
                    val hasInfinite = enabledItems.isNotEmpty() && AppServices.db.actionDao()
                        .getByIdsWithSteps(enabledItems.map { it.actionId }.distinct())
                        .any { it.action.repeatCount == 0 }
                    if (hasInfinite) {
                        zeroInfiniteWarned = true
                        Toast.makeText(context, R.string.panel_zero_infinite_warn, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
                // Switch to passthrough first so replayed gestures aren't captured by the drawing layer;
                // keep the panel expanded so progress and the pause/stop controls stay visible.
                manager.setDrawMode(false)
                AppServices.queueScheduler.startQueue(id)
            }
            // While running the button is disabled (label only) so tapping it doesn't just show a toast
        },
            enabled = runState != QueueRunStatus.RUNNING
        ) {
            // Button text follows run state so it doesn't still say "run" while paused
            Text(
                stringResource(
                    when (runState) {
                        QueueRunStatus.RUNNING -> R.string.panel_queue_running
                        QueueRunStatus.PAUSED -> R.string.panel_resume
                        else -> R.string.panel_execute
                    }
                )
            )
        }
        // No pause button here: during a run the panel is replaced by the progress capsule, so an overlay
        // pause button would be unreachable. Pause/resume stays available in QueueActivity.
        TextButton(
            onClick = { scope.launch { AppServices.queueScheduler.stopQueue() } },
            enabled = runState == QueueRunStatus.RUNNING || runState == QueueRunStatus.PAUSED
        ) {
            Text(stringResource(R.string.panel_stop))
        }
    }
    // Queue loading belongs in the library (save/load) group
    Text(stringResource(R.string.panel_group_library), style = MaterialTheme.typography.titleSmall)
    TextButton(onClick = onLoadQueue) { Text(stringResource(R.string.panel_load_queue)) }
}

// Marker list: drag the handle vertically to reorder, tap the name to rename, tap the row to select
@Composable
private fun MarkerList(
    markers: List<OverlayMarker>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onRename: (OverlayMarker) -> Unit,
    onDelete: (OverlayMarker) -> Unit,
    onReorder: (List<Long>) -> Unit
) {
    // Plain Column instead of a nested LazyColumn (same-direction nesting steals the outer scroll and is
    // disallowed under infinite height constraints). Row heights are measured per row for drag math;
    // key(itemId) keeps per-item state.
    Column(Modifier.fillMaxWidth()) {
        markers.forEach { m ->
            key(m.itemId) {
            val selected = m.itemId == selectedId
            // The drag callback is long-lived and would capture stale markers/index; rememberUpdatedState keeps
            // it reading the latest list so a second drag doesn't rebuild from the old order and lose the reorder.
            val currentMarkers by rememberUpdatedState(markers)
            // During a drag only accumulate the offset and translate visually; commit the reorder once on release
            // so recomposition can't interrupt the gesture
            var dragOffset by remember(m.itemId) { mutableStateOf(0f) }
            var rowHeight by remember(m.itemId) { mutableStateOf(0f) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { rowHeight = it.height.toFloat() }
                    .graphicsLayer { translationY = dragOffset }
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    )
                    .clickable { onSelect(if (selected) null else m.itemId) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Drag handle exposes TalkBack move up/down actions and an enlarged touch target
                val moveBy: (Int) -> Unit = { delta ->
                    val ids = currentMarkers.map { it.itemId }.toMutableList()
                    val from = ids.indexOf(m.itemId)
                    val target = from + delta
                    if (from >= 0 && target in ids.indices) {
                        val moved = ids.removeAt(from)
                        ids.add(target, moved)
                        onReorder(ids)
                    }
                }
                val upLabel = stringResource(R.string.panel_move_up)
                val downLabel = stringResource(R.string.panel_move_down)
                val handleLabel = stringResource(R.string.panel_drag_handle)
                Box(
                    modifier = Modifier
                        // 48dp touch target to meet the Material minimum
                        .size(48.dp)
                        .pointerInput(m.itemId) {
                            detectDragGestures(
                                onDragEnd = {
                                    val h = if (rowHeight > 0f) rowHeight else 1f
                                    val shift = (dragOffset / h).roundToInt()
                                    dragOffset = 0f
                                    if (shift != 0) {
                                        val list = currentMarkers
                                        val ids = list.map { it.itemId }.toMutableList()
                                        val from = ids.indexOf(m.itemId)
                                        if (from >= 0) {
                                            val target = (from + shift).coerceIn(0, ids.lastIndex)
                                            val moved = ids.removeAt(from)
                                            ids.add(target, moved)
                                            onReorder(ids)
                                        }
                                    }
                                },
                                onDragCancel = { dragOffset = 0f }
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                            }
                        }
                        .semantics {
                            contentDescription = handleLabel
                            customActions = listOf(
                                CustomAccessibilityAction(upLabel) { moveBy(-1); true },
                                CustomAccessibilityAction(downLabel) { moveBy(1); true }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Use the drag handle icon resource instead of a literal glyph
                    Icon(
                        painter = painterResource(R.drawable.ic_drag_handle),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(m.orderId.toString(), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        m.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // Use a localizable string instead of concatenating symbols like "x3 +100ms"
                        stringResource(
                            R.string.panel_marker_detail,
                            m.durationMs.toString(),
                            if (m.repeatCount == 0) {
                                stringResource(R.string.marker_repeat_infinite)
                            } else {
                                m.repeatCount.toString()
                            },
                            m.delayMs.toString()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Visible rename button instead of the hidden tap-the-name interaction
                TextButton(onClick = { onRename(m) }) {
                    Text(stringResource(R.string.action_rename), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { onDelete(m) }) {
                    // Content description so TalkBack can announce the delete action
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.panel_delete_marker))
                }
            }
            }
        }
    }
}

@Composable
private fun RenameModal(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val context = LocalContext.current
    Text(title, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row {
        TextButton(onClick = {
            val t = text.trim()
            // An empty name does nothing, which reads as a broken button
            if (t.isEmpty()) {
                Toast.makeText(context, R.string.panel_name_required, Toast.LENGTH_SHORT).show()
            } else {
                onConfirm(t)
            }
        }) {
            Text(stringResource(R.string.dialog_ok))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.dialog_cancel)) }
    }
}

@Composable
private fun QueuePickerModal(
    manager: OverlayManager,
    onPick: (Long) -> Unit,
    onCancel: () -> Unit
) {
    var queues by remember { mutableStateOf<List<ActionQueueEntity>>(emptyList()) }
    LaunchedEffect(Unit) { queues = manager.listQueuesForLoad() }
    val currentMarkers by OverlayState.markers.collectAsState()
    Text(stringResource(R.string.load_queue_title), style = MaterialTheme.typography.titleSmall)
    // Loading overwrites unsaved panel content, so warn first
    if (currentMarkers.isNotEmpty()) {
        Text(
            text = stringResource(R.string.panel_load_overwrites),
            style = MaterialTheme.typography.bodySmall,
            // This is a notice, not an error: error red is reserved for deletion/failures, tertiary signals "pay attention"
            color = MaterialTheme.colorScheme.tertiary
        )
    }
    if (queues.isEmpty()) {
        Text(stringResource(R.string.panel_empty_library), style = MaterialTheme.typography.bodySmall)
    } else {
        LazyColumn(Modifier.heightIn(max = 200.dp)) {
            items(queues, key = { it.id }) { q ->
                Text(
                    q.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 48dp minimum row height for the touch target
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable { onPick(q.id) }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.dialog_cancel)) }
}

@Composable
private fun ActionPickerModal(
    manager: OverlayManager,
    onPick: (Long) -> Unit,
    onCancel: () -> Unit
) {
    var actions by remember { mutableStateOf<List<ActionWithSteps>>(emptyList()) }
    LaunchedEffect(Unit) { actions = manager.listActionsForLoad() }
    Text(stringResource(R.string.load_action_title), style = MaterialTheme.typography.titleSmall)
    if (actions.isEmpty()) {
        Text(stringResource(R.string.panel_empty_library), style = MaterialTheme.typography.bodySmall)
    } else {
        LazyColumn(Modifier.heightIn(max = 200.dp)) {
            items(actions, key = { it.action.id }) { a ->
                Text(
                    "${a.action.name}${stringResource(R.string.item_separator)}${markerTypeLabel(a.action.type)}",
                    modifier = Modifier
                        .fillMaxWidth()
                        // 48dp minimum row height for the touch target
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable { onPick(a.action.id) }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.dialog_cancel)) }
}

@Composable
private fun markerTypeLabel(type: ActionType): String = when (type) {
    ActionType.SWIPE -> stringResource(R.string.panel_marker_swipe)
    else -> stringResource(R.string.panel_marker_click)
}

@Composable
private fun queueStateLabel(state: QueueRunStatus): String = when (state) {
    // READY only appears in data saved by older versions; show it with the idle label
    QueueRunStatus.IDLE, QueueRunStatus.READY -> stringResource(R.string.queue_state_idle)
    QueueRunStatus.RUNNING -> stringResource(R.string.queue_state_running)
    QueueRunStatus.PAUSED -> stringResource(R.string.queue_state_paused)
    QueueRunStatus.STOPPED -> stringResource(R.string.queue_state_stopped)
    QueueRunStatus.ERROR -> stringResource(R.string.queue_state_error)
}

// Solid triangle at the swipe end pointing along the trajectory. Callers pass halfWidth in dp (equal to the
// start circle radius); length defaults to 2*halfWidth and shrinks for short segments so the arrow stays on the line.
private fun DrawScope.drawArrowHead(
    color: Color,
    tip: Offset,
    direction: Offset,
    halfWidth: Float,
    length: Float = halfWidth * 2f
) {
    val len = direction.getDistance()
    // Skip when the direction vector is too short to normalize, avoiding a collapsed point
    if (len < 1e-3f) return
    val d = direction / len
    val perp = Offset(-d.y, d.x)
    val base = tip - d * length
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + perp.x * halfWidth, base.y + perp.y * halfWidth)
        lineTo(base.x - perp.x * halfWidth, base.y - perp.y * halfWidth)
        close()
    }
    drawPath(path, color = color)
}

// A swipe logically has only start/end points (intermediate DB points are injected at execution time);
// a click is a single point. Collapse editing to these two grabbable vertices so dragging can't bend the line.
private fun logicalPoints(marker: OverlayMarker, screenW: Float, screenH: Float, originY: Float): List<Offset> {
    val pts = marker.points.map { Offset(it.x * screenW, it.y * screenH - originY) }
    return if (marker.type == ActionType.SWIPE && pts.size >= 2) {
        listOf(pts.first(), pts.last())
    } else {
        pts
    }
}

private fun nearestVertex(points: List<Offset>, pos: Offset, radius: Float): Int {
    var best = -1
    var bestDist = radius
    points.forEachIndexed { i, p ->
        val d = (p - pos).getDistance()
        if (d <= bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lenSq = ab.x * ab.x + ab.y * ab.y
    if (lenSq == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lenSq).coerceIn(0f, 1f)
    return (p - Offset(a.x + ab.x * t, a.y + ab.y * t)).getDistance()
}

// Gesture state for the collapsed-handle drag. Deliberately plain mutable fields, not Compose state:
// high-frequency gesture updates must not trigger recomposition; raw coords are window-position independent.
private class PanelDragState {
    var activeId = -1
    var lastRawX = 0f
    var lastRawY = 0f
    var accumX = 0f
    var accumY = 0f
    var carryX = 0f
    var carryY = 0f
    var started = false
    var aborted = false
}
