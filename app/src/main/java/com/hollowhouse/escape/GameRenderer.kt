package com.hollowhouse.escape

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class GameRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // ---------------- input, set by MainActivity / UI thread ----------------
    var moveStick: TouchStick? = null
    var lookPad: LookPad? = null
    @Volatile var sneaking = false
    @Volatile var running = false
    @Volatile var muted = false
    @Volatile var startRequested = false
    @Volatile var restartRequested = false

    // ---------------- output, polled by MainActivity for the HUD ----------------
    @Volatile var keysCollected = 0
    val totalKeys = KEY_POSITIONS.size
    @Volatile var gameState = "menu" // menu | playing | won | lost
    @Volatile var stateLabel = "Still"
    @Volatile var dangerIntensity = 0f

    // ---------------- world constants ----------------
    private val HX = 14f
    private val HZ = 10f
    private val WALL_H = 3.2f
    private val THICK = 0.35f
    private val PLAYER_R = 0.32f
    private val EYE_Y = 1.65f

    private data class Box(val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float,
                            val r: Float, val g: Float, val b: Float)

    private val walls = ArrayList<Box>()

    companion object {
        val KEY_POSITIONS = arrayOf(
            floatArrayOf(-8.5f, -8.2f), floatArrayOf(8.5f, -8.2f),
            floatArrayOf(-8.5f, 8.2f), floatArrayOf(8.5f, 8.2f),
            floatArrayOf(0f, -8.5f)
        )
        val PATROL_POINTS = arrayOf(
            floatArrayOf(-8.5f, -8.2f), floatArrayOf(8.5f, -8.2f),
            floatArrayOf(8.5f, 8.2f), floatArrayOf(-8.5f, 8.2f), floatArrayOf(0f, 0f)
        )
    }

    private val keyVisible = BooleanArray(KEY_POSITIONS.size) { true }
    private var worldTime = 0f

    // player state
    private var playerX = 0f
    private var playerZ = 8f
    private var yaw = Math.PI.toFloat()
    private var pitch = 0f

    // stalker state
    private var stalkerX = PATROL_POINTS[0][0]
    private var stalkerZ = PATROL_POINTS[0][1]
    private var patrolIdx = 0
    private var stalkerState = "patrol" // patrol | chase | search
    private var searchTargetX = 0f
    private var searchTargetZ = 0f
    private var searchTimer = 0f
    private var lastBeatTime = 0f

    private var doorUnlocked = false
    private val doorGoalZ = HZ - 0.9f

    // ---------------- GL objects ----------------
    private var program = 0
    private var uMVPLoc = 0
    private var uColorLoc = 0
    private var uLightDirLoc = 0
    private var aPositionLoc = 0
    private var aNormalLoc = 0
    private var vbo = 0

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var lastFrameNanos = 0L
    private var toneGen: ToneGenerator? = null

    init {
        buildHouse()
    }

    // =========================================================================
    // World construction
    // =========================================================================

    private fun wallX(z: Float, x1: Float, x2: Float, gaps: List<FloatArray> = emptyList(), color: FloatArray) {
        val sorted = gaps.sortedBy { it[0] }
        var cursor = x1
        for (g in sorted) {
            if (g[0] > cursor) addBox((cursor + g[0]) / 2f, z, g[0] - cursor, THICK, color)
            cursor = g[1]
        }
        if (x2 > cursor) addBox((cursor + x2) / 2f, z, x2 - cursor, THICK, color)
    }

    private fun wallZ(x: Float, z1: Float, z2: Float, gaps: List<FloatArray> = emptyList(), color: FloatArray) {
        val sorted = gaps.sortedBy { it[0] }
        var cursor = z1
        for (g in sorted) {
            if (g[0] > cursor) addBox(x, (cursor + g[0]) / 2f, THICK, g[0] - cursor, color)
            cursor = g[1]
        }
        if (z2 > cursor) addBox(x, (cursor + z2) / 2f, THICK, z2 - cursor, color)
    }

    private fun addBox(cx: Float, cz: Float, w: Float, d: Float, color: FloatArray) {
        walls.add(Box(cx - w / 2f, cx + w / 2f, cz - d / 2f, cz + d / 2f, color[0], color[1], color[2]))
    }

    private fun buildHouse() {
        val outer = floatArrayOf(0.20f, 0.17f, 0.14f)
        val inner = floatArrayOf(0.16f, 0.13f, 0.11f)
        val furn = floatArrayOf(0.11f, 0.09f, 0.07f)

        wallX(-HZ, -HX, HX, emptyList(), outer)
        wallX(HZ, -HX, HX, listOf(floatArrayOf(-1.3f, 1.3f)), outer)
        wallZ(-HX, -HZ, HZ, emptyList(), outer)
        wallZ(HX, -HZ, HZ, emptyList(), outer)

        wallZ(-1.4f, -HZ, HZ, listOf(floatArrayOf(-8.6f, -5.4f), floatArrayOf(3.2f, 7.0f)), inner)
        wallZ(1.4f, -HZ, HZ, listOf(floatArrayOf(-8.6f, -5.4f), floatArrayOf(3.2f, 7.0f)), inner)

        wallX(-3.6f, -HX, -1.4f, listOf(floatArrayOf(-8.5f, -6.5f)), inner)
        wallX(-3.6f, 1.4f, HX, listOf(floatArrayOf(6.5f, 8.5f)), inner)

        wallX(3.6f, -HX, -1.4f, listOf(floatArrayOf(-8.5f, -6.5f)), inner)
        wallX(3.6f, 1.4f, HX, listOf(floatArrayOf(6.5f, 8.5f)), inner)

        val furniture = arrayOf(
            floatArrayOf(-10f, -8f, 1.6f, 0.8f), floatArrayOf(10f, -8f, 1.6f, 0.8f),
            floatArrayOf(-10f, 8f, 1.4f, 1.4f), floatArrayOf(10f, 8f, 1.2f, 1.2f),
            floatArrayOf(-6f, 0f, 0.7f, 2.4f), floatArrayOf(6f, 0f, 0.7f, 2.4f),
            floatArrayOf(0f, -8.6f, 2.0f, 0.5f)
        )
        furniture.forEach { addBox(it[0], it[1], it[2], it[3], furn) }
    }

    // 2D segment vs AABB (Liang-Barsky) — true if the segment crosses the rect
    private fun segRect(x1: Float, z1: Float, x2: Float, z2: Float,
                         minX: Float, maxX: Float, minZ: Float, maxZ: Float): Boolean {
        val dx = x2 - x1; val dz = z2 - z1
        val p = floatArrayOf(-dx, dx, -dz, dz)
        val q = floatArrayOf(x1 - minX, maxX - x1, z1 - minZ, maxZ - z1)
        var u1 = 0f; var u2 = 1f
        for (i in 0..3) {
            if (abs(p[i]) < 1e-6f) {
                if (q[i] < 0f) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) { if (t > u2) return false; if (t > u1) u1 = t }
                else { if (t < u1) return false; if (t < u2) u2 = t }
            }
        }
        return true
    }

    private fun segmentBlocked(x1: Float, z1: Float, x2: Float, z2: Float): Boolean {
        for (b in walls) if (segRect(x1, z1, x2, z2, b.minX, b.maxX, b.minZ, b.maxZ)) return true
        return false
    }

    private fun tryMove(nx: Float, nz: Float) {
        var px = playerX; var pz = playerZ
        var blocked = false
        for (b in walls) {
            if (nx > b.minX - PLAYER_R && nx < b.maxX + PLAYER_R && pz > b.minZ - PLAYER_R && pz < b.maxZ + PLAYER_R) { blocked = true; break }
        }
        if (!blocked) px = nx
        blocked = false
        for (b in walls) {
            if (px > b.minX - PLAYER_R && px < b.maxX + PLAYER_R && nz > b.minZ - PLAYER_R && nz < b.maxZ + PLAYER_R) { blocked = true; break }
        }
        if (!blocked) pz = nz
        playerX = max(-HX + 0.4f, min(HX - 0.4f, px))
        playerZ = max(-HZ + 0.4f, min(HZ - 0.4f, pz))
    }

    // =========================================================================
    // Game reset
    // =========================================================================

    private fun resetGame() {
        keysCollected = 0
        for (i in keyVisible.indices) keyVisible[i] = true
        doorUnlocked = false
        playerX = 0f; playerZ = 8f
        yaw = Math.PI.toFloat(); pitch = 0f
        stalkerX = PATROL_POINTS[0][0]; stalkerZ = PATROL_POINTS[0][1]
        patrolIdx = 0
        stalkerState = "patrol"
        searchTimer = 0f
        dangerIntensity = 0f
    }

    // =========================================================================
    // Per-frame update
    // =========================================================================

    private fun updatePlayer(dt: Float): Float {
        val look = lookPad?.consumeDelta()
        if (look != null) {
            yaw -= look[0] * 0.0035f
            pitch -= look[1] * 0.0035f
            pitch = max(-1.3f, min(1.3f, pitch))
        }

        val stickDX = moveStick?.dx ?: 0f
        val stickDY = moveStick?.dy ?: 0f
        val fwd = -stickDY   // pushing the stick up moves forward
        val strafe = stickDX

        val speed = if (sneaking) 1.3f else if (running) 4.6f else 2.6f
        val moving = abs(fwd) > 0.02f || abs(strafe) > 0.02f

        if (moving) {
            val sinY = sin(yaw); val cosY = cos(yaw)
            // forward vector rotated by yaw, plus strafe (right) vector
            val dirX = strafe * cosY + fwd * sinY
            val dirZ = -strafe * sinY + fwd * cosY
            val len = hypot(dirX.toDouble(), dirZ.toDouble()).toFloat().let { if (it > 1f) it else 1f }
            val nx = playerX + (dirX / len) * speed * dt
            val nz = playerZ + (dirZ / len) * speed * dt
            tryMove(nx, nz)
        }

        stateLabel = if (sneaking) "Creeping" else if (running) "Running" else if (moving) "Walking" else "Still"

        return when {
            sneaking && !moving -> 1.0f
            sneaking -> 3.2f
            running -> 13.5f
            !moving -> 2.5f
            else -> 8.0f
        }
    }

    private fun updateKeys(dt: Float) {
        for (i in KEY_POSITIONS.indices) {
            if (!keyVisible[i]) continue
            val kx = KEY_POSITIONS[i][0]; val kz = KEY_POSITIONS[i][1]
            val dx = kx - playerX; val dz = kz - playerZ
            if (hypot(dx.toDouble(), dz.toDouble()) < 0.9) {
                keyVisible[i] = false
                keysCollected++
                if (keysCollected >= totalKeys) doorUnlocked = true
            }
        }
    }

    private fun updateStalker(dt: Float, noiseRadius: Float) {
        val dx0 = playerX - stalkerX; val dz0 = playerZ - stalkerZ
        val dist = hypot(dx0.toDouble(), dz0.toDouble()).toFloat()
        val canSee = !segmentBlocked(stalkerX, stalkerZ, playerX, playerZ)

        if (stalkerState != "chase") {
            if (canSee && dist < noiseRadius) stalkerState = "chase"
        } else {
            if (!canSee || dist > noiseRadius + 6f) {
                searchTimer += dt
                if (searchTimer > 2.5f) {
                    stalkerState = "search"
                    searchTargetX = playerX; searchTargetZ = playerZ
                    searchTimer = 0f
                }
            } else searchTimer = 0f
        }

        var targetX: Float; var targetZ: Float; var spd: Float
        when (stalkerState) {
            "chase" -> { targetX = playerX; targetZ = playerZ; spd = 3.15f }
            "search" -> {
                targetX = searchTargetX; targetZ = searchTargetZ; spd = 2.2f
                if (hypot((stalkerX - targetX).toDouble(), (stalkerZ - targetZ).toDouble()) < 0.6) stalkerState = "patrol"
            }
            else -> {
                val wp = PATROL_POINTS[patrolIdx]
                targetX = wp[0]; targetZ = wp[1]; spd = 1.5f
                if (hypot((stalkerX - targetX).toDouble(), (stalkerZ - targetZ).toDouble()) < 0.6) {
                    patrolIdx = (patrolIdx + 1) % PATROL_POINTS.size
                }
            }
        }

        val ddx = targetX - stalkerX; val ddz = targetZ - stalkerZ
        val d = hypot(ddx.toDouble(), ddz.toDouble()).toFloat().let { if (it < 0.001f) 1f else it }
        val moveX = stalkerX + ddx / d * spd * dt
        val moveZ = stalkerZ + ddz / d * spd * dt

        var blocked = false
        for (b in walls) if (moveX > b.minX - 0.3f && moveX < b.maxX + 0.3f && stalkerZ > b.minZ - 0.3f && stalkerZ < b.maxZ + 0.3f) { blocked = true; break }
        if (!blocked) stalkerX = moveX
        blocked = false
        for (b in walls) if (stalkerX > b.minX - 0.3f && stalkerX < b.maxX + 0.3f && moveZ > b.minZ - 0.3f && moveZ < b.maxZ + 0.3f) { blocked = true; break }
        if (!blocked) stalkerZ = moveZ

        if (stalkerState == "chase") {
            val targetIntensity = min(1f, 1.4f - dist * 0.08f).coerceAtLeast(0.15f)
            dangerIntensity += (targetIntensity - dangerIntensity) * min(1f, dt * 6f)
            val bpm = max(0.16f, 0.55f - dist * 0.03f)
            if (worldTime - lastBeatTime > bpm) {
                beep()
                lastBeatTime = worldTime
            }
        } else {
            dangerIntensity += (0f - dangerIntensity) * min(1f, dt * 3f)
        }

        if (dist < 0.85f) gameState = "lost"
    }

    private fun beep() {
        if (muted) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 90) } catch (e: Exception) { /* ignore */ }
    }

    private fun checkWin() {
        if (keysCollected < totalKeys) return
        val dz = abs(playerZ - doorGoalZ)
        val dx = abs(playerX)
        if (dz < 0.6f && dx < 1.3f) gameState = "won"
    }

    // =========================================================================
    // GL lifecycle
    // =========================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.01f, 0.008f, 0.006f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vertexShaderSrc = """
            attribute vec4 aPosition;
            attribute vec3 aNormal;
            uniform mat4 uMVP;
            varying vec3 vNormal;
            void main() {
                gl_Position = uMVP * aPosition;
                vNormal = aNormal;
            }
        """.trimIndent()

        val fragmentShaderSrc = """
            precision mediump float;
            varying vec3 vNormal;
            uniform vec4 uColor;
            uniform vec3 uLightDir;
            void main() {
                float diff = max(dot(normalize(vNormal), normalize(uLightDir)), 0.0);
                float ambient = 0.4;
                vec3 col = uColor.rgb * (ambient + diff * 0.6);
                gl_FragColor = vec4(col, uColor.a);
            }
        """.trimIndent()

        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSrc)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aNormalLoc = GLES20.glGetAttribLocation(program, "aNormal")
        uMVPLoc = GLES20.glGetUniformLocation(program, "uMVP")
        uColorLoc = GLES20.glGetUniformLocation(program, "uColor")
        uLightDirLoc = GLES20.glGetUniformLocation(program, "uLightDir")

        val bb = ByteBuffer.allocateDirect(CubeMesh.vertexData.size * 4).order(ByteOrder.nativeOrder())
        val fb: FloatBuffer = bb.asFloatBuffer().apply { put(CubeMesh.vertexData); position(0) }

        val vboArr = IntArray(1)
        GLES20.glGenBuffers(1, vboArr, 0)
        vbo = vboArr[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, fb.capacity() * 4, fb, GLES20.GL_STATIC_DRAW)

        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) { toneGen = null }

        lastFrameNanos = System.nanoTime()
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        Matrix.perspectiveM(projMatrix, 0, 72f, aspect, 0.1f, 200f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        dt = min(dt, 0.05f)
        worldTime += dt

        if (gameState == "menu" && startRequested) { gameState = "playing"; startRequested = false }
        if (restartRequested) { resetGame(); gameState = "playing"; restartRequested = false }

        if (gameState == "playing") {
            val noiseRadius = updatePlayer(dt)
            updateKeys(dt)
            updateStalker(dt, noiseRadius)
            checkWin()
        } else {
            dangerIntensity += (0f - dangerIntensity) * min(1f, dt * 3f)
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val dirX = cos(pitch) * sin(yaw)
        val dirY = sin(pitch)
        val dirZ = cos(pitch) * cos(yaw)
        Matrix.setLookAtM(
            viewMatrix, 0,
            playerX, EYE_Y, playerZ,
            playerX + dirX, EYE_Y + dirY, playerZ + dirZ,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(aNormalLoc)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glUniform3f(uLightDirLoc, 0.3f, 1f, 0.25f)

        // floor & ceiling
        drawBox(0f, -0.05f, 0f, HX * 2, 0.1f, HZ * 2, 0.11f, 0.09f, 0.07f)
        drawBox(0f, WALL_H + 0.05f, 0f, HX * 2, 0.1f, HZ * 2, 0.05f, 0.04f, 0.03f)

        for (b in walls) {
            val cx = (b.minX + b.maxX) / 2f
            val cz = (b.minZ + b.maxZ) / 2f
            drawBox(cx, WALL_H / 2f, cz, b.maxX - b.minX, WALL_H, b.maxZ - b.minZ, b.r, b.g, b.b)
        }

        // door
        val doorColor = if (doorUnlocked) floatArrayOf(0.22f, 0.42f, 0.2f) else floatArrayOf(0.36f, 0.17f, 0.12f)
        drawBox(0f, 1.3f, HZ - 0.1f, 2.4f, 2.6f, 0.25f, doorColor[0], doorColor[1], doorColor[2], minBrightness = 0.3f)

        // keys
        for (i in KEY_POSITIONS.indices) {
            if (!keyVisible[i]) continue
            val kx = KEY_POSITIONS[i][0]; val kz = KEY_POSITIONS[i][1]
            val bob = 1.1f + sin(worldTime * 2f + i) * 0.08f
            drawBox(kx, bob, kz, 0.24f, 0.42f, 0.24f, 0.91f, 0.72f, 0.29f,
                minBrightness = 0.35f, rotY = worldTime * 1.6f + i)
        }

        // stalker
        val nearFactor = max(0.22f, dangerIntensity)
        drawBox(stalkerX, 0.6f, stalkerZ, 0.5f, 1.15f, 0.5f, 0.06f, 0.05f, 0.05f, minBrightness = nearFactor * 0.3f)
        drawBox(stalkerX, 1.55f, stalkerZ, 0.4f, 0.4f, 0.4f, 0.06f, 0.05f, 0.05f, minBrightness = nearFactor * 0.3f)
        if (stalkerState == "chase") {
            drawBox(stalkerX, 1.6f, stalkerZ - 0.2f, 0.14f, 0.06f, 0.06f, 0.6f, 0.02f, 0.02f, minBrightness = 0.6f)
        }
    }

    private fun drawBox(cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float,
                         r: Float, g: Float, b: Float, minBrightness: Float = 0.12f, rotY: Float = 0f) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cx, cy, cz)
        if (rotY != 0f) Matrix.rotateM(modelMatrix, 0, Math.toDegrees(rotY.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, w, h, d)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        val ddx = cx - playerX; val ddy = cy - EYE_Y; val ddz = cz - playerZ
        val dist = sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
        val falloff = max(minBrightness, min(1f, 1f - dist / 13f))

        GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(uColorLoc, r * falloff, g * falloff, b * falloff, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CubeMesh.VERTEX_COUNT)
    }
}
