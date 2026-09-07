package com.hollowhouse.escape

/** Shared world-scale constants used by both the level layouts and the renderer. */
object Metrics {
    const val HX = 14f
    const val HZ = 10f
    const val WALL_H = 3.2f
    const val THICK = 0.35f
    const val DOOR_W = 1.1f
}

/** An axis-aligned box in the XZ ground plane. Used for walls, furniture, windows.
 *  [glow] marks a window pane so the renderer can give it its own emissive look. */
data class Box(
    val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float,
    val r: Float, val g: Float, val b: Float, val glow: Boolean = false
)

/** A swinging door. It lives in a doorway [width] wide, hinged at (hingeX, hingeZ).
 *  [axis] says which wall the doorway sits in: 'X' = a wall running along X
 *  (drawn with segX), 'Z' = a wall running along Z (drawn with segZ). The panel
 *  spans from the hinge to hinge + width along that axis when closed. */
class Door(
    val id: String,
    val hingeX: Float, val hingeZ: Float,
    val width: Float,
    val axis: Char
) {
    @Volatile var isOpen = false
    @Volatile var angle = 0f // current swing angle in radians, animated toward target
}

/** A crawl-under spot (bed / table / desk). While the player crouches inside
 *  these bounds, the furniture box itself stops blocking movement and the
 *  stalker can no longer see them. */
data class HideSpot(
    val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float,
    val r: Float, val g: Float, val b: Float, val label: String
) {
    fun contains(x: Float, z: Float) = x in minX..maxX && z in minZ..maxZ
    fun toBox() = Box(minX, maxX, minZ, maxZ, r, g, b)
}

/** A point the fragment shader treats as a soft, always-on moonlight source —
 *  placed at each window so light visibly pools on the floor near it. */
data class WindowGlow(val x: Float, val y: Float, val z: Float)

class KeySpot(val x: Float, val z: Float) {
    @Volatile var collected = false
}

class StairTrigger(
    val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float,
    val landingX: Float, val landingZ: Float, val targetFloor: Int, val label: String
) {
    fun contains(x: Float, z: Float) = x in minX..maxX && z in minZ..maxZ
}

/** Everything needed to simulate and render one floor of the house. */
class FloorData {
    val walls = ArrayList<Box>()
    val details = ArrayList<Box>()        // baseboard / trim footprints
    val detailY = ArrayList<FloatArray>() // [centerY, height], parallel to `details`
    val furniture = ArrayList<Box>()
    val hideSpots = ArrayList<HideSpot>()
    val windows = ArrayList<WindowGlow>()
    val keySpots = ArrayList<KeySpot>()
    val doors = ArrayList<Door>()
    val stairs = ArrayList<StairTrigger>()
}

/**
 * Builds the two floors from the sketch:
 *  Floor 1 — front door, a south hallway with a bathroom nook, and a north
 *            wing of four cells off a corridor: Cellar | Room | Room | Upstairs.
 *  Floor 2 — a landing at the top of the stairs, a short corridor down into
 *            a big bedroom (bed + desk you can hide under), with a closet
 *            carved out of its west corner.
 */
object LevelBuilder {
    private val OUTER = floatArrayOf(0.20f, 0.17f, 0.14f)
    private val INNER = floatArrayOf(0.17f, 0.14f, 0.12f)
    private val FURN = floatArrayOf(0.12f, 0.10f, 0.08f)
    private val BED = floatArrayOf(0.24f, 0.10f, 0.10f)
    private val DESK = floatArrayOf(0.16f, 0.11f, 0.07f)
    private val GLASS = floatArrayOf(0.45f, 0.52f, 0.62f)

    private fun segX(f: FloorData, z: Float, x1: Float, x2: Float, c: FloatArray = INNER) {
        if (x2 <= x1) return
        f.walls.add(Box(x1, x2, z - Metrics.THICK / 2f, z + Metrics.THICK / 2f, c[0], c[1], c[2]))
    }
    private fun segZ(f: FloorData, x: Float, z1: Float, z2: Float, c: FloatArray = INNER) {
        if (z2 <= z1) return
        f.walls.add(Box(x - Metrics.THICK / 2f, x + Metrics.THICK / 2f, z1, z2, c[0], c[1], c[2]))
    }
    private fun windowX(f: FloorData, z: Float, cx: Float, w: Float) {
        f.walls.add(Box(cx - w / 2f, cx + w / 2f, z - Metrics.THICK / 2f, z + Metrics.THICK / 2f, GLASS[0], GLASS[1], GLASS[2], glow = true))
        f.windows.add(WindowGlow(cx, Metrics.WALL_H * 0.55f, z))
    }
    private fun windowZ(f: FloorData, x: Float, cz: Float, d: Float) {
        f.walls.add(Box(x - Metrics.THICK / 2f, x + Metrics.THICK / 2f, cz - d / 2f, cz + d / 2f, GLASS[0], GLASS[1], GLASS[2], glow = true))
        f.windows.add(WindowGlow(x, Metrics.WALL_H * 0.55f, cz))
    }
    private fun furn(f: FloorData, cx: Float, cz: Float, w: Float, d: Float, c: FloatArray = FURN) {
        f.furniture.add(Box(cx - w / 2f, cx + w / 2f, cz - d / 2f, cz + d / 2f, c[0], c[1], c[2]))
    }
    private fun hide(f: FloorData, cx: Float, cz: Float, w: Float, d: Float, c: FloatArray, label: String) {
        f.hideSpots.add(HideSpot(cx - w / 2f, cx + w / 2f, cz - d / 2f, cz + d / 2f, c[0], c[1], c[2], label))
    }
    private fun key(f: FloorData, x: Float, z: Float) { f.keySpots.add(KeySpot(x, z)) }
    private fun door(f: FloorData, id: String, hingeX: Float, hingeZ: Float, axis: Char, width: Float = Metrics.DOOR_W) {
        f.doors.add(Door(id, hingeX, hingeZ, width, axis))
    }
    private fun stairs(f: FloorData, minX: Float, maxX: Float, minZ: Float, maxZ: Float,
                        landX: Float, landZ: Float, target: Int, label: String) {
        f.stairs.add(StairTrigger(minX, maxX, minZ, maxZ, landX, landZ, target, label))
    }

    /** Cosmetic baseboard + top trim added on top of every plain wall segment,
     *  so walls read as built rooms instead of flat slabs. */
    private fun addTrim(f: FloorData) {
        val base = floatArrayOf(0.09f, 0.07f, 0.06f)
        val top = floatArrayOf(0.24f, 0.21f, 0.17f)
        val H = Metrics.WALL_H
        val plain = ArrayList(f.walls)
        for (b in plain) {
            if (b.glow) continue // windows keep a clean glass edge, no trim
            f.details.add(Box(b.minX - 0.03f, b.maxX + 0.03f, b.minZ - 0.03f, b.maxZ + 0.03f, base[0], base[1], base[2]))
            f.detailY.add(floatArrayOf(0.09f, 0.18f))
            f.details.add(Box(b.minX - 0.02f, b.maxX + 0.02f, b.minZ - 0.02f, b.maxZ + 0.02f, top[0], top[1], top[2]))
            f.detailY.add(floatArrayOf(H - 0.16f, 0.2f))
        }
    }

    fun buildFloor1(): FloorData {
        val f = FloorData()
        val HX = Metrics.HX; val HZ = Metrics.HZ

        // outer shell — front door gap on the south wall, one window in each side wall
        segX(f, HZ, -HX, -1.3f, OUTER)
        segX(f, HZ, 1.3f, HX, OUTER)
        segX(f, -HZ, -HX, HX, OUTER)
        segZ(f, -HX, -HZ, 4.4f, OUTER); windowZ(f, -HX, 5.0f, 1.2f); segZ(f, -HX, 5.6f, HZ, OUTER)
        segZ(f, HX, -HZ, 4.4f, OUTER); windowZ(f, HX, 5.0f, 1.2f); segZ(f, HX, 5.6f, HZ, OUTER)

        // corridor wall dividing the north wing (Cellar | Room | Room | Upstairs)
        // from the south hallway — a door into each of the 4 cells
        segX(f, -2f, -HX, -11.05f)
        door(f, "d_cellar", -11.05f, -2f, 'X')
        segX(f, -2f, -9.95f, -4.05f)
        door(f, "d_roomA", -4.05f, -2f, 'X')
        segX(f, -2f, -2.95f, 2.95f)
        door(f, "d_roomB", 2.95f, -2f, 'X')
        segX(f, -2f, 4.05f, 9.95f)
        door(f, "d_upstairs", 9.95f, -2f, 'X')
        segX(f, -2f, 11.05f, HX)

        // dividers between the 4 north cells (no doors between rooms, only from the corridor)
        segZ(f, -7f, -HZ, -2f)
        segZ(f, 0f, -HZ, -2f)
        segZ(f, 7f, -HZ, -2f)

        // Cellar cell — sealed stairs down, one key
        furn(f, -10.5f, -9.2f, 1.8f, 0.6f)
        key(f, -10.5f, -6f)

        // Room A — a bed you can crouch under, one key
        hide(f, -3.5f, -8f, 2.6f, 1.6f, BED, "Bed")
        furn(f, -1.9f, -8f, 0.5f, 0.5f)
        key(f, -3.5f, -6f)

        // Room B — a desk you can crouch under, one key
        hide(f, 3.5f, -8f, 1.6f, 1.0f, DESK, "Desk")
        key(f, 3.5f, -6f)

        // Upstairs alcove — walking onto the stairs sends you to floor 2's landing
        furn(f, 11f, -8.5f, 2.6f, 0.4f)
        stairs(f, 9f, 13f, -9.5f, -7.5f, 0f, -8.5f, 2, "Stairs Up")

        // Bathroom nook off the south hallway
        segX(f, 2f, -HX, -10f)
        segZ(f, -10f, -2f, -0.55f)
        door(f, "d_bath", -10f, -0.55f, 'Z')
        segZ(f, -10f, 0.55f, 2f)
        furn(f, -12.4f, 0.4f, 1.6f, 1.0f)

        // hallway dressing
        furn(f, 6f, 8f, 1.4f, 0.6f)

        addTrim(f)
        return f
    }

    fun buildFloor2(): FloorData {
        val f = FloorData()

        // landing room at the top of the stairs
        segX(f, -10f, -4f, 4f, OUTER)
        segZ(f, -4f, -10f, -6.4f, OUTER)
        segZ(f, 4f, -10f, -6.4f, OUTER)
        segX(f, -6.4f, -4f, -0.55f)
        door(f, "d_landing", -0.55f, -6.4f, 'X')
        segX(f, -6.4f, 0.55f, 4f)
        stairs(f, -1.5f, 1.5f, -9.5f, -8f, 11f, -8.5f, 1, "Stairs Down")
        furn(f, 2.5f, -8f, 1.0f, 1.0f)
        key(f, -2.5f, -8f)

        // narrow corridor connecting the landing to the bedroom wing
        segZ(f, -1f, -6.4f, -2f)
        segZ(f, 1f, -6.4f, -2f)
        segX(f, -2f, -14f, -0.55f)
        door(f, "d_corridor", -0.55f, -2f, 'X')
        segX(f, -2f, 0.55f, 14f)

        // the big bedroom — two windows on the east wall throw moonlight far across the room
        segZ(f, -14f, -2f, -0.6f, OUTER); windowZ(f, -14f, 0f, 1.2f); segZ(f, -14f, 0.6f, 10f, OUTER)
        segZ(f, 14f, -2f, 0.4f, OUTER); windowZ(f, 14f, 1f, 1.2f); segZ(f, 14f, 1.6f, 6.4f, OUTER); windowZ(f, 14f, 7f, 1.2f); segZ(f, 14f, 7.6f, 10f, OUTER)
        segX(f, 10f, -14f, 14f, OUTER)

        hide(f, -3f, 4f, 1.6f, 1.2f, DESK, "Desk")
        hide(f, 9f, 6f, 4.4f, 2.6f, BED, "Bed")
        furn(f, 6.3f, 6f, 0.5f, 0.5f)
        furn(f, 11.7f, 6f, 0.5f, 0.5f)
        furn(f, 12.5f, 2.0f, 1.4f, 0.8f)
        key(f, 12.5f, 1.2f)

        // closet carved out of the west corner of the bedroom
        segX(f, 3f, -14f, -9f)
        segZ(f, -9f, 3f, 5.95f)
        door(f, "d_closet", -9f, 5.95f, 'Z')
        segZ(f, -9f, 7.05f, 10f)
        furn(f, -11.5f, 4.5f, 1.2f, 1.0f)
        key(f, -11.5f, 7.5f)

        addTrim(f)
        return f
    }
}
