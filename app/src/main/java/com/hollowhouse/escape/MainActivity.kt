package com.hollowhouse.escape

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var renderer: GameRenderer

    private lateinit var keysText: TextView
    private lateinit var stateText: TextView
    private lateinit var dangerOverlay: android.view.View

    private lateinit var menuOverlay: LinearLayout
    private lateinit var loseOverlay: LinearLayout
    private lateinit var winOverlay: LinearLayout
    private lateinit var muteBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastState = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)
        renderer = gameView.renderer
        renderer.moveStick = findViewById(R.id.moveStick)
        renderer.lookPad = findViewById(R.id.lookPad)

        keysText = findViewById(R.id.keysText)
        stateText = findViewById(R.id.stateText)
        dangerOverlay = findViewById(R.id.dangerOverlay)
        menuOverlay = findViewById(R.id.menuOverlay)
        loseOverlay = findViewById(R.id.loseOverlay)
        winOverlay = findViewById(R.id.winOverlay)
        muteBtn = findViewById(R.id.muteBtn)

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            renderer.startRequested = true
        }
        findViewById<Button>(R.id.retryLoseBtn).setOnClickListener {
            renderer.restartRequested = true
        }
        findViewById<Button>(R.id.retryWinBtn).setOnClickListener {
            renderer.restartRequested = true
        }
        muteBtn.setOnClickListener {
            renderer.muted = !renderer.muted
            muteBtn.text = if (renderer.muted) "🔇" else "🔊"
        }

        attachHoldButton(findViewById(R.id.sneakBtn)) { renderer.sneaking = it }
        attachHoldButton(findViewById(R.id.runBtn)) { renderer.running = it }

        handler.post(pollRunnable)
    }

    private fun attachHoldButton(button: Button, onPressed: (Boolean) -> Unit) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onPressed(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onPressed(false)
            }
            true
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            keysText.text = "Keys: ${renderer.keysCollected}/${renderer.totalKeys}"
            stateText.text = renderer.stateLabel

            val danger = renderer.dangerIntensity
            dangerOverlay.alpha = (danger * 0.55f).coerceIn(0f, 0.55f)

            val state = renderer.gameState
            if (state != lastState) {
                menuOverlay.visibility = if (state == "menu") android.view.View.VISIBLE else android.view.View.GONE
                loseOverlay.visibility = if (state == "lost") android.view.View.VISIBLE else android.view.View.GONE
                winOverlay.visibility = if (state == "won") android.view.View.VISIBLE else android.view.View.GONE
                lastState = state
            }
            handler.postDelayed(this, 80)
        }
    }

    override fun onResume() {
        super.onResume()
        gameView.onResume()
    }

    override fun onPause() {
        super.onPause()
        gameView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
    }
}
