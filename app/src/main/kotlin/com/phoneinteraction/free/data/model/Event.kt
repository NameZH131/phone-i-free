package com.phoneinteraction.free.data.model

// Unified carrier for GestureEvent and VoiceCommandEvent
// EventType covers only gestures and voice commands.
enum class EventType { GESTURE, VOICE_COMMAND }

data class Event(
    val eventType: EventType,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)
