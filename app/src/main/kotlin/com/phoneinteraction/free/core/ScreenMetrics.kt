package com.phoneinteraction.free.core

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

// Different Context resources.displayMetrics may have inconsistent height - app window Context
// excludes system bars (Vivo/Android 14 reports 1080x2318), while floating window uses full
// physical display (1080x2400). Different normalization/denormalization base causes action
// coordinates to land ~3.4% higher (50+ pixels in mid-screen). Use physical display size
// to ensure dispatch and draw use the same base.
object ScreenMetrics {

    fun size(context: Context): Pair<Float, Float> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
    }
}
