package com.abandonsearch.hazardgrid.ui.map

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.core.graphics.PathParser
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.abandonsearch.hazardgrid.ui.map.NativeHazardShapes.registerShapes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val SHAPE_VERTEX_SAMPLES = 96
private const val SHAPE_LIBRARY_VERSION = 1

internal data class ShapeEntry(
    val id: Int,
    val name: String,
    val vertices: FloatArray,
)

internal object ShapeLibrary {
    private val entries: List<ShapeEntry> by lazy { buildEntries() }
    private val entryMap by lazy { entries.associateBy { it.id } }
    private var registered = false
    private val arrowId by lazy { entries.firstOrNull()?.id ?: 0 }

    fun ensureRegistered() {
        if (!registered) {
            val buffer = encode(entries)
            registerShapes(buffer, buffer.limit())
            registered = true
        }
    }

    fun randomEntry(random: kotlin.random.Random): ShapeEntry =
        entries[random.nextInt(entries.size)]

    fun entryById(id: Int): ShapeEntry? = entryMap[id]

    fun entriesList(): List<ShapeEntry> = entries

    fun arrowShapeId(): Int = arrowId

    private fun buildEntries(): List<ShapeEntry> {
        val list = mutableListOf<ShapeEntry>()
        var nextId = 1
        materialShapes.forEach { polygon ->
            list += ShapeEntry(
                id = nextId++,
                name = "material-$nextId",
                vertices = sampleRoundedPolygon(polygon),
            )
        }
        svgDefinitions.forEach { svg ->
            list += ShapeEntry(
                id = nextId++,
                name = svg.name,
                vertices = sampleSvgPath(svg.pathData),
            )
        }
        return list
    }

    private fun encode(entries: List<ShapeEntry>): ByteBuffer {
        val floats = entries.sumOf { it.vertices.size }
        val capacity = Int.SIZE_BYTES * 2 + entries.size * (Int.SIZE_BYTES * 2) + floats * java.lang.Float.BYTES
        val buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(SHAPE_LIBRARY_VERSION)
        buffer.putInt(entries.size)
        entries.forEach { entry ->
            buffer.putInt(entry.id)
            val vertexCount = entry.vertices.size / 2
            buffer.putInt(vertexCount)
            entry.vertices.forEach { buffer.putFloat(it) }
        }
        buffer.flip()
        return buffer
    }

    private fun sampleRoundedPolygon(polygon: RoundedPolygon): FloatArray {
        val path = Path()
        polygon.toPath(path)
        return samplePath(path)
    }

    private fun sampleSvgPath(pathData: String): FloatArray {
        val path = PathParser.createPathFromPathData(pathData) ?: Path()
        return samplePath(path)
    }

    private fun samplePath(path: Path): FloatArray {
        val measure = PathMeasure(path, true)
        val totalLength = computeTotalLength(measure)
        val step = if (totalLength <= 0f) 0f else totalLength / SHAPE_VERTEX_SAMPLES
        val coords = FloatArray(2)
        val result = FloatArray(SHAPE_VERTEX_SAMPLES * 2)
        var distance = 0f
        var index = 0
        for (i in 0 until SHAPE_VERTEX_SAMPLES) {
            if (!measure.getPosTan(distance.coerceAtMost(totalLength), coords, null)) {
                measure.nextContour()
                distance = 0f
                if (!measure.getPosTan(distance, coords, null)) {
                    coords[0] = 0f
                    coords[1] = 0f
                }
            }
            result[index++] = coords[0]
            result[index++] = coords[1]
            distance += step
        }
        return normalizeVertices(result)
    }

    private fun computeTotalLength(measure: PathMeasure): Float {
        var length = 0f
        do {
            length += measure.length
        } while (measure.nextContour())
        return length
    }

    private fun normalizeVertices(vertices: FloatArray): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in vertices.indices step 2) {
            val x = vertices[i]
            val y = vertices[i + 1]
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        var maxRadius = 0f
        for (i in vertices.indices step 2) {
            val x = vertices[i] - centerX
            val y = vertices[i + 1] - centerY
            val r = sqrt(x * x + y * y)
            if (r > maxRadius) {
                maxRadius = r
            }
        }
        val scale = if (maxRadius <= 0f) 1f else 1f / maxRadius
        for (i in vertices.indices step 2) {
            vertices[i] = (vertices[i] - centerX) * scale
            vertices[i + 1] = (vertices[i + 1] - centerY) * scale
        }
        return vertices
    }
}

private data class SvgDefinition(
    val name: String,
    val pathData: String,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val materialShapes = listOf(
    MaterialShapes.Arrow,
    MaterialShapes.Circle,
    MaterialShapes.Square,
    MaterialShapes.Slanted,
    MaterialShapes.Arch,
    MaterialShapes.Oval,
    MaterialShapes.Pill,
    MaterialShapes.Triangle,
    MaterialShapes.Diamond,
    MaterialShapes.ClamShell,
    MaterialShapes.Pentagon,
    MaterialShapes.Gem,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Cookie7Sided,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Clover8Leaf,
    MaterialShapes.Flower,
    MaterialShapes.Ghostish,
    MaterialShapes.Bun,
)

private val svgDefinitions = listOf(
    SvgDefinition(
        name = "svg-radiation",
        pathData = "M0,-100 C55,-100 100,-55 100,0 C100,55 55,100 0,100 C-55,100 -100,55 -100,0 C-100,-55 -55,-100 0,-100 Z " +
            "M0,-20 L35,-60 A70,70 0 0,0 -35,-60 Z " +
            "M60,10 L20,35 A70,70 0 0,0 60,-10 Z " +
            "M-60,10 L-20,35 A70,70 0 0,1 -60,-10 Z " +
            "M0,-5 A5,5 0 1,1 0,5 A5,5 0 1,1 0,-5 Z",
    ),
)
