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
import kotlin.math.atan2
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
    @Volatile var crouching = false          // toggled by the Hide button
    @Volatile var muted = false
    @Volatile var startRequested = false
    @Volatile var restartRequested = false
    @Volatile var interactRequested = false  // one-shot, set by the hand-icon button

    // ---------------- output, polled by MainActivity for the HUD ----------------
    @Volatile var keysHeld = 0
    @Volatile var keysDelivered = 0
    @Volatile var currentFloor = 1
    @Volatile var isHidden = false
    @Volatile var gameState = "menu" // menu | playing | won | lost
    @Volatile var stateLabel = "Still"
    @Volatile var dangerIntensity = 0f
    @Volatile var dangerBearing = 0f
    @Volatile var stalkerDistance = 999f

    // the floating hand-icon prompt: where to draw it and what it should say
    @Volatile var canInteract = false
    @Volatile var interactLabel = ""
    @Volatile var promptOnScreen = false
    @Volatile var promptScreenX = 0.5f
    @Volatile var promptScreenY = 0.5f

    // ---------------- world constants ----------------
    private val HX = Metrics.HX
    private val HZ = Metrics.HZ
    private val WALL_H = Metrics.WALL_H
    private val THICK = Metrics.THICK
    private val PLAYER_R = 0.32f
    private val EYE_Y = 1.65f
    private val CROUCH_EYE_Y = 0.95f

    companion object {
        private val ZERO_EMISSIVE = floatArrayOf(0f, 0f, 0f)
        private val MOON_GLOW = floatArrayOf(0.10f, 0.13f, 0.20f)
        private val DOOR_CLOSED = floatArrayOf(0.30f, 0.17f, 0.11f)
        private val DOOR_OPEN = floatArrayOf(0.34f, 0.24f, 0.15f)
    }

    // ---------------- level ----------------
    private val floor1: FloorData = LevelBuilder.buildFloor1()
    private val floor2: FloorData = LevelBuilder.buildFloor2()
    private val floors = mapOf(1 to floor1, 2 to floor2)
    val totalKeys: Int = floor1.keySpots.size + floor2.keySpots.size

    private fun activeFloor(): FloorData = floors[currentFloor] ?: floor1

    private val mainDoor = Door("main", -1.3f, HZ, 2.6f, 'X')
    private var doorUnlocked = false
    private val doorGoalZ = HZ - 0.9f
    private var stairsCooldown = 0f
    private var interactTarget: Door? = null

    private val PATROL_POINTS = arrayOf(
        floatArrayOf(-9f, -1f), floatArrayOf(9f, -1f), floatArrayOf(9f, 8.5f),
        floatArrayOf(-9f, 8.5f), floatArrayOf(0f, 3f)
    )

    private var worldTime = 0f

    // player state
    private var playerX = 0f
    private var playerZ = 8f
    private var yaw = Math.PI.toFloat()
    private var pitch = 0f
    private var eyeY = EYE_Y

    // stalker state — only ever active on floor 1
    private var stalkerX = PATROL_POINTS[0][0]
    private var stalkerZ = PATROL_POINTS[0][1]
    private var patrolIdx = 0
    private var stalkerState = "patrol" // patrol | chase | search
    private var searchTargetX = 0f
    private var searchTargetZ = 0f
    private var searchTimer = 0f
    private var lastBeatTime = 0f

    // ---------------- GL objects ----------------
    private var program = 0
    private var uMVPLoc = 0
    private var uModelLoc = 0
    private var uColorLoc = 0
    private var uEmissiveLoc = 0
    private var uLightDirLoc = 0
    private var uTorchPosLoc = 0
    private var uTorchDirLoc = 0
    private var uCutOffLoc = 0
    private var uOuterCutOffLoc = 0
    private var uMoonPosLoc = 0
    private var uMoonCountLoc = 0
    private var aPositionLoc = 0
    private var aNormalLoc = 0
    private var vbo = 0

    // flashlight cone: inner angle stays fully bright, outer angle is the soft edge
    private val torchCutOff = cos(Math.toRadians(17.0)).toFloat()
    private val torchOuterCutOff = cos(Math.toRadians(32.0)).toFloat()

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var lastFrameNanos = 0L
    private var toneGen: ToneGenerator? = null

    // =========================================================================
    // Collision helpers
    // =========================================================================

    private fun doorBox(d: Door): Box =
        if (d.axis == 'X') Box(d.hingeX, d.hingeX + d.width, d.hingeZ - THICK / 2f, d.hingeZ + THICK / 2f, 0f, 0f, 0f)
        else Box(d.hingeX - THICK / 2f, d.hingeX + THICK / 2f, d.hingeZ, d.hingeZ + d.width, 0f, 0f, 0f)

    private fun doorMidpoint(d: Door): Pair<Float, Float> =
        if (d.axis == 'X') Pair(d.hingeX + d.width / 2f, d.hingeZ)
        else Pair(d.hingeX, d.hingeZ + d.width / 2f)

    /** Everything that currently blocks movement: walls, plain furniture, closed
     *  doors, and — unless the player is crouching — the hideable furniture too. */
    private fun collidable(): List<Box> {
        val f = activeFloor()
        val list = ArrayList<Box>(f.walls.size + f.furniture.size + f.hideSpots.size + f.doors.size + 2)
        list.addAll(f.walls)
        list.addAll(f.furniture)
        if (!crouching) for (h in f.hideSpots) list.add(h.toBox())
        for (d in f.doors) if (d.angle < 0.35f) list.add(doorBox(d))
        if (currentFloor == 1 && mainDoor.angle < 0.35f) list.add(doorBox(mainDoor))
        return list
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
        val f = activeFloor()
        for (b in f.walls) if (segRect(x1, z1, x2, z2, b.minX, b.maxX, b.minZ, b.maxZ)) return true
        for (d in f.doors) if (d.angle < 0.35f) {
            val b = doorBox(d); if (segRect(x1, z1, x2, z2, b.minX, b.maxX, b.minZ, b.maxZ)) return true
        }
        if (currentFloor == 1 && mainDoor.angle < 0.35f) {
            val b = doorBox(mainDoor); if (segRect(x1, z1, x2, z2, b.minX, b.maxX, b.minZ, b.maxZ)) return true
        }
        return false
    }

    private fun tryMove(nx: Float, nz: Float) {
        val boxes = collidable()
        var px = playerX; var pz = playerZ
        var blocked = false
        for (b in boxes) if (nx > b.minX - PLAYER_R && nx < b.maxX + PLAYER_R && pz > b.minZ - PLAYER_R && pz < b.maxZ + PLAYER_R) { blocked = true; break }
        if (!blocked) px = nx
        blocked = false
        for (b in boxes) if (px > b.minX - PLAYER_R && px < b.maxX + PLAYER_R && nz > b.minZ - PLAYER_R && nz < b.maxZ + PLAYER_R) { blocked = true; break }
        if (!blocked) pz = nz
        playerX = max(-HX + 0.4f, min(HX - 0.4f, px))
        playerZ = max(-HZ + 0.4f, min(HZ - 0.4f, pz))
    }

    // =========================================================================
    // Game reset
    // =========================================================================

    private fun resetGame() {
        keysHeld = 0; keysDelivered = 0
        for (fl in floors.values) {
            for (k in fl.keySpots) k.collected = false
            for (d in fl.doors) { d.isOpen = false; d.angle = 0f }
        }
        mainDoor.isOpen = false; mainDoor.angle = 0f
        doorUnlocked = false
        currentFloor = 1
        crouching = false
        isHidden = false
        playerX = 0f; playerZ = 8f
        yaw = Math.PI.toFloat(); pitch = 0f
        eyeY = EYE_Y
        stalkerX = PATROL_POINTS[0][0]; stalkerZ = PATROL_POINTS[0][1]
        patrolIdx = 0
        stalkerState = "patrol"
        searchTimer = 0f
        dangerIntensity = 0f
        stairsCooldown = 0f
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
        val strafe = -stickDX // pushing the stick right must move the player right

        val speed = when {
            crouching -> 0.9f
            sneaking -> 1.3f
            running -> 4.6f
            else -> 2.6f
        }
        val moving = abs(fwd) > 0.02f || abs(strafe) > 0.02f

        if (moving) {
            val sinY = sin(yaw); val cosY = cos(yaw)
            val dirX = strafe * cosY + fwd * sinY
            val dirZ = -strafe * sinY + fwd * cosY
            val len = hypot(dirX.toDouble(), dirZ.toDouble()).toFloat().let { if (it > 1f) it else 1f }
            val nx = playerX + (dirX / len) * speed * dt
            val nz = playerZ + (dirZ / len) * speed * dt
            tryMove(nx, nz)
        }

        isHidden = crouching && activeFloor().hideSpots.any { it.contains(playerX, playerZ) }

        stateLabel = when {
            isHidden -> "Hidden"
            crouching -> "Crouching"
            sneaking -> "Creeping"
            running -> "Running"
            moving -> "Walking"
            else -> "Still"
        }

        val targetEye = if (crouching) CROUCH_EYE_Y else EYE_Y
        eyeY += (targetEye - eyeY) * min(1f, dt * 8f)

        return when {
            isHidden -> 0.35f
            crouching && !moving -> 0.7f
            crouching -> 1.8f
            sneaking && !moving -> 1.0f
            sneaking -> 3.2f
            running -> 13.5f
            !moving -> 2.5f
            else -> 8.0f
        }
    }

    private fun updateDoors(dt: Float) {
        val speed = Math.toRadians(220.0).toFloat()
        val maxAngle = Math.toRadians(100.0).toFloat()
        fun animate(d: Door) {
            val target = if (d.isOpen) maxAngle else 0f
            if (d.angle < target) d.angle = min(target, d.angle + speed * dt)
            else if (d.angle > target) d.angle = max(target, d.angle - speed * dt)
        }
        for (d in activeFloor().doors) animate(d)
        if (currentFloor == 1) animate(mainDoor)
    }

    private fun updateStairs(dt: Float) {
        if (stairsCooldown > 0f) { stairsCooldown -= dt; return }
        for (t in activeFloor().stairs) {
            if (t.contains(playerX, playerZ)) {
                currentFloor = t.targetFloor
                playerX = t.landingX
                playerZ = t.landingZ
                stairsCooldown = 1.0f
                break
            }
        }
    }

    private fun updateKeys(dt: Float) {
        for (k in activeFloor().keySpots) {
            if (k.collected) continue
            val dx = k.x - playerX; val dz = k.z - playerZ
            if (hypot(dx.toDouble(), dz.toDouble()) < 0.9) {
                k.collected = true
                keysHeld++
                uiClick()
            }
        }
    }

    /** Finds the nearest door in front of the player, drives the on-screen hand
     *  icon, and resolves a tap: opens/closes an interior door, or feeds a key
     *  into the front door one at a time until all five have gone in. */
    private fun updateInteraction() {
        var best: Door? = null
        var bestScore = -1f
        val sinY = sin(yaw); val cosY = cos(yaw)
        val candidates = ArrayList<Door>(activeFloor().doors)
        if (currentFloor == 1) candidates.add(mainDoor)
        for (d in candidates) {
            val (mx, mz) = doorMidpoint(d)
            val dx = mx - playerX; val dz = mz - playerZ
            val dist = hypot(dx.toDouble(), dz.toDouble()).toFloat()
            if (dist > 2.0f || dist < 0.001f) continue
            val fwdComp = dx * sinY + dz * cosY
            if (fwdComp <= 0f) continue
            val facing = fwdComp / dist
            if (facing < 0.4f) continue
            val score = facing - dist * 0.05f
            if (score > bestScore) { bestScore = score; best = d }
        }
        interactTarget = best
        canInteract = best != null
        interactLabel = when {
            best == null -> ""
            best === mainDoor && !doorUnlocked -> if (keysHeld > 0) "Insert key ($keysDelivered/$totalKeys)" else "Locked — bring a key"
            best.isOpen -> "Close door"
            else -> "Open door"
        }

        if (best != null) {
            val (mx, mz) = doorMidpoint(best)
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, vpMatrix, 0, floatArrayOf(mx, WALL_H * 0.5f, mz, 1f), 0)
            if (clip[3] > 0.05f) {
                val ndcX = clip[0] / clip[3]; val ndcY = clip[1] / clip[3]
                promptScreenX = (ndcX * 0.5f + 0.5f).coerceIn(0f, 1f)
                promptScreenY = (1f - (ndcY * 0.5f + 0.5f)).coerceIn(0f, 1f)
                promptOnScreen = true
            } else promptOnScreen = false
        } else promptOnScreen = false

        if (interactRequested) {
            interactRequested = false
            val d = best
            if (d != null) {
                if (d === mainDoor && !doorUnlocked) {
                    if (keysHeld > 0) {
                        keysHeld--
                        keysDelivered++
                        if (keysDelivered >= totalKeys) doorUnlocked = true
                        uiClick()
                    }
                } else {
                    d.isOpen = !d.isOpen
                    uiClick()
                }
            }
        }
    }

    private fun updateStalker(dt: Float, noiseRadius: Float) {
        val dx0 = playerX - stalkerX; val dz0 = playerZ - stalkerZ
        val dist = hypot(dx0.toDouble(), dz0.toDouble()).toFloat()
        val canSee = !isHidden && !segmentBlocked(stalkerX, stalkerZ, playerX, playerZ)

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
        for (b in floor1.walls) if (moveX > b.minX - 0.3f && moveX < b.maxX + 0.3f && stalkerZ > b.minZ - 0.3f && stalkerZ < b.maxZ + 0.3f) { blocked = true; break }
        if (!blocked) stalkerX = moveX
        blocked = false
        for (b in floor1.walls) if (stalkerX > b.minX - 0.3f && stalkerX < b.maxX + 0.3f && moveZ > b.minZ - 0.3f && moveZ < b.maxZ + 0.3f) { blocked = true; break }
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

        if (dist < 0.85f && !isHidden) gameState = "lost"
    }

    private fun beep() {
        if (muted) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 90) } catch (e: Exception) { /* ignore */ }
    }

    private fun uiClick() {
        if (muted) return
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 60) } catch (e: Exception) { /* ignore */ }
    }

    private fun checkWin() {
        if (currentFloor != 1 || !doorUnlocked || !mainDoor.isOpen) return
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
            uniform mat4 uModel;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            void main() {
                gl_Position = uMVP * aPosition;
                vWorldPos = (uModel * aPosition).xyz;
                vNormal = mat3(uModel) * aNormal;
            }
        """.trimIndent()

        // The room itself is almost black. The player's held torch is a real
        // spotlight cone in world space. uMoonPos are small always-on point
        // lights placed at each window so moonlight visibly pools nearby even
        // far from the torch. uEmissive adds self-glow (stalker candle, unlocked door).
        val fragmentShaderSrc = """
            precision mediump float;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            uniform vec4 uColor;
            uniform vec3 uEmissive;
            uniform vec3 uLightDir;
            uniform vec3 uTorchPos;
            uniform vec3 uTorchDir;
            uniform float uCutOff;
            uniform float uOuterCutOff;
            uniform vec3 uMoonPos[6];
            uniform int uMoonCount;
            void main() {
                vec3 N = normalize(vNormal);

                float fill = max(dot(N, normalize(uLightDir)), 0.0);
                float ambient = 0.045;
                vec3 base = uColor.rgb * (ambient + fill * 0.05);

                vec3 toFrag = vWorldPos - uTorchPos;
                float dist = length(toFrag);
                vec3 toFragN = toFrag / max(dist, 0.001);
                float theta = dot(toFragN, normalize(uTorchDir));
                float epsilon = max(uCutOff - uOuterCutOff, 0.0001);
                float coneAtten = clamp((theta - uOuterCutOff) / epsilon, 0.0, 1.0);
                float distAtten = 1.0 / (1.0 + 0.05 * dist + 0.02 * dist * dist);
                float nDotL = max(dot(N, -toFragN), 0.0);
                vec3 torchColor = vec3(1.0, 0.93, 0.78);
                vec3 torch = uColor.rgb * torchColor * nDotL * coneAtten * distAtten * 2.8;

                vec3 moon = vec3(0.0);
                for (int i = 0; i < 6; i++) {
                    if (i < uMoonCount) {
                        vec3 toMoon = vWorldPos - uMoonPos[i];
                        float md = length(toMoon);
                        float atten = 1.0 / (1.0 + 0.10 * md + 0.012 * md * md);
                        float wrap = dot(N, normalize(-toMoon)) * 0.5 + 0.5;
                        moon += vec3(0.55, 0.62, 0.85) * atten * wrap;
                    }
                }

                vec3 col = base + torch + uColor.rgb * moon * 0.5 + uEmissive;
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
        uModelLoc = GLES20.glGetUniformLocation(program, "uModel")
        uColorLoc = GLES20.glGetUniformLocation(program, "uColor")
        uEmissiveLoc = GLES20.glGetUniformLocation(program, "uEmissive")
        uLightDirLoc = GLES20.glGetUniformLocation(program, "uLightDir")
        uTorchPosLoc = GLES20.glGetUniformLocation(program, "uTorchPos")
        uTorchDirLoc = GLES20.glGetUniformLocation(program, "uTorchDir")
        uCutOffLoc = GLES20.glGetUniformLocation(program, "uCutOff")
        uOuterCutOffLoc = GLES20.glGetUniformLocation(program, "uOuterCutOff")
        uMoonPosLoc = GLES20.glGetUniformLocation(program, "uMoonPos[0]")
        uMoonCountLoc = GLES20.glGetUniformLocation(program, "uMoonCount")

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
            updateDoors(dt)
            updateStairs(dt)
            updateKeys(dt)
            if (currentFloor == 1) {
                updateStalker(dt, noiseRadius)
            } else {
                stalkerDistance = 999f
                dangerIntensity += (0f - dangerIntensity) * min(1f, dt * 3f)
            }
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
            playerX, eyeY, playerZ,
            playerX + dirX, eyeY + dirY, playerZ + dirZ,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        if (gameState == "playing") updateInteraction() else { canInteract = false; promptOnScreen = false }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(aNormalLoc)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glUniform3f(uLightDirLoc, 0.3f, 1f, 0.25f)

        GLES20.glUniform3f(uTorchPosLoc, playerX, eyeY, playerZ)
        GLES20.glUniform3f(uTorchDirLoc, dirX, dirY, dirZ)
        GLES20.glUniform1f(uCutOffLoc, torchCutOff)
        GLES20.glUniform1f(uOuterCutOffLoc, torchOuterCutOff)

        val windows = activeFloor().windows
        val moonArr = FloatArray(18)
        val moonCount = min(windows.size, 6)
        for (i in 0 until moonCount) {
            moonArr[i * 3] = windows[i].x; moonArr[i * 3 + 1] = windows[i].y; moonArr[i * 3 + 2] = windows[i].z
        }
        GLES20.glUniform3fv(uMoonPosLoc, 6, moonArr, 0)
        GLES20.glUniform1i(uMoonCountLoc, moonCount)

        if (currentFloor == 1) {
            val sinYaw = sin(yaw); val cosYaw = cos(yaw)
            val tx = stalkerX - playerX; val tz = stalkerZ - playerZ
            val fwdComp = tx * sinYaw + tz * cosYaw
            val rightComp = -tx * cosYaw + tz * sinYaw
            dangerBearing = atan2(rightComp, fwdComp)
            stalkerDistance = hypot(tx.toDouble(), tz.toDouble()).toFloat()
        }

        // floor & ceiling
        drawBox(0f, -0.05f, 0f, HX * 2, 0.1f, HZ * 2, 0.11f, 0.09f, 0.07f)
        drawBox(0f, WALL_H + 0.05f, 0f, HX * 2, 0.1f, HZ * 2, 0.05f, 0.04f, 0.03f)

        val floor = activeFloor()

        for (b in floor.walls) {
            val cx = (b.minX + b.maxX) / 2f
            val cz = (b.minZ + b.maxZ) / 2f
            val em = if (b.glow) MOON_GLOW else ZERO_EMISSIVE
            drawBox(cx, WALL_H / 2f, cz, b.maxX - b.minX, WALL_H, b.maxZ - b.minZ, b.r, b.g, b.b, emissive = em)
        }

        for (i in floor.details.indices) {
            val b = floor.details[i]; val y = floor.detailY[i]
            val cx = (b.minX + b.maxX) / 2f; val cz = (b.minZ + b.maxZ) / 2f
            drawBox(cx, y[0] + y[1] / 2f, cz, b.maxX - b.minX, y[1], b.maxZ - b.minZ, b.r, b.g, b.b)
        }

        for (b in floor.furniture) {
            val cx = (b.minX + b.maxX) / 2f; val cz = (b.minZ + b.maxZ) / 2f
            drawBox(cx, 0.45f, cz, b.maxX - b.minX, 0.9f, b.maxZ - b.minZ, b.r, b.g, b.b)
        }

        for (h in floor.hideSpots) {
            val cx = (h.minX + h.maxX) / 2f; val cz = (h.minZ + h.maxZ) / 2f
            drawBox(cx, 0.42f, cz, h.maxX - h.minX, 0.85f, h.maxZ - h.minZ, h.r, h.g, h.b)
        }

        for (d in floor.doors) drawDoorPanel(d, DOOR_CLOSED, DOOR_OPEN)

        if (currentFloor == 1) {
            val closedCol = if (doorUnlocked) floatArrayOf(0.30f, 0.16f, 0.10f) else floatArrayOf(0.36f, 0.15f, 0.11f)
            val openCol = floatArrayOf(0.26f, 0.44f, 0.22f)
            val glow = if (doorUnlocked) floatArrayOf(0.05f, 0.13f, 0.05f) else ZERO_EMISSIVE
            drawDoorPanel(mainDoor, closedCol, openCol, glow)
        }

        for (k in floor.keySpots) {
            if (k.collected) continue
            val bob = 1.1f + sin(worldTime * 2f + k.x * 0.3f + k.z * 0.7f) * 0.08f
            drawBox(k.x, bob, k.z, 0.24f, 0.42f, 0.24f, 0.91f, 0.72f, 0.29f, rotY = worldTime * 1.6f + k.x)
        }

        if (currentFloor == 1) {
            val distToPlayer = hypot((stalkerX - playerX).toDouble(), (stalkerZ - playerZ).toDouble()).toFloat()
            val flicker = (0.55f + 0.25f * sin(worldTime * 16f) + 0.15f * sin(worldTime * 6.1f + 1.3f) +
                0.12f * sin(worldTime * 23f + 0.7f)).coerceIn(0.22f, 1f)
            val proximityGlow = (1.3f - distToPlayer * 0.055f).coerceIn(0.12f, 1f)
            val candle = flicker * proximityGlow
            val candleBody = floatArrayOf(0.85f * candle * 0.4f, 0.42f * candle * 0.4f, 0.12f * candle * 0.4f)
            val candleFlame = floatArrayOf(1f * candle, 0.55f * candle, 0.16f * candle)

            drawBox(stalkerX, 0.6f, stalkerZ, 0.5f, 1.15f, 0.5f, 0.06f, 0.05f, 0.05f, emissive = candleBody)
            drawBox(stalkerX, 1.55f, stalkerZ, 0.4f, 0.4f, 0.4f, 0.06f, 0.05f, 0.05f, emissive = candleBody)
            drawBox(stalkerX, 1.55f, stalkerZ - 0.28f, 0.11f, 0.16f, 0.11f, 1f, 0.6f, 0.2f, emissive = candleFlame)
        }
    }

    private fun drawBox(cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float,
                         r: Float, g: Float, b: Float, emissive: FloatArray = ZERO_EMISSIVE, rotY: Float = 0f) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cx, cy, cz)
        if (rotY != 0f) Matrix.rotateM(modelMatrix, 0, Math.toDegrees(rotY.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, w, h, d)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform4f(uColorLoc, r, g, b, 1f)
        GLES20.glUniform3f(uEmissiveLoc, emissive[0], emissive[1], emissive[2])
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CubeMesh.VERTEX_COUNT)
    }

    /** Draws a door panel hinged at (hingeX, hingeZ), swinging by d.angle. */
    private fun drawDoorPanel(d: Door, closed: FloatArray, open: FloatArray, glow: FloatArray = ZERO_EMISSIVE) {
        val col = if (d.isOpen) open else closed
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, d.hingeX, WALL_H * 0.47f, d.hingeZ)
        val baseYawDeg = if (d.axis == 'X') 0f else 90f
        val swingDeg = Math.toDegrees(d.angle.toDouble()).toFloat()
        Matrix.rotateM(modelMatrix, 0, baseYawDeg + swingDeg, 0f, 1f, 0f)
        Matrix.translateM(modelMatrix, 0, d.width / 2f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, d.width * 0.96f, WALL_H * 0.9f, THICK * 0.5f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform4f(uColorLoc, col[0], col[1], col[2], 1f)
        GLES20.glUniform3f(uEmissiveLoc, glow[0], glow[1], glow[2])
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CubeMesh.VERTEX_COUNT)
    }
}
