package com.hollowhouse.escape

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer: GameRenderer = GameRenderer(context)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
