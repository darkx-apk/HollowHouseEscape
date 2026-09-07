package com.hollowhouse.escape

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var renderer: GameRenderer
    private lateinit var soundManager: SoundManager

    private lateinit var keysText: TextView
    private lateinit var stateText: TextView
    private lateinit var floorText: TextView
    private lateinit var dangerGlow: ImageView
    private lateinit var rootView: FrameLayout

    private lateinit var interactBtn: Button
    private lateinit var interactHint: TextView
    private lateinit var hideBtn: Button

    private lateinit var menuOverlay: FrameLayout
    private lateinit var loseOverlay: LinearLayout
    private lateinit var winOverlay: LinearLayout
    private lateinit var loaderOverlay: LinearLayout
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

        rootView = findViewById(android.R.id.content)

        gameView = findViewById(R.id.gameView)
        renderer = gameView.renderer
        renderer.moveStick = findViewById(R.id.moveStick)
        renderer.lookPad = findViewById(R.id.lookPad)

        soundManager = SoundManager(this)
        soundManager.init()

        keysText = findViewById(R.id.keysText)
        stateText = findViewById(R.id.stateText)
        floorText = findViewById(R.id.floorText)
        dangerGlow = findViewById(R.id.dangerGlow)
        interactBtn = findViewById(R.id.interactBtn)
        interactHint = findViewById(R.id.interactHint)
        hideBtn = findViewById(R.id.hideBtn)
        menuOverlay = findViewById(R.id.menuOverlay)
        loseOverlay = findViewById(R.id.loseOverlay)
        winOverlay = findViewById(R.id.winOverlay)
        loaderOverlay = findViewById(R.id.loaderOverlay)
        muteBtn = findViewById(R.id.muteBtn)

        // Hand icon: tap to open/close the nearest door, or feed a key into the
        // front door once you're carrying one.
        interactBtn.setOnClickListener { renderer.interactRequested = true }

        // Crouch toggle: press once to crouch and slip under a bed/table hide
        // spot, press again to stand back up.
        hideBtn.setOnClickListener {
            renderer.crouching = !renderer.crouching
            hideBtn.text = if (renderer.crouching) "Stand" else "Hide"
        }

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            renderer.startRequested = true
            soundManager.stopMenuMusic()
        }
        findViewById<Button>(R.id.retryLoseBtn).setOnClickListener {
            renderer.restartRequested = true
        }
        findViewById<Button>(R.id.retryWinBtn).setOnClickListener {
            renderer.restartRequested = true
        }
        muteBtn.setOnClickListener {
            renderer.muted = !renderer.muted
            soundManager.setMuted(renderer.muted)
            muteBtn.text = if (renderer.muted) "🔇" else "🔊"
        }

        attachHoldButton(findViewById(R.id.sneakBtn)) { renderer.sneaking = it }
        attachHoldButton(findViewById(R.id.runBtn)) { renderer.running = it }

        handler.post(pollRunnable)

        // Show the loader/splash briefly while the scene warms up, then fade it away
        // and start the main-menu music underneath it.
        handler.postDelayed({
            loaderOverlay.animate()
                .alpha(0f)
                .setDuration(420)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        loaderOverlay.visibility = View.GONE
                    }
                })
                .start()
            soundManager.playMenuMusic()
        }, 1600)
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
            keysText.text = "Keys: ${renderer.keysDelivered}/${renderer.totalKeys}" +
                if (renderer.keysHeld > 0) "  (carrying ${renderer.keysHeld})" else ""
            stateText.text = renderer.stateLabel
            floorText.text = if (renderer.currentFloor == 1) "Ground Floor" else "Upper Floor"

            val state = renderer.gameState
            if (state != lastState) {
                menuOverlay.visibility = if (state == "menu") View.VISIBLE else View.GONE
                loseOverlay.visibility = if (state == "lost") View.VISIBLE else View.GONE
                winOverlay.visibility = if (state == "won") View.VISIBLE else View.GONE
                lastState = state
            }

            if (state == "playing") {
                soundManager.setRunningFootsteps(renderer.running && renderer.stateLabel == "Running")
                soundManager.updateGrannySound(renderer.stalkerDistance)
                updateDangerGlow()
                updateInteractPrompt()
            } else {
                soundManager.setRunningFootsteps(false)
                soundManager.updateGrannySound(999f)
                dangerGlow.alpha = 0f
                interactBtn.visibility = View.INVISIBLE
                interactHint.visibility = View.INVISIBLE
            }

            handler.postDelayed(this, 80)
        }
    }

    /**
     * Floats the hand icon over whichever door the player is currently facing,
     * projected from the door's 3D position onto the screen by the renderer.
     */
    private fun updateInteractPrompt() {
        if (!renderer.canInteract || !renderer.promptOnScreen) {
            interactBtn.visibility = View.INVISIBLE
            interactHint.visibility = View.INVISIBLE
            return
        }
        val w = rootView.width.toFloat()
        val h = rootView.height.toFloat()
        if (w <= 0f || h <= 0f || interactBtn.width <= 0) return

        interactBtn.visibility = View.VISIBLE
        interactHint.visibility = View.VISIBLE
        interactHint.text = renderer.interactLabel

        val px = renderer.promptScreenX * w
        val py = renderer.promptScreenY * h
        interactBtn.translationX = px - interactBtn.width / 2f
        interactBtn.translationY = py - interactBtn.height / 2f
        interactHint.translationX = (px - interactHint.width / 2f).coerceIn(4f, w - interactHint.width - 4f)
        interactHint.translationY = py - interactBtn.height / 2f - interactHint.height - 8f
    }

    /**
     * Places the small red glow on the edge of the screen, in the direction
     * the stalker actually is relative to where the player is looking —
     * instead of tinting the whole screen red.
     */
    private fun updateDangerGlow() {
        val intensity = renderer.dangerIntensity
        if (intensity < 0.03f) {
            dangerGlow.alpha = 0f
            return
        }
        val w = rootView.width.toFloat()
        val h = rootView.height.toFloat()
        if (w <= 0f || h <= 0f || dangerGlow.width <= 0) return

        val bearing = renderer.dangerBearing.toDouble()
        val dx = sin(bearing).toFloat()
        val dy = -cos(bearing).toFloat()

        val cx = w / 2f
        val cy = h / 2f
        val margin = dangerGlow.width * 0.6f
        val halfW = w / 2f - margin
        val halfH = h / 2f - margin
        val tx = if (abs(dx) > 0.0001f) halfW / abs(dx) else Float.MAX_VALUE
        val ty = if (abs(dy) > 0.0001f) halfH / abs(dy) else Float.MAX_VALUE
        val t = min(tx, ty)

        val px = cx + dx * t
        val py = cy + dy * t

        dangerGlow.translationX = px - dangerGlow.width / 2f
        dangerGlow.translationY = py - dangerGlow.height / 2f
        dangerGlow.alpha = intensity.coerceIn(0f, 1f)
        val scale = 0.8f + intensity * 0.6f
        dangerGlow.scaleX = scale
        dangerGlow.scaleY = scale
    }

    override fun onResume() {
        super.onResume()
        gameView.onResume()
    }

    override fun onPause() {
        super.onPause()
        gameView.onPause()
        soundManager.pauseAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        soundManager.release()
    }
}
