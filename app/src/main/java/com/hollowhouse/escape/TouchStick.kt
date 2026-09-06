package com.hollowhouse.escape

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A left-thumb movement joystick. Drag anywhere inside this view to get a
 * normalized (dx, dy) in [-1, 1] via [dx]/[dy]. Releasing snaps back to zero.
 */
class TouchStick @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile var dx: Float = 0f
        private set
    @Volatile var dy: Float = 0f
        private set

    private var active = false
    private var baseX = 0f
    private var baseY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private val maxRadius = 130f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 216, 207, 184)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 216, 207, 184)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                baseX = event.x; baseY = event.y
                knobX = baseX; knobY = baseY
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                var vx = event.x - baseX
                var vy = event.y - baseY
                val len = hypot(vx, vy)
                val clamped = min(len, maxRadius)
                if (len > 0.001f) {
                    vx = vx / len * clamped
                    vy = vy / len * clamped
                }
                knobX = baseX + vx
                knobY = baseY + vy
                dx = vx / maxRadius
                dy = vy / maxRadius
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                dx = 0f; dy = 0f
            }
        }
        postInvalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        canvas.drawCircle(baseX, baseY, maxRadius, ringPaint)
        canvas.drawCircle(knobX, knobY, 55f, knobPaint)
    }
}
