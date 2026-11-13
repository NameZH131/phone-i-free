package com.phoneinteraction.free.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.room.withTransaction
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.phoneinteraction.free.AppServices
import com.phoneinteraction.free.R
import com.phoneinteraction.free.core.QueueScheduler
import com.phoneinteraction.free.data.ActionManager
import com.phoneinteraction.free.data.AppLanguage
import com.phoneinteraction.free.data.ThemeMode
import com.phoneinteraction.free.data.toLocaleList
import com.phoneinteraction.free.data.db.AppDatabase
import com.phoneinteraction.free.data.model.ActionQueueEntity
import com.phoneinteraction.free.data.model.ActionType
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.QueueItemEntity
import com.phoneinteraction.free.data.model.QueueRunStatus
import com.phoneinteraction.free.ui.theme.PifTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OverlayManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val actionManager: ActionManager,
    private val queueScheduler: QueueScheduler
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var markerView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var panelFlagsJob: Job? = null
    private var queueWatchJob: Job? = null
    private val showGuard = AtomicBoolean(false)
    // Target switching suspends on DB calls, so concurrent entry points could interleave; the mutex
    // ensures only one target takes effect at a time.
    private val targetMutex = Mutex()

    // Pending deletes commit here, not on the panel's Compose scope, so closing the overlay cannot
    // cancel them mid-flight. Survives service shutdown while the process lives (not process death).
    private val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingDeletes = java.util.concurrent.ConcurrentHashMap<Long, OverlayMarker>()

    // Persist the overlay queue id so renames and cold starts still find the same queue (not by name).
    private val overlayPrefs = context.getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
    // Collapsed round handle edge length (px): fixing the window size avoids per-frame measure while dragging.
    private var panelHandleSizePx = 0

    // Expanded panel width is fixed in the window LayoutParams: the overlay width constraint is
    // "screen width - x", which would keep narrowing the panel as it is dragged right (0.92x, max 400dp).
    private val panelWidthRatio = 0.92f
    private val panelMaxWidthDp = 400
    private var panelExpandedWidthPx = 0
    // Handle position before expanding: right-edge clamping rewrites x, so collapsing restores it.
    private var panelXBeforeExpand = 0
    private var panelYBeforeExpand = 0
    private var panelExpandedPrev = false

    // True while the running-state progress UI owns the panel; the flag watcher yields to explicit control.
    private var runningUiActive = false
    // Small progress capsule window shown while a queue runs.
    private var progressView: ComposeView? = null
    // 1x1dp tiny watch window - uses FLAG_WATCH_OUTSIDE_TOUCH to receive "user tapped screen" events
    // while still passing touches through to the underlying app (not consuming them, just observing)
    private var tapWatchView: android.view.View? = null

    // Tracks whether the user actively requested a stop via tapping the screen
    @Volatile
    private var userRequestedStop = false

    // Language the overlay windows are created with, resolved from the settings value directly.
    // Overlay windows outlive any Activity, so per-app locale switching never reaches them:
    // API 31/32 AppCompat wraps activity contexts only; API 33+ leaves existing WindowManager
    // compositions untouched. Every window must be built from a locale-wrapped context.
    private var overlayLanguage: AppLanguage = AppLanguage.SYSTEM

    // Context whose resources carry the chosen app locale; SYSTEM keeps the base system locale.
    private fun localeContext(): Context {
        if (overlayLanguage == AppLanguage.SYSTEM) return context
        val conf = Configuration(context.resources.configuration)
        conf.setLocales(
            android.os.LocaleList.forLanguageTags(overlayLanguage.toLocaleList().toLanguageTags())
        )
        return context.createConfigurationContext(conf)
    }

    fun isShowing(): Boolean = markerView != null

    suspend fun show(): Boolean {
        // Consume the pending edit target first: switch to it if already shown, else load it below.
        val wantAction = OverlayState.pendingEditActionId
        val wantQueue = OverlayState.pendingEditQueueId
        OverlayState.pendingEditActionId = -1L
        OverlayState.pendingEditQueueId = -1L
        if (markerView != null) {
            // A partial failure can leave windows up while state says off; correct it instead of returning true.
            ServiceState.setOverlayVisible(true)
            // Overlay already shown: switch to the requested edit target in place, otherwise the early
            // return above would swallow the request silently (no target = keep current).
            if (wantAction > 0L || wantQueue > 0L) {
                // Target switch suspends on DB calls; the mutex serializes concurrent entry points.
                targetMutex.withLock {
                    applyTarget(wantAction, wantQueue)
                    // applyTarget only updates OverlayState.mode; sync the real window flags here since a
                    // just-finished queue can leave stale passthrough flags with no UI toggle to fix them.
                    setDrawMode(true)
                }
            }
            return true
        }
        // Two DB suspensions separate the null check from assignment, so a fast double-tap can open two
        // windows; the atomic guard makes a concurrent second show() fail fast.
        if (!showGuard.compareAndSet(false, true)) return false
        try {
            // SYSTEM_ALERT_WINDOW pre-check: report failure so the UI can guide the user to grant it.
            if (!Settings.canDrawOverlays(context)) return false
            // Re-resolve at every show(): covers cold start (the service language collector skips
            // its first emission, so the field alone would miss the persisted setting).
            overlayLanguage = runCatching { AppServices.settings.language.first() }
                .getOrDefault(AppLanguage.SYSTEM)
            // Apply the pending edit target: a single action loads that action, an explicit queue id
            // binds that queue, and with neither fall back to the default overlay queue.
            applyTarget(wantAction, wantQueue)

            val owner = OverlayLifecycleOwner().also { it.onCreate() }
            lifecycleOwner = owner

            val markerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                markerFlags(draw = OverlayState.mode.value == OverlayMode.DRAW),
                PixelFormat.TRANSLUCENT
            ).apply {
                // Marker window must cover the full screen incl. status bar; insets would shrink it and
                // shift the draw canvas vs the displayMetrics-based replay baseline. Explicit size/origin
                // + fitInsetsTypes=0 keep both canvases identical.
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                width = context.resources.displayMetrics.widthPixels
                height = context.resources.displayMetrics.heightPixels
                setFitInsetsTypes(0)
            }
            val mv = ComposeView(localeContext())
            mv.setViewTreeLifecycleOwner(owner)
            mv.setViewTreeSavedStateRegistryOwner(owner)
            mv.setContent {
                val themeMode by AppServices.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
                PifTheme(themeMode = themeMode) { MarkerOverlayContent(manager = this) }
            }
            windowManager.addView(mv, markerParams)
            markerView = mv

            val density = context.resources.displayMetrics.density
            // Collapsed state is a 56dp round handle; fixed window size avoids per-frame measure on drag.
            panelHandleSizePx = (56 * density).toInt()
            // Fixed pixel width from the screen ratio (see field comment), written into LayoutParams when expanded.
            panelExpandedWidthPx = (context.resources.displayMetrics.widthPixels * panelWidthRatio).toInt()
                .coerceAtMost((panelMaxWidthDp * density).toInt())
            val panelParams = WindowManager.LayoutParams(
                panelHandleSizePx,
                panelHandleSizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                panelFlags(expanded = false),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = context.resources.displayMetrics.widthPixels - panelHandleSizePx - (16 * density).toInt()
                y = (200 * density).toInt()
            }
            val pv = ComposeView(localeContext())
            pv.setViewTreeLifecycleOwner(owner)
            pv.setViewTreeSavedStateRegistryOwner(owner)
            pv.setContent {
                val themeMode by AppServices.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
                PifTheme(themeMode = themeMode) { PanelContent(manager = this) }
            }
            windowManager.addView(pv, panelParams)
            panelView = pv
            // Watch panel size changes so growth from content/Snackbar is clamped back on screen.
            pv.viewTreeObserver.addOnGlobalLayoutListener(panelLayoutListener)
            // Reset across show/hide: stale expanded state would make the first collect(false) restore old positions.
            panelExpandedPrev = false
            panelXBeforeExpand = 0
            panelYBeforeExpand = 0

            startPanelFlagWatch()
            startQueueWatch()
            owner.onResume()
            ServiceState.setOverlayVisible(true)
            // A paw handle suddenly appearing on screen needs context: explain once on first show.
            if (!overlayPrefs.getBoolean("handle_tip_shown", false)) {
                overlayPrefs.edit().putBoolean("handle_tip_shown", true).apply()
                Toast.makeText(localeContext(), R.string.overlay_handle_tip, Toast.LENGTH_LONG).show()
            }
            return true
        } catch (e: Exception) {
            // Roll back if adding windows fails midway: otherwise markerView is set while state is off
            // and half-built windows leak.
            hide()
            throw e
        } finally {
            showGuard.set(false)
        }
    }

    // Sync window flags on expand/collapse: expanded clears FLAG_NOT_FOCUSABLE so text inputs can
    // take focus and open the keyboard. Also sync size: collapsed fixed 56dp handle, expanded WRAP_CONTENT.
    private fun startPanelFlagWatch() {
        panelFlagsJob = scope.launch {
            OverlayState.panelExpanded.collect { expanded ->
                // While the running UI is active the panel is hidden; enterRunningUi owns size/flags.
                if (runningUiActive) return@collect
                applyPanelState(expanded)
            }
        }
    }

    // Split out of the collect callback so running-state exit can call it directly (no re-emission needed).
    private fun applyPanelState(expanded: Boolean) {
        val v = panelView ?: return
        // panelExpanded is the single source of truth for window size/flags and Compose content. This
        // function also runs off the collect path (running-state exit); writing the state back keeps
        // window and content consistent (no loop: equal values do not re-emit).
        if (OverlayState.panelExpanded.value != expanded) OverlayState.setPanelExpanded(expanded)
        v.visibility = android.view.View.VISIBLE
        val p = v.layoutParams as WindowManager.LayoutParams
        val newFlags = panelFlags(expanded)
        // Expanded: fixed pixel width + adaptive height; collapsed: 56dp handle. Pinning width to the
        // window keeps the "screen width - x" constraint from narrowing it while dragging.
        val newWidth = if (expanded) panelExpandedWidthPx else panelHandleSizePx
        val newHeight = if (expanded) WindowManager.LayoutParams.WRAP_CONTENT else panelHandleSizePx
        // Record the handle position before expanding; collapse restores it after edge clamping moved it.
        if (expanded && !panelExpandedPrev) {
            panelXBeforeExpand = p.x
            panelYBeforeExpand = p.y
        }
        val restore = !expanded && panelExpandedPrev
        val targetX = if (restore) panelXBeforeExpand else p.x
        val targetY = if (restore) panelYBeforeExpand else p.y
        if (p.flags != newFlags || p.width != newWidth || p.height != newHeight ||
            p.x != targetX || p.y != targetY
        ) {
            p.flags = newFlags
            p.width = newWidth
            p.height = newHeight
            p.x = targetX
            p.y = targetY
            runCatching { windowManager.updateViewLayout(v, p) }
        }
        panelExpandedPrev = expanded
        // After expanding, the wider panel can stick out past the right edge; clamp after layout,
        // when v.width/v.height finally reflect the real size.
        v.post { clampPanelInto(v) }
    }

    // Clamp the panel on screen using its current real size; the upper bound also leaves room for
    // the navigation bar so the panel/undo bar cannot hide under the gesture area.
    private fun navBarHeightPx(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun clampPanelInto(v: android.view.View) {
        if (v.width <= 0 || v.height <= 0) return
        val p = v.layoutParams as WindowManager.LayoutParams
        val dm = context.resources.displayMetrics
        val nx = p.x.coerceIn(0, (dm.widthPixels - v.width).coerceAtLeast(0))
        val ny = p.y.coerceIn(0, (dm.heightPixels - navBarHeightPx() - v.height).coerceAtLeast(0))
        if (nx != p.x || ny != p.y) {
            p.x = nx
            p.y = ny
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    // Tracks that passthrough was switched on automatically for a queue run, so it is only restored
    // after an automatic switch and never overrides the user's manual mode choice.
    private var autoPassthrough = false

    // Re-clamp when the panel grows (Snackbar/content) so the undo button cannot fall off screen.
    private val panelLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        panelView?.let { v -> v.post { clampPanelInto(v) } }
    }

    // Force passthrough while a queue runs: the marker layer would otherwise recapture replayed
    // gestures and persist dragged SWIPE markers onto themselves.
    private fun startQueueWatch() {
        queueWatchJob = scope.launch {
            queueScheduler.state.collect { s ->
                val active = s == QueueRunStatus.RUNNING || s == QueueRunStatus.PAUSED
                if (active) {
                    // Passthrough: replayed gestures must not be recaptured by the marker layer.
                    if (OverlayState.mode.value == OverlayMode.DRAW) setDrawMode(false)
                    autoPassthrough = true
                    // Collapse into a progress capsule: it ignores injected gestures and a tap stops the run.
                    if (!runningUiActive) enterRunningUi()
                } else if (runningUiActive) {
                    // Restarting from PAUSED passes through IDLE momentarily; treating that as finished
                    // flashes the handle with a wrong toast. Re-check the latest value before reacting.
                    val latest = queueScheduler.state.value
                    if (latest == QueueRunStatus.RUNNING || latest == QueueRunStatus.PAUSED) return@collect
                    // Restore panel: expand if user tapped screen to stop, otherwise restore pre-run shape
                    exitRunningUi(expandPanel = userRequestedStop || panelExpandedBeforeRun)
                    userRequestedStop = false
                    autoPassthrough = false
                    // The end-of-run message must match the final status: an errored run must not be
                    // reported as a normal finish.
                    val finishedMsg = when (latest) {
                        QueueRunStatus.ERROR -> R.string.panel_queue_error
                        QueueRunStatus.STOPPED -> R.string.panel_queue_stopped
                        else -> R.string.panel_queue_finished
                    }
                    Toast.makeText(context, finishedMsg, Toast.LENGTH_SHORT).show()
                } else if (autoPassthrough) {
                    // Queue end shows a toast and leaves the mode to the user instead of forcing the edit page.
                    autoPassthrough = false
                    Toast.makeText(context, R.string.panel_queue_finished, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Panel shape (expanded vs handle) before entering the running state, restored after the run so
    // an expanded panel is not unexpectedly collapsed when the run finishes.
    private var panelExpandedBeforeRun = false

    // ===== Running-state window (progress capsule, tap to stop) =====

    private fun enterRunningUi() {
        runningUiActive = true
        // Remember the pre-run shape to restore it after the run.
        panelExpandedBeforeRun = OverlayState.panelExpanded.value
        // Hide the panel entirely and make it untouchable - key to the overlay not swallowing injected actions
        panelView?.let { v ->
            v.visibility = android.view.View.GONE
            val p = v.layoutParams as WindowManager.LayoutParams
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(v, p) }
        }
        showProgressWindow()
        showTapWatchWindow()
    }

    private fun exitRunningUi(expandPanel: Boolean) {
        runningUiActive = false
        progressView?.let { runCatching { windowManager.removeView(it) } }
        progressView = null
        tapWatchView?.let { runCatching { windowManager.removeView(it) } }
        tapWatchView = null
        // Restore the handle/panel explicitly; it was hidden for the whole run.
        applyPanelState(expandPanel)
        // In expanded state the position may end up at an edge; post one frame, then clamp (applyPanelState clamps)
    }

    private fun showProgressWindow() {
        if (progressView != null) return
        val density = context.resources.displayMetrics.density
        val owner = lifecycleOwner ?: return
        val params = WindowManager.LayoutParams(
            (240 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // The capsule must stay non-touchable: a touchable window at top center would swallow
            // injected gestures crossing that strip and could even self-trigger a stop. Stopping is
            // handled by the tap-watch window (tap anywhere) and the notification button.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // A fixed 16dp offset covers the status bar clock/icons on notch devices; use status bar
            // height + 8dp, falling back to 24dp when the system dimension is unavailable.
            val statusBarId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarPx = if (statusBarId > 0) context.resources.getDimensionPixelSize(statusBarId) else (24 * density).toInt()
            y = statusBarPx + (8 * density).toInt()
            alpha = 0.8f
        }
        val pv = ComposeView(localeContext())
        pv.setViewTreeLifecycleOwner(owner)
        pv.setViewTreeSavedStateRegistryOwner(owner)
        pv.setContent {
            val themeMode by AppServices.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            PifTheme(themeMode = themeMode) {
                // Capsule is read-only progress; stopping is via tap-anywhere or the notification.
                RunningProgressContent()
            }
        }
        runCatching {
            windowManager.addView(pv, params)
            progressView = pv
        }
    }

    private fun showTapWatchWindow() {
        if (tapWatchView != null) return
        val v = object : android.view.View(context) {
            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                // FLAG_WATCH_OUTSIDE_TOUCH only delivers ACTION_OUTSIDE for the first press,
                // and the touch still passes through to the underlying app (not consumed) - "see but don't block"
                if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                    onUserTouchedScreen(event.rawX, event.rawY)
                    return true
                }
                return super.onTouchEvent(event)
            }
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0.01f
        }
        runCatching {
            windowManager.addView(v, params)
            tapWatchView = v
        }
    }

    private fun onUserTouchedScreen(x: Float, y: Float) {
        // Skip if this touch came from our own injected gesture (avoid stopping automation when we tap ourselves)
        if (AccessibilityBridge.isOwnInjection(x, y)) return
        userRequestedStop = true
        scope.launch { queueScheduler.stopQueue() }
    }

    fun hide() {
        // Commit pending deletes now: closing the overlay tears down the panel composition and would
        // cancel their undo coroutines, leaving to-be-deleted actions stuck in the queue.
        flushPendingDeletes()
        ServiceState.setOverlayVisible(false)
        panelFlagsJob?.cancel()
        panelFlagsJob = null
        queueWatchJob?.cancel()
        queueWatchJob = null
        // Tear down the running-state window too, so no orphan progress capsule is left.
        runningUiActive = false
        userRequestedStop = false
        progressView?.let { runCatching { windowManager.removeView(it) } }
        progressView = null
        tapWatchView?.let { runCatching { windowManager.removeView(it) } }
        tapWatchView = null
        // Unregister the layout listener so the removed view is not leaked.
        panelView?.viewTreeObserver?.removeOnGlobalLayoutListener(panelLayoutListener)
        markerView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        markerView = null
        panelView = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        OverlayState.setPanelExpanded(false)
        OverlayState.select(null)
        // Clear the pre-run shape so a stale value cannot leak into the next session.
        panelExpandedBeforeRun = false
        // Reset autoPassthrough too: after hiding mid-run and reopening, the initial IDLE emission
        // would hit the else-if branch and toast a spurious "queue finished".
        autoPassthrough = false
        // Clear the single-action state so the next plain open does not inherit the last edit target.
        OverlayState.exitEditTarget()
    }

    fun setDrawMode(draw: Boolean) {
        OverlayState.setMode(if (draw) OverlayMode.DRAW else OverlayMode.PASSTHROUGH)
        markerView?.let { v ->
            val p = v.layoutParams as WindowManager.LayoutParams
            p.flags = markerFlags(draw)
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    // Coalesce move events into one updateViewLayout per frame; per-event Binder IPC caused drag jank.
    private var pendingDx = 0
    private var pendingDy = 0
    private var frameScheduled = false

    fun movePanelBy(dx: Int, dy: Int) {
        pendingDx += dx
        pendingDy += dy
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback {
            frameScheduled = false
            val dx = pendingDx
            val dy = pendingDy
            // Always reset: if panelView is gone mid-drag (hidden), leftover deltas would pollute the next drag.
            pendingDx = 0
            pendingDy = 0
            if (dx == 0 && dy == 0) return@postFrameCallback
            panelView?.let { v ->
                val p = v.layoutParams as WindowManager.LayoutParams
                val dm = context.resources.displayMetrics
                // Bounds subtract the view's own size so the panel cannot be dragged off screen. Prefer
                // the real window size (the expanded panel is far larger than the handle); fall back to
                // the fixed 56dp handle edge when measure results are not in yet.
                val sizeW = if (v.width > 0) v.width else panelHandleSizePx
                val sizeH = if (v.height > 0) v.height else panelHandleSizePx
                val maxX = (dm.widthPixels - sizeW).coerceAtLeast(0)
                // Upper bound leaves room for the navigation bar so the panel bottom/undo bar is not covered.
                val maxY = (dm.heightPixels - navBarHeightPx() - sizeH).coerceAtLeast(0)
                p.x = (p.x + dx).coerceIn(0, maxX)
                p.y = (p.y + dy).coerceIn(0, maxY)
                runCatching { windowManager.updateViewLayout(v, p) }
            }
        }
    }

    // Draw mode keeps the overlay touchable for capture/drag; passthrough adds NOT_TOUCHABLE so
    // markers stay visible without intercepting input. FLAG_LAYOUT_IN_SCREEN makes the window cover
    // the whole screen incl. the status bar, aligning the draw canvas with the replay coordinate baseline.
    private fun markerFlags(draw: Boolean): Int =
        if (draw) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

    // Collapsed handle stays NOT_FOCUSABLE; expanded clears it so inputs can take focus (keyboard).
    private fun panelFlags(expanded: Boolean): Int =
        if (expanded) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    // ===== Overlay queue/marker persistence =====

    // Apply the edit target: single action, explicit queue, or fall back to the default overlay queue.
    private suspend fun applyTarget(wantAction: Long, wantQueue: Long) {
        when {
            wantAction > 0L -> OverlayState.refreshAction(db, wantAction)
            wantQueue > 0L -> {
                OverlayState.overlayQueueId = wantQueue
                OverlayState.refresh(db, OverlayState.overlayQueueId)
                // Queue editing starts in draw mode: a stale passthrough mode would make the new window
                // NOT_TOUCHABLE and the canvas undraggable (still switchable from the panel).
                OverlayState.setMode(OverlayMode.DRAW)
            }
            else -> {
                OverlayState.overlayQueueId = ensureQueue()
                OverlayState.refresh(db, OverlayState.overlayQueueId)
            }
        }
    }

    private suspend fun ensureQueue(): Long {
        // Reuse the persisted queue id first so renaming the overlay queue keeps pointing at it.
        val savedId = overlayPrefs.getLong("overlay_queue_id", -1L)
        if (savedId > 0 && db.queueDao().getQueueById(savedId) != null) return savedId
        // Find an existing overlay queue by name, including one created by an older version
        // (locale context: the auto-created name must match the in-app language).
        val name = localeContext().getString(R.string.overlay_queue_name)
        val existing = db.queueDao().getAllQueuesWithItems().firstOrNull { it.queue.name == name }
        val id = existing?.queue?.id ?: db.queueDao().insertQueue(
            ActionQueueEntity(name = name, createdAt = System.currentTimeMillis())
        )
        overlayPrefs.edit().putLong("overlay_queue_id", id).apply()
        return id
    }

    // Drawn markers are enqueued immediately; their panel order is the execution order.
    suspend fun addDrawnMarker(points: List<Offset>, durationMs: Long) {
        if (points.isEmpty()) return
        // Single-action mode forbids adding actions; defensive guard (drawing is disabled in the UI too).
        if (OverlayState.targetActionId.value >= 0L) return
        // The overlay queue may have been deleted externally (id reset to -1); rebuild it before
        // inserting or the queue_item foreign key fails and drawing crashes silently.
        if (OverlayState.overlayQueueId <= 0 ||
            db.queueDao().getQueueById(OverlayState.overlayQueueId) == null
        ) {
            OverlayState.overlayQueueId = ensureQueue()
        }
        val type = if (points.size > 1) ActionType.SWIPE else ActionType.CLICK
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val name = localeContext().getString(R.string.overlay_action_name, stamp)
        val provisional = OverlayMarker(
            itemId = -1L,
            // id must be 0 so Room auto-generates it; a non-zero id is treated as an explicit key.
            actionId = 0L,
            orderId = 0,
            type = type,
            points = points,
            durationMs = durationMs,
            repeatCount = 1
        )
        val (action, steps) = OverlayState.toActionEntities(provisional, name)
        val actionId = actionManager.addAction(action, steps)
        // max(order)+1 avoids duplicate orders after deletions.
        val order = (db.queueDao().maxOrderForQueue(OverlayState.overlayQueueId) ?: -1) + 1
        db.queueDao().insertItem(
            QueueItemEntity(
                queueId = OverlayState.overlayQueueId,
                actionId = actionId,
                order = order,
                enabled = true
            )
        )
        OverlayState.refresh(db, OverlayState.overlayQueueId)
    }

    // Drag handling: CLICK updates normalizedX/Y; SWIPE translates the whole polyline and rebuilds steps.
    suspend fun persistMarkerPoints(marker: OverlayMarker, newPoints: List<Offset>) {
        if (newPoints.isEmpty()) return
        val withSteps = db.actionDao().getByIdWithSteps(marker.actionId) ?: return
        if (marker.type == ActionType.CLICK) {
            db.actionDao().update(
                withSteps.action.copy(
                    normalizedX = newPoints.first().x,
                    normalizedY = newPoints.first().y
                )
            )
        } else {
            val rebuilt = OverlayState.toActionEntities(
                marker.copy(points = newPoints),
                withSteps.action.name
            )
            // Update + step replace must be atomic; reuse ActionManager.updateActionWithSteps.
            actionManager.updateActionWithSteps(rebuilt.first, rebuilt.second)
        }
        OverlayState.refreshCurrent(db)
    }

    suspend fun persistMarkerConfig(marker: OverlayMarker, durationMs: Long, repeatCount: Int, delayMs: Long): Boolean {
        val withSteps = db.actionDao().getByIdWithSteps(marker.actionId) ?: return false
        val duration = durationMs.coerceIn(1, 60_000)
        // Delay = wait before this action runs (Action.delay); 0 is valid.
        val delay = delayMs.coerceIn(0, 60_000)
        // Action-level repeat 0 = infinite and overrides the queue loop; upper bound guards huge values.
        val repeat = repeatCount.coerceIn(0, 999)
        if (marker.type == ActionType.SWIPE && withSteps.steps.isNotEmpty()) {
            // SWIPE duration derives from step timestamps, so changing duration must rebuild the step
            // timeline or the change silently has no effect on playback.
            val rebuilt = OverlayState.toActionEntities(
                marker.copy(durationMs = duration, delayMs = delay, repeatCount = repeat),
                withSteps.action.name
            )
            // Update + step replace must be atomic; reuse ActionManager.updateActionWithSteps.
            actionManager.updateActionWithSteps(rebuilt.first, rebuilt.second)
        } else {
            db.actionDao().update(
                withSteps.action.copy(duration = duration, delay = delay, repeatCount = repeat)
            )
        }
        OverlayState.refreshCurrent(db)
        return true
    }

    // Renaming a marker renames the action row itself (shared by the panel and the action library).
    suspend fun renameMarker(marker: OverlayMarker, newName: String) {
        val withSteps = db.actionDao().getByIdWithSteps(marker.actionId) ?: return
        db.actionDao().update(withSteps.action.copy(name = newName))
        OverlayState.refreshCurrent(db)
    }

    // Renaming a queue updates the queue row (shared by the panel and the queues page).
    suspend fun renameQueue(queueId: Long, newName: String) {
        val queue = db.queueDao().getQueueById(queueId) ?: return
        db.queueDao().updateQueue(queue.copy(name = newName))
        OverlayState.refresh(db, OverlayState.overlayQueueId)
    }

    // Drag-to-reorder persists the new itemId order for the currently loaded queue.
    suspend fun reorderMarkers(orderedItemIds: List<Long>) {
        queueScheduler.reorderItems(OverlayState.overlayQueueId, orderedItemIds)
        OverlayState.refresh(db, OverlayState.overlayQueueId)
    }

    // Save-as deep-copies actions and steps so later edits cannot pollute the source queue.
    suspend fun saveOverlayQueueAs(name: String): Long {
        val srcId = OverlayState.overlayQueueId
        val srcQueue = db.queueDao().getQueueById(srcId)
        val srcItems = db.queueDao().getItemsForQueue(srcId).sortedBy { it.order }
        val newQueueId = db.queueDao().insertQueue(
            ActionQueueEntity(
                name = name,
                createdAt = System.currentTimeMillis(),
                loopCount = srcQueue?.loopCount ?: 1,
                startDelay = srcQueue?.startDelay ?: 0L
            )
        )
        srcItems.forEachIndexed { index, item ->
            val aws = db.actionDao().getByIdWithSteps(item.actionId) ?: return@forEachIndexed
            val newActionId = actionManager.addAction(
                aws.action.copy(id = 0),
                aws.steps.map { it.copy(stepId = 0, actionId = 0) }
            )
            db.queueDao().insertItem(
                QueueItemEntity(
                    queueId = newQueueId,
                    actionId = newActionId,
                    order = index,
                    enabled = item.enabled
                )
            )
        }
        return newQueueId
    }

    // Loading deep-copies the source queue into the overlay queue, so later edits only touch the
    // copy and the saved source stays intact. Returns false when the target queue is currently
    // running: its items must not be deleted or rewritten mid-run.
    suspend fun loadQueue(queueId: Long): Boolean {
        if (OverlayState.overlayQueueId <= 0 ||
            db.queueDao().getQueueById(OverlayState.overlayQueueId) == null
        ) {
            OverlayState.overlayQueueId = ensureQueue()
        }
        val targetId = OverlayState.overlayQueueId
        // The run loop re-reads items by id each step; deleting the running queue's items mid-run breaks it.
        val running = queueScheduler.getQueueState()
        if ((running == QueueRunStatus.RUNNING || running == QueueRunStatus.PAUSED) &&
            queueScheduler.currentRunningQueueId() == targetId
        ) {
            return false
        }
        if (queueId == targetId) {
            OverlayState.select(null)
            OverlayState.refresh(db, targetId)
            return true
        }
        val srcQueue = db.queueDao().getQueueById(queueId) ?: return false
        val srcItems = db.queueDao().getItemsForQueue(queueId).sortedBy { it.order }
        db.withTransaction {
            // Clear the current overlay queue content; actions are deleted only when no other queue references them
            val old = db.queueDao().getItemsForQueue(targetId)
            old.forEach { db.queueDao().deleteItem(it.itemId) }
            old.forEach {
                if (db.queueDao().countItemsByActionId(it.actionId) == 0) {
                    // Delete via ActionManager so rule references are cleaned too; a raw dao delete
                    // leaves dangling rules that fire with no action attached.
                    actionManager.deleteAction(it.actionId)
                }
            }
            // Sync queue-level settings (loop count / pre-execution delay) and rename the copy to "source name (copy)" so users see where it came from
            db.queueDao().getQueueById(targetId)?.let { q ->
                db.queueDao().updateQueue(
                    q.copy(
                        name = localeContext().getString(R.string.panel_queue_copy_name, srcQueue.name),
                        loopCount = srcQueue.loopCount,
                        startDelay = srcQueue.startDelay
                    )
                )
            }
            // Deep-copy the actions and steps of the source queue (ids set to 0 to go through autoincrement)
            srcItems.forEachIndexed { index, item ->
                val aws = db.actionDao().getByIdWithSteps(item.actionId) ?: return@forEachIndexed
                val newActionId = actionManager.addAction(
                    aws.action.copy(id = 0),
                    aws.steps.map { it.copy(stepId = 0, actionId = 0) }
                )
                db.queueDao().insertItem(
                    QueueItemEntity(
                        queueId = targetId,
                        actionId = newActionId,
                        order = index,
                        enabled = item.enabled
                    )
                )
            }
        }
        OverlayState.select(null)
        OverlayState.refresh(db, targetId)
        return true
    }

    suspend fun addActionToOverlay(actionId: Long) {
        // Single-action edit mode never appends to a queue; defensive guard (UI hides the entry too).
        if (OverlayState.targetActionId.value >= 0L) return
        // As in addDrawnMarker, ensure the target queue exists before inserting.
        if (OverlayState.overlayQueueId <= 0 ||
            db.queueDao().getQueueById(OverlayState.overlayQueueId) == null
        ) {
            OverlayState.overlayQueueId = ensureQueue()
        }
        // max(order)+1 avoids duplicate orders after deletions.
        val order = (db.queueDao().maxOrderForQueue(OverlayState.overlayQueueId) ?: -1) + 1
        db.queueDao().insertItem(
            QueueItemEntity(
                queueId = OverlayState.overlayQueueId,
                actionId = actionId,
                order = order,
                enabled = true
            )
        )
        OverlayState.refresh(db, OverlayState.overlayQueueId)
    }

    // Passthrough-page queue settings: loop count (0 = infinite) and pre-run delay.
    suspend fun updateOverlayQueueSettings(loopCount: Int, startDelayMs: Long) {
        val queue = db.queueDao().getQueueById(OverlayState.overlayQueueId) ?: return
        db.queueDao().updateQueue(
            queue.copy(
                loopCount = loopCount.coerceIn(0, 999),
                startDelay = startDelayMs.coerceIn(0, 3_600_000)
            )
        )
        OverlayState.refresh(db, OverlayState.overlayQueueId)
    }

    suspend fun listQueuesForLoad(): List<ActionQueueEntity> =
        db.queueDao().getAllQueuesWithItems().map { it.queue }

    suspend fun listActionsForLoad(): List<ActionWithSteps> = actionManager.getAllActions()

    // Register a delete as pending-undo: hide the marker now (markPendingDelete); the panel snackbar
    // decides undo vs commit, and hide() commits any leftovers.
    fun scheduleMarkerDelete(marker: OverlayMarker) {
        if (pendingDeletes.putIfAbsent(marker.itemId, marker) != null) return
        OverlayState.markPendingDelete(marker.itemId)
    }

    // User tapped undo: drop the pending delete and restore the marker without touching the DB.
    fun undoScheduledDelete(itemId: Long) {
        if (pendingDeletes.remove(itemId) != null) OverlayState.unmarkPendingDelete(itemId)
    }

    // Commit one pending delete (snackbar timeout or overlay close); runs on commitScope, off the composition.
    fun commitScheduledDelete(itemId: Long) {
        commitScope.launch { commitMarkerDelete(itemId) }
    }

    // Atomically take the pending delete (no double commit); catch/finally keep a failed commit from
    // crashing and always restore the hidden marker.
    private suspend fun commitMarkerDelete(itemId: Long) {
        val marker = pendingDeletes.remove(itemId) ?: return
        try {
            // Bypass the single-action guard: this delete was already confirmed from the queue and must
            // not be silently dropped even if the user has since switched to single-action edit.
            performMarkerDelete(marker)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // CancellationException must be rethrown, not swallowed as a failure.
            throw c
        } catch (t: Throwable) {
            // commitScope uses a SupervisorJob: an escaping exception crashes the app, and a throwing
            // loop would abandon the remaining deletes. Log and keep going instead.
            Log.e("OverlayManager", "commit delete failed, itemId=$itemId", t)
        } finally {
            OverlayState.unmarkPendingDelete(itemId)
        }
    }

    // On overlay close, commit pending deletes sequentially in one coroutine so composition teardown
    // cannot lose them and interleaved commits cannot read stale queue state mid-refresh.
    private fun flushPendingDeletes() {
        if (pendingDeletes.isEmpty()) return
        commitScope.launch {
            pendingDeletes.keys.toList().forEach { commitMarkerDelete(it) }
        }
    }

    // Actual DB delete without mode guards; only called for confirmed pending-delete commits.
    private suspend fun performMarkerDelete(marker: OverlayMarker) {
        db.withTransaction {
            // Delete by this marker's itemId so the same action added twice is not wiped together.
            db.queueDao().deleteItem(marker.itemId)
            if (db.queueDao().countItemsByActionId(marker.actionId) == 0) {
                // ActionManager.deleteAction also cleans rule references, avoiding dangling rules.
                actionManager.deleteAction(marker.actionId)
            }
        }
        OverlayState.select(null)
        // refreshCurrent, not refresh(overlayQueueId): refresh resets targetActionId to -1 and would
        // yank the panel back to the old queue if the user switched to single-action edit meanwhile.
        OverlayState.refreshCurrent(db)
    }

    // Clear = remove all queue items first, then delete actions left orphaned.
    suspend fun clearMarkers() {
        // Single-action edit mode never clears the queue; defensive guard (UI hides the entry too).
        if (OverlayState.targetActionId.value >= 0L) return
        val queueId = OverlayState.overlayQueueId
        db.withTransaction {
            val items = db.queueDao().getItemsForQueue(queueId)
            items.forEach { db.queueDao().deleteItem(it.itemId) }
            items.forEach {
                if (db.queueDao().countItemsByActionId(it.actionId) == 0) {
                    // Same as above: delete via ActionManager to avoid dangling rules.
                    actionManager.deleteAction(it.actionId)
                }
            }
        }
        OverlayState.refresh(db, queueId)
    }
}

// An overlay ComposeView needs Lifecycle/SavedStateRegistry owners; the service lifecycle never
// reaches RESUMED, so a dedicated owner drives the composition.
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
