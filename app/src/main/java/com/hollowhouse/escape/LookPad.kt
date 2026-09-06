package com.hollowhouse.escape

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A transparent right-thumb drag area for camera look. Accumulates raw pixel
 * deltas since the last [consumeDelta] call, which the renderer polls once
 * per frame and turns into a yaw/pitch change.
 */
class LookPad @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private val lock = Any()
    private var pendingDX = 0f
    private var pendingDY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                val ddx = event.x - lastX
                val ddy = event.y - lastY
                lastX = event.x; lastY = event.y
                synchronized(lock) { pendingDX += ddx; pendingDY += ddy }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }

    fun consumeDelta(): FloatArray {
        synchronized(lock) {
            val out = floatArrayOf(pendingDX, pendingDY)
            pendingDX = 0f; pendingDY = 0f
            return out
        }
    }
}
