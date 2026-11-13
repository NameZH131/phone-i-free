package com.phoneinteraction.free.data.model

enum class ActionType { CLICK, LONG_PRESS, SWIPE, GLOBAL }

enum class StepType { POINTER_DOWN, POINTER_MOVE, POINTER_UP }

// TriggerType covers only gestures and voice; no screen-gesture source exists.
enum class TriggerType { GESTURE, VOICE }

// Execution outcomes shared by single actions and queue items; Room converters persist enum names - renaming breaks decoding of stored records
enum class ExecutionResult { SUCCESS, FAILURE }

// READY only appears in data saved by older versions; reset writes IDLE.
// Deleting it would crash Room's valueOf on historical data. UI folds READY into IDLE display.
enum class QueueRunStatus { IDLE, READY, RUNNING, PAUSED, STOPPED, ERROR }

// Domain enum without platform constants; AccessibilityService constants mapped in bridge layer
enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS }
