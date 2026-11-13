package com.phoneinteraction.free.ui

import android.content.res.Resources
import com.phoneinteraction.free.R
import com.phoneinteraction.free.core.ExecutionFailure

// Core layer reports stable failure enum; localized text is mapped here,
// so users never see raw enum names or untranslated strings.
fun failureText(resources: Resources, failure: ExecutionFailure): String = resources.getString(
    when (failure) {
        // NONE means "no failure" - should not reach display layer. Defensively fall back to generic message
        // to avoid showing another "unknown" that overlaps semantically with "unexpected error".
        ExecutionFailure.NONE -> R.string.failure_unknown
        ExecutionFailure.TIMEOUT -> R.string.failure_timeout
        ExecutionFailure.ACTION_MISSING -> R.string.failure_action_missing
        ExecutionFailure.NOT_ACCEPTED -> R.string.failure_not_accepted
        ExecutionFailure.SWIPE_REQUIRES_STEPS -> R.string.failure_swipe_requires_steps
        ExecutionFailure.GLOBAL_MISSING -> R.string.failure_global_missing
        ExecutionFailure.GLOBAL_NOT_ACCEPTED -> R.string.failure_global_not_accepted
        ExecutionFailure.UNKNOWN -> R.string.failure_unknown
    }
)
