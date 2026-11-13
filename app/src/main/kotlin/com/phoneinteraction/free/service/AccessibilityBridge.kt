package com.phoneinteraction.free.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.ViewConfiguration
import com.phoneinteraction.free.data.model.GlobalAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object AccessibilityBridge {

    // System tap timeout (typically 100ms) instead of a custom 50ms: matches the official sample and
    // stays below the long-press threshold
    private val clickDurationMs: Long = ViewConfiguration.getTapTimeout().toLong()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected

    @Volatile
    private var service: AccessibilityService? = null

    // Injected gesture tracking for isOwnInjection: records the screen region of the last injected gesture
    // to avoid confusing our own automation as a real user tap during tap-to-stop detection.
    // Written from the queue-execution coroutine (Dispatchers.Default) and read on the main thread from
    // touch events, so these are volatile to guarantee cross-thread visibility.
    @Volatile
    private var lastInjectionX = 0f

    @Volatile
    private var lastInjectionY = 0f

    @Volatile
    private var lastInjectionTime = 0L
    private val injectionDebounceMs = 500L

    internal fun attach(s: AccessibilityService) {
        service = s
        _serviceConnected.value = true
    }

    internal fun detach() {
        service = null
        _serviceConnected.value = false
    }

    // Click press duration is editable (Action.duration) and defaults to the system tap timeout; overly
    // long durations degrade into a long press, so callers must cap the upper bound
    suspend fun dispatchClick(x: Float, y: Float, durationMs: Long = clickDurationMs): Boolean {
        recordInjection(x, y)
        return dispatch {
            val path = Path().apply { moveTo(x, y) }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L)))
                .build()
        }
    }

    suspend fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        recordInjection((startX + endX) / 2, (startY + endY) / 2)
        return dispatch {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        }
    }

    suspend fun dispatchLongPress(x: Float, y: Float, durationMs: Long): Boolean {
        recordInjection(x, y)
        return dispatch {
            val path = Path().apply { moveTo(x, y) }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        }
    }

    // Generic stroke dispatch: replaying recorded steps as rebuilt segments needs an arbitrary path + duration
    suspend fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        // Record injection at the path's starting point
        val points = path.approximate(0.5f)
        recordInjection(points[1], points[2])
        return dispatch {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        }
    }

    fun performGlobalAction(action: GlobalAction): Boolean {
        val s = service ?: return false
        // GlobalAction is a domain enum (data.model); mapping to platform constants is kept in this bridge
        val platformId = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        }
        return s.performGlobalAction(platformId)
    }

    // Called by dispatch methods to record injection location/time for isOwnInjection() debouncing
    private fun recordInjection(x: Float, y: Float) {
        lastInjectionX = x
        lastInjectionY = y
        lastInjectionTime = System.currentTimeMillis()
    }

    // Returns true if the given screen coordinates appear to be from our own injected gesture
    // rather than a real user touch (avoids tap-to-stop confusing automation as user input)
    fun isOwnInjection(x: Float, y: Float): Boolean {
        val elapsed = System.currentTimeMillis() - lastInjectionTime
        if (elapsed > injectionDebounceMs) return false
        // Check if the touch is near the last injected gesture location (within 50dp tolerance)
        val dx = x - lastInjectionX
        val dy = y - lastInjectionY
        val tolerancePx = 50 * android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 1f,
            android.content.res.Resources.getSystem().displayMetrics
        )
        return (dx * dx + dy * dy) <= (tolerancePx * tolerancePx)
    }

    private suspend fun dispatch(buildGesture: () -> GestureDescription): Boolean {
        val s = service ?: return false
        return suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            try {
                val accepted = s.dispatchGesture(buildGesture(), callback, null)
                // false means the system did not accept the gesture (e.g. service disconnected); end as failure
                if (!accepted && cont.isActive) cont.resume(false)
            } catch (e: RuntimeException) {
                // dispatchGesture throws RuntimeException on binder errors instead of returning false; catch it
                // and end as failure so the exception never propagates up the coroutine and crashes the app
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
