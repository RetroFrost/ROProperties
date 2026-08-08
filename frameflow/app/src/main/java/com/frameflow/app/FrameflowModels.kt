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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val FRAMEFLOW_FORMAT_VERSION = 3

enum class Tool { Brush, Eraser, SmartSelect, ColorRepeat }

enum class ProjectMode(val label: String) {
    Static("Static animation"),
    Auto("Auto animation"),
    ObjectShow("Object show")
}

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
    locked: Boolean = false,
    opacity: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    rotationDeg: Float = 0f,
    rasterPngBase64: String? = null,
    rasterName: String? = null
) {
    var name by mutableStateOf(name)
    var part by mutableStateOf(part)
    var visible by mutableStateOf(visible)
    var locked by mutableStateOf(locked)
    var opacity by mutableFloatStateOf(opacity.coerceIn(0f, 1f))
    var offsetX by mutableFloatStateOf(offsetX.finiteOr(0f))
    var offsetY by mutableFloatStateOf(offsetY.finiteOr(0f))
    var scaleX by mutableFloatStateOf(scaleX.finiteOr(1f).coerceIn(-20f, 20f))
    var scaleY by mutableFloatStateOf(scaleY.finiteOr(1f).coerceIn(-20f, 20f))
    var rotationDeg by mutableFloatStateOf(rotationDeg.finiteOr(0f))
    var rasterPngBase64 by mutableStateOf(rasterPngBase64)
    var rasterName by mutableStateOf(rasterName)
    val strokes = mutableStateListOf<StrokeData>().apply { addAll(strokes) }

    val hasRaster: Boolean get() = !rasterPngBase64.isNullOrBlank()

    fun cloneLayer() = LayerState(
        name = name,
        part = part,
        strokes = strokes.map { stroke -> stroke.copy(points = stroke.points.toList()) },
        visible = visible,
        locked = locked,
        opacity = opacity,
        offsetX = offsetX,
        offsetY = offsetY,
        scaleX = scaleX,
        scaleY = scaleY,
        rotationDeg = rotationDeg,
        rasterPngBase64 = rasterPngBase64,
        rasterName = rasterName
    )
}

class FrameState(
    duration: Int = 1000,
    layers: List<LayerState> = defaultLayers(),
    label: String = "",
    cameraX: Float = 0f,
    cameraY: Float = 0f,
    cameraZoom: Float = 1f,
    cameraRotation: Float = 0f
) {
    var durationMs by mutableIntStateOf(duration.coerceIn(50, 120000))
    var label by mutableStateOf(label)
    var cameraX by mutableFloatStateOf(cameraX.finiteOr(0f))
    var cameraY by mutableFloatStateOf(cameraY.finiteOr(0f))
    var cameraZoom by mutableFloatStateOf(cameraZoom.finiteOr(1f).coerceIn(.05f, 20f))
    var cameraRotation by mutableFloatStateOf(cameraRotation.finiteOr(0f))
    val layers = mutableStateListOf<LayerState>().apply { addAll(layers.ifEmpty { listOf(LayerState("Layer 1", Part.None)) }) }

    fun cloneFrame() = FrameState(
        duration = durationMs,
        layers = layers.map { it.cloneLayer() },
        label = label,
        cameraX = cameraX,
        cameraY = cameraY,
        cameraZoom = cameraZoom,
        cameraRotation = cameraRotation
    )
}

class ProjectState(
    val id: String = UUID.randomUUID().toString(),
    name: String = "Untitled animation",
    canvasWidth: Int = 1080,
    canvasHeight: Int = 1080,
    backgroundArgb: Int = 0xFFFFFFFF.toInt(),
    frames: List<FrameState> = listOf(FrameState()),
    modified: Long = System.currentTimeMillis(),
    mode: ProjectMode = ProjectMode.Static,
    fps: Int = 30,
    loopPlayback: Boolean = true,
    snapMs: Int = 100,
    audioFileName: String? = null,
    audioOffsetMs: Int = 0,
    audioVolume: Float = 1f
) {
    var name by mutableStateOf(name.ifBlank { "Untitled animation" })
    var canvasWidth by mutableIntStateOf(canvasWidth.coerceIn(16, 8192))
    var canvasHeight by mutableIntStateOf(canvasHeight.coerceIn(16, 8192))
    var backgroundArgb by mutableIntStateOf(backgroundArgb)
    var modifiedAt by mutableLongStateOf(modified)
    var revision by mutableIntStateOf(0)
    var activeFrameIndex by mutableIntStateOf(0)
    var mode by mutableStateOf(mode)
    var fps by mutableIntStateOf(fps.coerceIn(1, 60))
    var loopPlayback by mutableStateOf(loopPlayback)
    var snapMs by mutableIntStateOf(snapMs.coerceIn(10, 5000))
    var audioFileName by mutableStateOf(audioFileName)
    var audioOffsetMs by mutableIntStateOf(audioOffsetMs.coerceIn(-3_600_000, 3_600_000))
    var audioVolume by mutableFloatStateOf(audioVolume.finiteOr(1f).coerceIn(0f, 1f))
    val frames = mutableStateListOf<FrameState>().apply { addAll(frames.ifEmpty { listOf(FrameState()) }) }

    val totalDurationMs: Int get() = frames.fold(0L) { acc, frame -> (acc + frame.durationMs).coerceAtMost(Int.MAX_VALUE.toLong()) }.toInt()

    fun touch() {
        revision = if (revision == Int.MAX_VALUE) 0 else revision + 1
        modifiedAt = System.currentTimeMillis()
    }

    fun replaceFrom(other: ProjectState, markDirty: Boolean = true) {
        name = other.name.ifBlank { "Untitled animation" }
        canvasWidth = other.canvasWidth.coerceIn(16, 8192)
        canvasHeight = other.canvasHeight.coerceIn(16, 8192)
        backgroundArgb = other.backgroundArgb
        mode = other.mode
        fps = other.fps.coerceIn(1, 60)
        loopPlayback = other.loopPlayback
        snapMs = other.snapMs.coerceIn(10, 5000)
        audioFileName = other.audioFileName
        audioOffsetMs = other.audioOffsetMs.coerceIn(-3_600_000, 3_600_000)
        audioVolume = other.audioVolume.finiteOr(1f).coerceIn(0f, 1f)
        frames.clear()
        frames.addAll(other.frames.ifEmpty { listOf(FrameState()) }.map { it.cloneFrame() })
        activeFrameIndex = other.activeFrameIndex.coerceIn(frames.indices)
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
    private var frameIndexState by mutableIntStateOf(project.activeFrameIndex.coerceIn(project.frames.indices))
    var frameIndex: Int
        get() = frameIndexState
        set(value) {
            val safe = if (project.frames.isEmpty()) 0 else value.coerceIn(project.frames.indices)
            frameIndexState = safe
            project.activeFrameIndex = safe
        }
    var layerIndex by mutableIntStateOf(0)
    var tool by mutableStateOf(Tool.Brush)
    var brush by mutableStateOf(brushes.getOrElse(8) { BrushPreset("Ink", "Ink", 8f, 1f) })
    var eraser by mutableStateOf(erasers.getOrElse(12) { BrushPreset("Eraser", "Eraser", 24f, 1f) })
    var hue by mutableFloatStateOf(220f)
    var saturation by mutableFloatStateOf(.72f)
    var value by mutableFloatStateOf(.88f)
    var alpha by mutableFloatStateOf(1f)
    var onionPrevious by mutableStateOf(true)
    var onionNext by mutableStateOf(false)
    var onionAlpha by mutableFloatStateOf(.18f)
    var playing by mutableStateOf(false)
    var selectedRasterLayerIndex by mutableIntStateOf(-1)
    val selectedIds = mutableStateListOf<String>()

    val frame: FrameState
        get() {
            ensureIndices()
            return project.frames[frameIndexState]
        }

    val layer: LayerState
        get() {
            ensureIndices()
            return project.frames[frameIndexState].layers[layerIndex]
        }

    val color: Color
        get() = Color.hsv(
            hue.finiteOr(0f).coerceIn(0f, 360f),
            saturation.finiteOr(0f).coerceIn(0f, 1f),
            value.finiteOr(0f).coerceIn(0f, 1f),
            alpha.finiteOr(1f).coerceIn(0f, 1f)
        )

    /** Never call frame/layer properties from here: they call ensureIndices(). */
    fun ensureIndices() {
        if (project.frames.isEmpty()) project.frames.add(FrameState())
        val safeFrame = frameIndexState.coerceIn(project.frames.indices)
        if (safeFrame != frameIndexState) frameIndexState = safeFrame
        project.activeFrameIndex = safeFrame
        val currentFrame = project.frames[safeFrame]
        if (currentFrame.layers.isEmpty()) currentFrame.layers.add(LayerState("Layer 1", Part.None))
        layerIndex = layerIndex.coerceIn(currentFrame.layers.indices)
        if (selectedRasterLayerIndex !in currentFrame.layers.indices) selectedRasterLayerIndex = -1
    }

    fun addBlankFrame() {
        ensureIndices()
        val current = project.frames[frameIndexState]
        val layers = current.layers.map { LayerState(it.name, it.part, visible = it.visible, locked = it.locked) }
        project.frames.add(frameIndexState + 1, FrameState(current.durationMs, layers))
        frameIndex = frameIndexState + 1
        layerIndex = layerIndex.coerceIn(project.frames[frameIndexState].layers.indices)
        clearSelection()
        project.touch()
    }

    fun cloneFrame() {
        ensureIndices()
        project.frames.add(frameIndexState + 1, project.frames[frameIndexState].cloneFrame())
        frameIndex = frameIndexState + 1
        clearSelection()
        project.touch()
    }

    fun deleteFrame() {
        ensureIndices()
        if (project.frames.size == 1) {
            project.frames[0] = FrameState()
            frameIndex = 0
        } else {
            project.frames.removeAt(frameIndexState)
            frameIndex = frameIndexState.coerceAtMost(project.frames.lastIndex)
        }
        layerIndex = 0
        clearSelection()
        project.touch()
    }

    fun moveFrame(delta: Int) {
        ensureIndices()
        val destination = (frameIndexState + delta).coerceIn(project.frames.indices)
        if (destination == frameIndexState) return
        val item = project.frames.removeAt(frameIndexState)
        project.frames.add(destination, item)
        frameIndex = destination
        project.touch()
    }

    fun addLayer() {
        ensureIndices()
        project.frames[frameIndexState].layers.add(0, LayerState("Layer ${project.frames[frameIndexState].layers.size + 1}", Part.None))
        layerIndex = 0
        clearSelection()
        project.touch()
    }

    fun addRasterLayer(base64Png: String, name: String, part: Part = Part.None) {
        ensureIndices()
        project.frames[frameIndexState].layers.add(0, LayerState(name.ifBlank { "Imported image" }, part, rasterPngBase64 = base64Png, rasterName = name))
        layerIndex = 0
        selectedRasterLayerIndex = 0
        selectedIds.clear()
        project.touch()
    }

    fun duplicateLayer() {
        ensureIndices()
        val current = project.frames[frameIndexState]
        val source = current.layers[layerIndex]
        current.layers.add(layerIndex, source.cloneLayer().also { it.name = "${it.name} copy" })
        project.touch()
    }

    fun deleteLayer() {
        ensureIndices()
        val current = project.frames[frameIndexState]
        if (current.layers.size == 1) {
            current.layers[0].strokes.clear()
            current.layers[0].rasterPngBase64 = null
            current.layers[0].name = "Layer 1"
            current.layers[0].part = Part.None
        } else {
            current.layers.removeAt(layerIndex)
            layerIndex = layerIndex.coerceAtMost(current.layers.lastIndex)
        }
        clearSelection()
        project.touch()
    }

    fun moveLayer(delta: Int) {
        ensureIndices()
        val current = project.frames[frameIndexState]
        val destination = (layerIndex + delta).coerceIn(current.layers.indices)
        if (destination == layerIndex) return
        val oldSelectedLayer = if (selectedRasterLayerIndex in current.layers.indices) current.layers[selectedRasterLayerIndex] else null
        val item = current.layers.removeAt(layerIndex)
        current.layers.add(destination, item)
        layerIndex = destination
        selectedRasterLayerIndex = oldSelectedLayer?.let { current.layers.indexOf(it) } ?: -1
        project.touch()
    }

    fun clearSelection() {
        selectedIds.clear()
        selectedRasterLayerIndex = -1
    }

    fun selectLayer(index: Int) {
        ensureIndices()
        val current = project.frames[frameIndexState]
        if (index !in current.layers.indices) return
        layerIndex = index
        selectedIds.clear()
        selectedRasterLayerIndex = if (current.layers[index].hasRaster) index else -1
    }

    fun selectWholePartAt(point: CanvasPoint) {
        ensureIndices()
        val current = project.frames[frameIndexState]
        val hit = current.layers.asReversed().firstNotNullOfOrNull { candidateLayer ->
            if (!candidateLayer.visible) null
            else candidateLayer.strokes.asReversed().firstOrNull { hitStroke(it, point) }?.let { candidateLayer to it }
        } ?: run {
            clearSelection()
            return
        }
        val targetPart = hit.first.part
        selectedIds.clear()
        selectedRasterLayerIndex = -1
        if (targetPart != Part.None && targetPart != Part.Background) {
            current.layers.filter { it.part == targetPart }.flatMap { it.strokes }.forEach { selectedIds.add(it.id) }
        } else selectedIds.add(hit.second.id)
    }

    fun selectColorAt(point: CanvasPoint) {
        ensureIndices()
        val current = project.frames[frameIndexState]
        val hit = current.layers.asReversed().firstNotNullOfOrNull { candidateLayer ->
            candidateLayer.strokes.asReversed().firstOrNull { !it.erase && hitStroke(it, point) }
        } ?: run {
            clearSelection()
            return
        }
        selectedIds.clear()
        selectedRasterLayerIndex = -1
        current.layers.flatMap { it.strokes }
            .filter { !it.erase && colorDistance(it.colorArgb, hit.colorArgb) < .08f }
            .forEach { selectedIds.add(it.id) }
    }

    fun moveSelection(dx: Float, dy: Float) {
        ensureIndices()
        val current = project.frames[frameIndexState]
        val safeDx = dx.finiteOr(0f)
        val safeDy = dy.finiteOr(0f)
        if (selectedRasterLayerIndex in current.layers.indices) {
            val selected = current.layers[selectedRasterLayerIndex]
            selected.offsetX = (selected.offsetX + safeDx).finiteOr(0f)
            selected.offsetY = (selected.offsetY + safeDy).finiteOr(0f)
            project.touch()
            return
        }
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        current.layers.forEach { candidateLayer ->
            for (index in candidateLayer.strokes.indices) {
                val stroke = candidateLayer.strokes[index]
                if (stroke.id in ids) candidateLayer.strokes[index] = stroke.copy(points = stroke.points.map { CanvasPoint((it.x + safeDx).finiteOr(it.x), (it.y + safeDy).finiteOr(it.y)) })
            }
        }
        project.touch()
    }

    fun rotateSelection(degrees: Float) = transformVectorSelection(rotation = degrees)
    fun scaleSelection(scale: Float) = transformVectorSelection(scaleX = scale, scaleY = scale)
    fun flipSelectionHorizontal() = transformVectorSelection(scaleX = -1f, scaleY = 1f)

    fun transformSelectedRaster(dx: Float = 0f, dy: Float = 0f, scale: Float = 1f, rotation: Float = 0f) {
        ensureIndices()
        val current = project.frames[frameIndexState]
        if (selectedRasterLayerIndex !in current.layers.indices) return
        val selected = current.layers[selectedRasterLayerIndex]
        selected.offsetX = (selected.offsetX + dx.finiteOr(0f)).finiteOr(0f)
        selected.offsetY = (selected.offsetY + dy.finiteOr(0f)).finiteOr(0f)
        val safeScale = scale.finiteOr(1f).coerceIn(-20f, 20f)
        selected.scaleX = (selected.scaleX * safeScale).finiteOr(1f).coerceIn(-20f, 20f)
        selected.scaleY = (selected.scaleY * safeScale).finiteOr(1f).coerceIn(-20f, 20f)
        selected.rotationDeg = (selected.rotationDeg + rotation.finiteOr(0f)).finiteOr(0f)
        project.touch()
    }

    fun duplicateSelection() {
        ensureIndices()
        val current = project.frames[frameIndexState]
        if (selectedRasterLayerIndex in current.layers.indices) {
            val source = current.layers[selectedRasterLayerIndex]
            current.layers.add(selectedRasterLayerIndex, source.cloneLayer().also {
                it.name = "${source.name} copy"
                it.offsetX += 24f
                it.offsetY += 24f
            })
            selectedRasterLayerIndex = selectedRasterLayerIndex.coerceIn(current.layers.indices)
            project.touch()
            return
        }
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        val created = mutableListOf<String>()
        current.layers.forEach { candidateLayer ->
            val copies = candidateLayer.strokes.filter { it.id in ids }.map { stroke ->
                stroke.copy(id = UUID.randomUUID().toString(), points = stroke.points.map { CanvasPoint(it.x + 24f, it.y + 24f) }).also { created += it.id }
            }
            candidateLayer.strokes.addAll(copies)
        }
        selectedIds.clear()
        selectedIds.addAll(created)
        project.touch()
    }

    fun recolorSelection(argb: Int) {
        ensureIndices()
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        project.frames[frameIndexState].layers.forEach { candidateLayer ->
            for (index in candidateLayer.strokes.indices) {
                val stroke = candidateLayer.strokes[index]
                if (stroke.id in ids && !stroke.erase) candidateLayer.strokes[index] = stroke.copy(colorArgb = argb)
            }
        }
        project.touch()
    }

    fun deleteSelection() {
        ensureIndices()
        val current = project.frames[frameIndexState]
        if (selectedRasterLayerIndex in current.layers.indices) {
            val index = selectedRasterLayerIndex
            if (current.layers.size > 1) current.layers.removeAt(index) else {
                current.layers[index].rasterPngBase64 = null
                current.layers[index].strokes.clear()
            }
            layerIndex = layerIndex.coerceIn(current.layers.indices)
            clearSelection()
            project.touch()
            return
        }
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        current.layers.forEach { candidateLayer -> candidateLayer.strokes.removeAll { it.id in ids } }
        clearSelection()
        project.touch()
    }

    private fun transformVectorSelection(rotation: Float = 0f, scaleX: Float = 1f, scaleY: Float = 1f) {
        ensureIndices()
        val current = project.frames[frameIndexState]
        if (selectedRasterLayerIndex in current.layers.indices) {
            val selected = current.layers[selectedRasterLayerIndex]
            selected.rotationDeg = (selected.rotationDeg + rotation.finiteOr(0f)).finiteOr(0f)
            selected.scaleX = (selected.scaleX * scaleX.finiteOr(1f)).finiteOr(1f).coerceIn(-20f, 20f)
            selected.scaleY = (selected.scaleY * scaleY.finiteOr(1f)).finiteOr(1f).coerceIn(-20f, 20f)
            project.touch()
            return
        }
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        val points = current.layers.flatMap { it.strokes }.filter { it.id in ids }.flatMap { it.points }
        if (points.isEmpty()) return
        val cx = (points.minOf { it.x } + points.maxOf { it.x }) / 2f
        val cy = (points.minOf { it.y } + points.maxOf { it.y }) / 2f
        val radians = Math.toRadians(rotation.finiteOr(0f).toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        current.layers.forEach { candidateLayer ->
            for (index in candidateLayer.strokes.indices) {
                val stroke = candidateLayer.strokes[index]
                if (stroke.id in ids) {
                    candidateLayer.strokes[index] = stroke.copy(points = stroke.points.map { point ->
                        val x = (point.x - cx) * scaleX.finiteOr(1f)
                        val y = (point.y - cy) * scaleY.finiteOr(1f)
                        CanvasPoint((cx + x * cos - y * sin).finiteOr(point.x), (cy + x * sin + y * cos).finiteOr(point.y))
                    })
                }
            }
        }
        project.touch()
    }
}

private fun hitStroke(stroke: StrokeData, point: CanvasPoint): Boolean {
    if (stroke.points.isEmpty()) return false
    val threshold = (stroke.width.finiteOr(1f).coerceAtLeast(1f) / 2f + 18f)
    return stroke.points.any { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) <= threshold }
}

private fun colorDistance(a: Int, b: Int): Float {
    val ar = (a shr 16 and 0xFF) / 255f
    val ag = (a shr 8 and 0xFF) / 255f
    val ab = (a and 0xFF) / 255f
    val br = (b shr 16 and 0xFF) / 255f
    val bg = (b shr 8 and 0xFF) / 255f
    val bb = (b and 0xFF) / 255f
    return kotlin.math.sqrt((ar - br) * (ar - br) + (ag - bg) * (ag - bg) + (ab - bb) * (ab - bb))
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
