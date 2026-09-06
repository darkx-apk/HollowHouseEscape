package com.hollowhouse.escape

/**
 * A unit cube (corners at +-0.5) with per-face normals, expanded into
 * 36 non-indexed vertices (6 faces * 2 triangles * 3 verts).
 * Each vertex is interleaved as: posX, posY, posZ, normX, normY, normZ.
 */
object CubeMesh {

    val vertexData: FloatArray = buildCube()
    const val FLOATS_PER_VERTEX = 6
    const val VERTEX_COUNT = 36

    private class Face(val nx: Float, val ny: Float, val nz: Float, val corners: FloatArray)

    private fun buildCube(): FloatArray {
        val faces = arrayOf(
            // +Z
            Face(0f, 0f, 1f, floatArrayOf(-.5f, -.5f, .5f, -.5f, .5f, .5f, .5f, .5f, .5f, .5f, -.5f, .5f)),
            // -Z
            Face(0f, 0f, -1f, floatArrayOf(.5f, -.5f, -.5f, .5f, .5f, -.5f, -.5f, .5f, -.5f, -.5f, -.5f, -.5f)),
            // +X
            Face(1f, 0f, 0f, floatArrayOf(.5f, -.5f, .5f, .5f, .5f, .5f, .5f, .5f, -.5f, .5f, -.5f, -.5f)),
            // -X
            Face(-1f, 0f, 0f, floatArrayOf(-.5f, -.5f, -.5f, -.5f, .5f, -.5f, -.5f, .5f, .5f, -.5f, -.5f, .5f)),
            // +Y (top)
            Face(0f, 1f, 0f, floatArrayOf(-.5f, .5f, .5f, -.5f, .5f, -.5f, .5f, .5f, -.5f, .5f, .5f, .5f)),
            // -Y (bottom)
            Face(0f, -1f, 0f, floatArrayOf(-.5f, -.5f, -.5f, -.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, -.5f, -.5f))
        )

        val out = ArrayList<Float>(VERTEX_COUNT * FLOATS_PER_VERTEX)
        fun addVert(f: Face, i: Int) {
            out.add(f.corners[i * 3]); out.add(f.corners[i * 3 + 1]); out.add(f.corners[i * 3 + 2])
            out.add(f.nx); out.add(f.ny); out.add(f.nz)
        }
        for (f in faces) {
            addVert(f, 0); addVert(f, 1); addVert(f, 2)
            addVert(f, 0); addVert(f, 2); addVert(f, 3)
        }
        return out.toFloatArray()
    }
}
