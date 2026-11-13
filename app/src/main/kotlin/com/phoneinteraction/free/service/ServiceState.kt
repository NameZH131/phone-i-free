package com.phoneinteraction.free.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Observable per-feature toggles so the home screen shows each feature's real on/off state.
object ServiceState {

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible
    fun setOverlayVisible(value: Boolean) {
        _overlayVisible.value = value
    }

    private val _visionRunning = MutableStateFlow(false)
    val visionRunning: StateFlow<Boolean> = _visionRunning
    fun setVisionRunning(value: Boolean) {
        _visionRunning.value = value
    }

    private val _voiceRunning = MutableStateFlow(false)
    val voiceRunning: StateFlow<Boolean> = _voiceRunning
    fun setVoiceRunning(value: Boolean) {
        _voiceRunning.value = value
    }

    // Vision detection auto-sleeps after the user-configured idle timeout (default 30s)
    // without a gesture; the UI must show this state
    // or users think the feature broke (the wake word is the only way to resume)
    private val _visionSleeping = MutableStateFlow(false)
    val visionSleeping: StateFlow<Boolean> = _visionSleeping
    fun setVisionSleeping(value: Boolean) {
        _visionSleeping.value = value
    }

    enum class RecognitionSource { GESTURE, VOICE }

    data class Recognition(val source: RecognitionSource, val value: String, val at: Long)

    // Feeds the home "last recognition" card so users can tell recognition is actually working.
    private val _lastRecognition = MutableStateFlow<Recognition?>(null)
    val lastRecognition: StateFlow<Recognition?> = _lastRecognition
    fun setLastRecognition(source: RecognitionSource, value: String) {
        val now = System.currentTimeMillis()
        _lastRecognition.value = Recognition(source, value, now)
        // Separate stream for voice hits so a later gesture hit cannot overwrite the voice-command
        // highlight on the home screen (the highlight lasts about 1.5s)
        if (source == RecognitionSource.VOICE) _lastVoiceHit.value = Recognition(source, value, now)
    }

    private val _lastVoiceHit = MutableStateFlow<Recognition?>(null)
    val lastVoiceHit: StateFlow<Recognition?> = _lastVoiceHit

    // Hand keypoints for the home-screen skeleton visualization, as normalized 0..1 coordinates of the
    // upright image; overwritten every frame, null when no hand is detected so the UI shows a placeholder
    data class HandSkeleton(val points: List<Pair<Float, Float>>, val at: Long)

    private val _handSkeleton = MutableStateFlow<HandSkeleton?>(null)
    val handSkeleton: StateFlow<HandSkeleton?> = _handSkeleton
    fun setHandSkeleton(points: List<Pair<Float, Float>>?) {
        _handSkeleton.value = points?.let { HandSkeleton(it, System.currentTimeMillis()) }
    }
}
