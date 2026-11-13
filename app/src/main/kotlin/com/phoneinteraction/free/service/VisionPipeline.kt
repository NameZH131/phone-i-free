package com.phoneinteraction.free.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.phoneinteraction.free.data.SettingsRepository
import java.util.concurrent.Executors
import kotlin.coroutines.resume

fun interface VisionResultListener {
    fun onGesture(gestureName: String, confidence: Float)
}

class VisionPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    // Enter sleep mode after 30s without a trigger; sleep mode throttles to about 1-2 fps.
    // Idle seconds before auto-sleep; user-configurable via applyIdleTimeoutSeconds (0 = never).
    private var idleTimeoutMs: Long = SettingsRepository.DEFAULT_VISION_IDLE_TIMEOUT_SECONDS * 1000L,
    private val sleepFrameIntervalMs: Long = 800L
) {

    private var recognizer: GestureRecognizer? = null
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var sleepMode = false

    @Volatile
    private var lastProcessAt = 0L

    // Gesture debounce state machine: single-frame results jitter, so a gesture must hold stable
    // briefly before firing; the state resets on release so the same gesture can fire again.
    @Volatile
    private var stableGesture: String? = null

    @Volatile
    private var stableSince = 0L

    @Volatile
    private var stableFrames = 0

    @Volatile
    private var releaseFrames = 0

    @Volatile
    private var lastFiredGesture: String? = null

    @Volatile
    private var frameTimestamp = 0L

    @Volatile
    private var listener: VisionResultListener? = null
    private var idleJob: Job? = null

    fun setListener(listener: VisionResultListener?) {
        this.listener = listener
    }

    private val startMutex = Mutex()

    suspend fun startCamera(lifecycleOwner: LifecycleOwner) {
        // Mutex serializes concurrent starts: the idempotence guard can be bypassed while
        // suspended inside awaitProvider, which would bind the camera twice.
        startMutex.withLock {
            if (cameraProvider != null) return
            val provider = awaitProvider()
            cameraProvider = provider
            // The 8MB model load must not block the main thread. If recognizer creation fails,
            // roll back cameraProvider or the idempotence guard would block every retry and
            // vision could never start again.
            val recognizer = try {
                withContext(Dispatchers.Default) { createRecognizer() }
            } catch (e: Exception) {
                cameraProvider = null
                throw e
            }
            this.recognizer = recognizer
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            this.analysis = analysis
            analysis.setAnalyzer(analyzerExecutor) { frame -> analyze(frame, recognizer) }
            try {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                // Release what was already created so a failed bind leaks nothing.
                recognizer.close()
                analysis.clearAnalyzer()
                this.recognizer = null
                this.analysis = null
                cameraProvider = null
                throw e
            }
            startIdleTimer()
            ServiceState.setVisionRunning(true)
        }
    }

    suspend fun stopCamera() = stopCameraInternal()

    // Synchronous stop for Service.onDestroy: a lifecycleScope coroutine could be cancelled
    // mid-stop, leaving the camera bound and the state flag stale.
    fun stopCameraNow() = stopCameraInternal()

    private fun stopCameraInternal() {
        ServiceState.setVisionRunning(false)
        // Clear the sleeping flag too, or the next start would show a stale "asleep" hint.
        ServiceState.setVisionSleeping(false)
        idleJob?.cancel()
        idleJob = null
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        recognizer?.close()
        recognizer = null
        resetGestureState()
        sleepMode = false
    }

    fun enterSleepMode() {
        sleepMode = true
    }

    fun wakeUp() {
        sleepMode = false
        // Reset the sleeping hint whenever the pipeline wakes up (voice keyword or manual).
        ServiceState.setVisionSleeping(false)
        resetGestureState()
        startIdleTimer()
    }

    fun isSleeping(): Boolean = sleepMode

    // Applies the user-configured idle timeout (seconds; 0 = never sleep). Safe to call with
    // no camera bound: the settings collector emits before startCamera and on every change.
    fun applyIdleTimeoutSeconds(seconds: Int) {
        idleTimeoutMs = seconds * 1000L
        if (seconds <= 0) {
            // "Never sleep": wake a sleeping pipeline, or cancel the pending timer so an
            // awake pipeline will not sleep at the old deadline. startIdleTimer cancels
            // first and no-ops for a disabled timeout.
            if (sleepMode) wakeUp() else startIdleTimer()
        } else if (cameraProvider != null && !sleepMode) {
            // Re-arm the pending timer with the new value. A sleeping pipeline stays asleep
            // until woken; the new timeout applies from the next wake.
            startIdleTimer()
        }
    }

    private fun createRecognizer(): GestureRecognizer {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            // Explicit thresholds (official defaults, one place to tune) and an error listener:
            // GPU fallback or model errors are otherwise swallowed and the UI just looks dead.
            .setMinHandDetectionConfidence(MIN_HAND_DETECTION_CONFIDENCE)
            .setMinHandPresenceConfidence(MIN_HAND_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(MIN_HAND_TRACKING_CONFIDENCE)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.e(TAG, "gesture recognizer error: ${e.message}") }
            .build()
        return GestureRecognizer.createFromOptions(context, options)
    }

    private fun analyze(frame: ImageProxy, recognizer: GestureRecognizer) {
        val now = SystemClock.uptimeMillis()
        val minInterval = if (sleepMode) sleepFrameIntervalMs else NORMAL_FRAME_INTERVAL_MS
        try {
            // Throttle frames: 1-2 fps in sleep, ~10 fps normally. Gesture recognition does not
            // need full frame rate, and fewer YUV-to-Bitmap conversions cut allocations and GC churn.
            if (now - lastProcessAt < minInterval) return
            lastProcessAt = now
            // toBitmap() does not apply rotationDegrees, so rotate manually: setRotationDegrees only
            // changes what the model sees; landmarks stay in the unrotated frame, skeleton goes sideways.
            val source = frame.toBitmap()
            val rotation = frame.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            } else {
                source
            }
            val mpImage = BitmapImageBuilder(upright).build()
            frameTimestamp += 1
            recognizer.recognizeAsync(mpImage, frameTimestamp)
        } catch (e: IllegalStateException) {
            // After stopCamera an in-flight frame makes recognizeAsync throw ISE; swallow it.
        } catch (e: Exception) {
            // toBitmap throws IAE/UOE on some devices/formats; if it escapes, the analyzer thread
            // dies and vision silently stops. Log and swallow.
            Log.e(TAG, "analyze frame failed", e)
        } finally {
            // The ImageProxy buffer must be returned (toBitmap copies, so closing is safe), or the
            // KEEP_ONLY_LATEST pipeline silently stalls after the first frame.
            frame.close()
        }
    }

    private fun onResult(result: GestureRecognizerResult) {
        // Report landmarks for skeleton visualization before the no-gesture early return, so the
        // skeleton still shows when a hand is visible but no gesture is recognized (null = no hand).
        val hand = result.landmarks().firstOrNull()
        if (hand != null && hand.size >= HAND_LANDMARK_COUNT) {
            ServiceState.setHandSkeleton(hand.map { it.x() to it.y() })
        } else {
            ServiceState.setHandSkeleton(null)
        }
        val category = result.gestures().firstOrNull()?.firstOrNull()
        val name = category?.categoryName()
        val score = category?.score() ?: 0f
        val now = SystemClock.uptimeMillis()
        // No hand, None category, or low confidence counts as no gesture: the release path resets
        // state so the gesture can fire again, and release itself needs consecutive frames (jitter).
        if (category == null || name == null || name == NONE_CATEGORY || score < MIN_GESTURE_SCORE) {
            releaseFrames += 1
            if (releaseFrames >= MIN_RELEASE_FRAMES) {
                stableGesture = null
                stableFrames = 0
                lastFiredGesture = null
            }
            return
        }
        releaseFrames = 0
        if (name != stableGesture) {
            // Gesture changed: restart the timer and observe at least one more frame
            stableGesture = name
            stableSince = now
            stableFrames = 1
            return
        }
        stableFrames += 1
        // Edge-triggered: fire once after holding stable long enough; continuous hold never re-fires.
        if (stableFrames >= MIN_STABLE_FRAMES &&
            now - stableSince >= MIN_HOLD_MS &&
            name != lastFiredGesture
        ) {
            lastFiredGesture = name
            listener?.onGesture(name, score)
            resetIdleTimer()
        }
    }

    // Clear all debounce state so stop/wake paths start clean.
    private fun resetGestureState() {
        stableGesture = null
        stableFrames = 0
        releaseFrames = 0
        lastFiredGesture = null
        // Clear skeleton data too so the home screen shows no stale overlay.
        ServiceState.setHandSkeleton(null)
    }

    private fun startIdleTimer() {
        idleJob?.cancel()
        // 0 (or less) disables auto-sleep entirely (user setting "never sleep").
        if (idleTimeoutMs <= 0L) {
            idleJob = null
            return
        }
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            if (!sleepMode) {
                sleepMode = true
                // Publish the sleeping state so the home screen shows the asleep hint.
                ServiceState.setVisionSleeping(true)
            }
        }
    }

    private fun resetIdleTimer() {
        startIdleTimer()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWith(Result.failure(e))
                }
            }, ContextCompat.getMainExecutor(context))
        }

    private companion object {
        const val MODEL_ASSET_PATH = "gesture_recognizer.task"
        const val NONE_CATEGORY = "None"
        const val TAG = "VisionPipeline"

        // MediaPipe's hand model always outputs 21 landmarks; fewer means an invalid result.
        const val HAND_LANDMARK_COUNT = 21

        // ~10 fps normally, 1-2 fps in sleep mode.
        const val NORMAL_FRAME_INTERVAL_MS = 100L
        // Model three-tier thresholds (official defaults, kept together for tuning)
        const val MIN_HAND_DETECTION_CONFIDENCE = 0.5f
        const val MIN_HAND_PRESENCE_CONFIDENCE = 0.5f
        const val MIN_HAND_TRACKING_CONFIDENCE = 0.5f
        // Below this confidence counts as no gesture, suppressing low-confidence jitter.
        const val MIN_GESTURE_SCORE = 0.6f
        // Stable only after 2 consecutive frames held >=250ms; release also needs 2 frames.
        const val MIN_STABLE_FRAMES = 2
        const val MIN_HOLD_MS = 250L
        const val MIN_RELEASE_FRAMES = 2
    }
}
