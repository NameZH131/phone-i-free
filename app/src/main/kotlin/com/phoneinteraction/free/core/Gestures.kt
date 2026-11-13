package com.phoneinteraction.free.core

import androidx.annotation.StringRes
import com.phoneinteraction.free.R

// Gesture internal names are model outputs - users cannot type them manually (no text-entry path would ever match a model output).
// These 7 IDs come from the APK's built-in model assets/gesture_recognizer.task label table (verified by unpacking).
// None means "no gesture" - filtered by VisionPipeline, not listed here.
enum class Gesture(val id: String, @StringRes val labelRes: Int) {
    CLOSED_FIST("Closed_Fist", R.string.gesture_closed_fist),
    OPEN_PALM("Open_Palm", R.string.gesture_open_palm),
    POINTING_UP("Pointing_Up", R.string.gesture_pointing_up),
    THUMB_DOWN("Thumb_Down", R.string.gesture_thumb_down),
    THUMB_UP("Thumb_Up", R.string.gesture_thumb_up),
    VICTORY("Victory", R.string.gesture_victory),
    I_LOVE_YOU("ILoveYou", R.string.gesture_i_love_you);

    companion object {
        // Map recognized result to readable name. Return null for unknown category; UI falls back to showing raw id.
        fun fromId(id: String): Gesture? = values().firstOrNull { it.id == id }
    }
}
