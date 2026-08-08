package com.frameflow.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.util.UUID
import kotlin.math.hypot

const val FRAMEFLOW_FORMAT_VERSION = 2

enum class Tool { Brush, Eraser, SmartSelect, ColorRepeat }

enum class Part(val label: String) {
    None("None"),
    Body("Body"),
    Face("Face"),
    LeftArm("Left arm"),
    RightArm("Right arm"),
    LeftLeg("Left leg"),
    RightLeg("Right leg"),
    Background("Background"),
    Accessory("Accessory")
}

data class CanvasPoint(val x: Float, val y: Float)

data class BrushPreset(
    val name: String,
    val family: String,
    val width: Float,
    val alpha: Float
)

data class StrokeData(
    val id: String = UUID.randomUUID().toString(),
    val points: List<CanvasPoint>,
    val colorArgb: Int,
    val width: Float,
    val alpha: Float,
    val erase: Boolean
)

class LayerState(
    name: String,
    part: Part,
    strokes: List<StrokeData> = emptyList(),
    visible: Boolean = true,
    locked: Boolean = false
) {
    var name by mutableStateOf(name)
    var part by mutableStateOf(part)
    var visible by mutableStateOf(visible)
    var locked by mutableStateOf(locked)
    val strokes = mutableStateListOf<StrokeData>().apply { addAll(strokes) }

    fun cloneLayer() = LayerState(
        name = name,
        part = part,
        strokes = strokes.map { stroke -> stroke.copy(points = stroke.points.toList()) },
        visible = visible,
        locked = locked
    )
}

class FrameState(
    duration: Int = 1000,
    layers: List<LayerState> = defaultLayers()
) {
    var durationMs by mutableIntStateOf(duration)
    val layers = mutableStateListOf<LayerState>().apply { addAll(layers) }

    fun cloneFrame() = FrameState(
        duration = durationMs,
        layers = layers.map { it.cloneLayer() }
    )
}

class ProjectState(
    val id: String = UUID.randomUUID().toString(),
    name: String = "Untitled animation",
    canvasWidth: Int = 1080,
    canvasHeight: Int = 1080,
    backgroundArgb: Int = 0xFFFFFFFF.toInt(),
    frames: List<FrameState> = listOf(FrameState()),
    modified: Long = System.currentTimeMillis()
) {
    var name by mutableStateOf(name)
    var canvasWidth by mutableIntStateOf(canvasWidth)
    var canvasHeight by mutableIntStateOf(canvasHeight)
    var backgroundArgb by mutableIntStateOf(backgroundArgb)
    var modifiedAt by mutableLongStateOf(modified)
    var revision by mutableIntStateOf(0)
    val frames = mutableStateListOf<FrameState>().apply { addAll(frames.ifEmpty { listOf(FrameState()) }) }

    fun touch() {
        revision++
        modifiedAt = System.currentTimeMillis()
    }

    fun replaceFrom(other: ProjectState, markDirty: Boolean = true) {
        name = other.name
        canvasWidth = other.canvasWidth
        canvasHeight = other.canvasHeight
        backgroundArgb = other.backgroundArgb
        frames.clear()
        frames.addAll(other.frames.map { it.cloneFrame() })
        if (markDirty) touch()
    }
}

data class ProjectMeta(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val frameCount: Int,
    val width: Int,
    val height: Int
)

fun defaultLayers() = listOf(
    LayerState("Body", Part.Body),
    LayerState("Face", Part.Face),
    LayerState("Left arm", Part.LeftArm),
    LayerState("Right arm", Part.RightArm),
    LayerState("Left leg", Part.LeftLeg),
    LayerState("Right leg", Part.RightLeg),
    LayerState("Background", Part.Background)
)

val brushFamilies = listOf(
    "Ink", "Pencil", "Marker", "Paint", "Pixel", "Spray",
    "Chalk", "Calligraphy", "Highlighter", "Texture", "Crayon", "Airbrush"
)

val brushes = buildList {
    brushFamilies.forEachIndexed { familyIndex, family ->
        repeat(20) { variant ->
            add(
                BrushPreset(
                    name = "$family ${variant + 1}",
                    family = family,
                    width = 3f + (variant * 2.1f) + familyIndex,
                    alpha = (0.55f + (variant % 6) * 0.075f).coerceAtMost(1f)
                )
            )
        }
    }
}

val erasers = List(60) {
    BrushPreset("Eraser ${it + 1}", "Eraser", 8f + it * 2.2f, 1f)
}

class EditorState(val project: ProjectState) {
    var frameIndex by mutableIntStateOf(0)
    var layerIndex by mutableIntStateOf(0)
    var tool by mutableStateOf(Tool.Brush)
    var brush by mutableStateOf(brushes[8])
    var eraser by mutableStateOf(erasers[12])
    var hue by mutableFloatStateOf(220f)
    var saturation by mutableFloatStateOf(.72f)
    var value by mutableFloatStateOf(.88f)
    var alpha by mutableFloatStateOf(1f)
    var onionPrevious by mutableStateOf(true)
    var onionNext by mutableStateOf(false)
    var onionAlpha by mutableFloatStateOf(.18f)
    var playing by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()

    val frame: FrameState
        get() {
            ensureIndices()
            return project.frames[frameIndex]
        }

    val layer: LayerState
        get() {
            ensureIndices()
            return frame.layers[layerIndex]
        }

    val color: Color
        get() = Color.hsv(hue, saturation, value, alpha)

    fun ensureIndices() {
        if (project.frames.isEmpty()) project.frames.add(FrameState())
        frameIndex = frameIndex.coerceIn(project.frames.indices)
        if (project.frames[frameIndex].layers.isEmpty()) {
            project.frames[frameIndex].layers.add(LayerState("Layer 1", Part.None))
        }
        layerIndex = layerIndex.coerceIn(project.frames[frameIndex].layers.indices)
    }

    fun addBlankFrame() {
        val layers = frame.layers.map { LayerState(it.name, it.part, visible = it.visible, locked = it.locked) }
        project.frames.add(frameIndex + 1, FrameState(frame.durationMs, layers))
        frameIndex++
        layerIndex = layerIndex.coerceIn(project.frames[frameIndex].layers.indices)
        selectedIds.clear()
        project.touch()
    }

    fun cloneFrame() {
        project.frames.add(frameIndex + 1, frame.cloneFrame())
        frameIndex++
        selectedIds.clear()
        project.touch()
    }

    fun deleteFrame() {
        if (project.frames.size == 1) {
            project.frames[0] = FrameState()
            frameIndex = 0
        } else {
            project.frames.removeAt(frameIndex)
            frameIndex = frameIndex.coerceAtMost(project.frames.lastIndex)
        }
        layerIndex = 0
        selectedIds.clear()
        project.touch()
    }

    fun moveFrame(delta: Int) {
        val destination = (frameIndex + delta).coerceIn(project.frames.indices)
        if (destination == frameIndex) return
        val item = project.frames.removeAt(frameIndex)
        project.frames.add(destination, item)
        frameIndex = destination
        project.touch()
    }

    fun addLayer() {
        frame.layers.add(0, LayerState("Layer ${frame.layers.size + 1}", Part.None))
        layerIndex = 0
        project.touch()
    }

    fun duplicateLayer() {
        frame.layers.add(layerIndex, layer.cloneLayer().also { it.name = "${it.name} copy" })
        project.touch()
    }

    fun deleteLayer() {
        if (frame.layers.size == 1) {
            frame.layers[0].strokes.clear()
            frame.layers[0].name = "Layer 1"
            frame.layers[0].part = Part.None
        } else {
            frame.layers.removeAt(layerIndex)
            layerIndex = layerIndex.coerceAtMost(frame.layers.lastIndex)
        }
        selectedIds.clear()
        project.touch()
    }

    fun moveLayer(delta: Int) {
        val destination = (layerIndex + delta).coerceIn(frame.layers.indices)
        if (destination == layerIndex) return
        val item = frame.layers.removeAt(layerIndex)
        frame.layers.add(destination, item)
        layerIndex = destination
        project.touch()
    }

    fun selectWholePartAt(point: CanvasPoint) {
        val hit = frame.layers.asReversed().firstNotNullOfOrNull { candidateLayer ->
            if (!candidateLayer.visible) null
            else candidateLayer.strokes.asReversed().firstOrNull { hitStroke(it, point) }?.let { candidateLayer to it }
        } ?: run {
            selectedIds.clear()
            return
        }
        val targetPart = hit.first.part
        selectedIds.clear()
        if (targetPart != Part.None && targetPart != Part.Background) {
            frame.layers.filter { it.part == targetPart }
                .flatMap { it.strokes }
                .forEach { selectedIds.add(it.id) }
        } else {
            selectedIds.add(hit.second.id)
        }
    }

    fun selectColorAt(point: CanvasPoint) {
        val hit = frame.layers.asReversed().firstNotNullOfOrNull { candidateLayer ->
            candidateLayer.strokes.asReversed().firstOrNull { !it.erase && hitStroke(it, point) }
        } ?: run {
            selectedIds.clear()
            return
        }
        selectedIds.clear()
        frame.layers.flatMap { it.strokes }
            .filter { !it.erase && colorDistance(it.colorArgb, hit.colorArgb) < .08f }
            .forEach { selectedIds.add(it.id) }
    }

    fun moveSelection(dx: Float, dy: Float) {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        frame.layers.forEach { candidateLayer ->
            for (index in candidateLayer.strokes.indices) {
                val stroke = candidateLayer.strokes[index]
                if (stroke.id in ids) {
                    candidateLayer.strokes[index] = stroke.copy(
                        points = stroke.points.map { CanvasPoint(it.x + dx, it.y + dy) }
                    )
                }
            }
        }
        project.touch()
    }

    fun deleteSelection() {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        frame.layers.forEach { candidateLayer ->
            candidateLayer.strokes.removeAll { it.id in ids }
        }
        selectedIds.clear()
        project.touch()
    }
}

fun hitStroke(stroke: StrokeData, point: CanvasPoint): Boolean {
    if (stroke.points.isEmpty()) return false
    val threshold = (stroke.width * 1.6f).coerceAtLeast(18f)
    if (stroke.points.size == 1) {
        return hypot(stroke.points[0].x - point.x, stroke.points[0].y - point.y) <= threshold
    }
    for (index in 0 until stroke.points.lastIndex) {
        if (distanceToSegment(point, stroke.points[index], stroke.points[index + 1]) <= threshold) return true
    }
    return false
}

private fun distanceToSegment(point: CanvasPoint, a: CanvasPoint, b: CanvasPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    if (dx == 0f && dy == 0f) return hypot(point.x - a.x, point.y - a.y)
    val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    val px = a.x + t * dx
    val py = a.y + t * dy
    return hypot(point.x - px, point.y - py)
}

fun colorDistance(a: Int, b: Int): Float {
    val ar = ((a shr 16) and 0xFF) / 255f
    val ag = ((a shr 8) and 0xFF) / 255f
    val ab = (a and 0xFF) / 255f
    val br = ((b shr 16) and 0xFF) / 255f
    val bg = ((b shr 8) and 0xFF) / 255f
    val bb = (b and 0xFF) / 255f
    return kotlin.math.sqrt((ar - br) * (ar - br) + (ag - bg) * (ag - bg) + (ab - bb) * (ab - bb))
}
