package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReleaseCanvas(
    modifier: Modifier,
    editor: EditorState,
    history: ProjectHistory,
    tool: ReleaseTool,
    zoom: Float,
    panX: Float,
    panY: Float,
    onPan: (Float, Float) -> Unit,
    onionBefore: Int,
    onionAfter: Int,
    onionAlpha: Float,
    onionSelectedLayerOnly: Boolean,
    recentColours: MutableList<Int>,
    onToolChange: (ReleaseTool) -> Unit,
    onMessage: (String) -> Unit
) {
    val project = editor.project
    val lassoColour = MaterialTheme.colorScheme.primary
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var livePoints by remember { mutableStateOf<List<CanvasPoint>>(emptyList()) }
    var shapeStart by remember { mutableStateOf<CanvasPoint?>(null) }
    var shapeEnd by remember { mutableStateOf<CanvasPoint?>(null) }
    var lastVisiblePoint by remember { mutableStateOf<CanvasPoint?>(null) }
    var colourRepeatPoint by remember { mutableStateOf<CanvasPoint?>(null) }
    var draggingSelection by remember { mutableStateOf(false) }
    var pressureTotal by remember { mutableFloatStateOf(0f) }
    var pressureCount by remember { mutableIntStateOf(0) }
    var activeErase by remember { mutableStateOf(false) }

    LaunchedEffect(tool, editor.frameIndex) {
        if (tool != ReleaseTool.ColourRepeat) colourRepeatPoint = null
    }

    val preview = remember(project.revision, editor.frameIndex) {
        FrameRenderer.renderPreview(project, editor.frameIndex)
    }
    DisposableEffect(preview) {
        onDispose { if (!preview.isRecycled) preview.recycle() }
    }

    val onionFrames = remember(project.revision, editor.frameIndex, onionBefore, onionAfter, onionSelectedLayerOnly, editor.layerIndex) {
        val result = mutableListOf<Pair<Bitmap, Boolean>>()
        val before = onionBefore.coerceIn(0, 5)
        val after = onionAfter.coerceIn(0, 5)
        for (distance in before downTo 1) {
            val index = editor.frameIndex - distance
            if (index >= 0) result += renderOnion(project, index, editor.layerIndex, onionSelectedLayerOnly) to false
        }
        for (distance in 1..after) {
            val index = editor.frameIndex + distance
            if (index <= project.frames.lastIndex) result += renderOnion(project, index, editor.layerIndex, onionSelectedLayerOnly) to true
        }
        result
    }
    DisposableEffect(onionFrames) {
        onDispose { onionFrames.forEach { (bitmap, _) -> if (!bitmap.isRecycled) bitmap.recycle() } }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = project.canvasWidth.toFloat() / project.canvasHeight.coerceAtLeast(1)
        val fit = if (maxWidth.value / maxHeight.value > aspect) {
            Modifier.fillMaxHeight().aspectRatio(aspect)
        } else Modifier.fillMaxWidth().aspectRatio(aspect)

        Box(
            fit
                .graphicsLayer {
                    scaleX = zoom.coerceIn(.25f, 8f)
                    scaleY = zoom.coerceIn(.25f, 8f)
                    translationX = panX
                    translationY = panY
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInteropFilter { event ->
                    if (canvasSize.width <= 0 || canvasSize.height <= 0) return@pointerInteropFilter false
                    val visible = eventPoint(event, canvasSize, project)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastVisiblePoint = visible
                            draggingSelection = false
                            pressureTotal = 0f
                            pressureCount = 0
                            activeErase = tool == ReleaseTool.Eraser || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                            when (tool) {
                                ReleaseTool.Brush, ReleaseTool.Eraser -> if (!editor.layer.locked) {
                                    history.checkpoint()
                                    livePoints = listOf(toLayerPoint(editor, visible))
                                    pressureTotal = pressureFor(event)
                                    pressureCount = 1
                                }
                                ReleaseTool.SelectPart -> SelectionTools.selectPartAt(editor, visible)
                                ReleaseTool.ColourRepeat -> {
                                    colourRepeatPoint = visible
                                    SelectionTools.selectRepeatedColorAt(editor, visible)
                                }
                                ReleaseTool.Fill -> {
                                    history.checkpoint()
                                    runCatching {
                                        val changed = RasterEditingTools.floodFillVisible(project, editor.frameIndex, visible, editor.color.toArgb())
                                        if (changed > 0) {
                                            editor.layerIndex = 0
                                            editor.clearSelection()
                                        }
                                        changed
                                    }.onFailure { onMessage("Fill failed: ${it.message ?: "not enough memory"}") }
                                }
                                ReleaseTool.Eyedropper -> {
                                    runCatching { RasterEditingTools.sampleVisibleColor(project, editor.frameIndex, visible) }
                                        .onSuccess { argb ->
                                            if (argb != null) {
                                                val hsv = FloatArray(3)
                                                AndroidColor.colorToHSV(argb, hsv)
                                                editor.hue = hsv[0]
                                                editor.saturation = hsv[1]
                                                editor.value = hsv[2]
                                                editor.alpha = AndroidColor.alpha(argb) / 255f
                                                onToolChange(ReleaseTool.Brush)
                                            }
                                        }.onFailure { onMessage("Eyedropper failed: ${it.message ?: "render error"}") }
                                }
                                ReleaseTool.Lasso -> livePoints = listOf(visible)
                                ReleaseTool.Line, ReleaseTool.Rectangle, ReleaseTool.Ellipse -> if (!editor.layer.locked) {
                                    history.checkpoint()
                                    val local = toLayerPoint(editor, visible)
                                    shapeStart = local
                                    shapeEnd = local
                                }
                                ReleaseTool.Pan -> Unit
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val previousVisible = lastVisiblePoint ?: visible
                            when (tool) {
                                ReleaseTool.Brush, ReleaseTool.Eraser -> if (!editor.layer.locked && livePoints.isNotEmpty()) {
                                    val appended = ArrayList<CanvasPoint>(event.historySize + 1)
                                    for (historyIndex in 0 until event.historySize) {
                                        val historical = eventPoint(event.getHistoricalX(0, historyIndex), event.getHistoricalY(0, historyIndex), canvasSize, project)
                                        appended += toLayerPoint(editor, historical)
                                        pressureTotal += pressureFor(event, historyIndex)
                                        pressureCount++
                                    }
                                    appended += toLayerPoint(editor, visible)
                                    pressureTotal += pressureFor(event)
                                    pressureCount++
                                    livePoints = livePoints + appended
                                }
                                ReleaseTool.SelectPart, ReleaseTool.ColourRepeat -> {
                                    if (editor.selectedIds.isNotEmpty() || editor.selectedRasterLayerIndex >= 0) {
                                        if (!draggingSelection) {
                                            history.checkpoint()
                                            draggingSelection = true
                                        }
                                        editor.moveSelection(visible.x - previousVisible.x, visible.y - previousVisible.y)
                                    }
                                }
                                ReleaseTool.Lasso -> livePoints = livePoints + visible
                                ReleaseTool.Line, ReleaseTool.Rectangle, ReleaseTool.Ellipse -> shapeEnd = toLayerPoint(editor, visible)
                                ReleaseTool.Pan -> onPan(
                                    event.x - projectToLocalX(previousVisible, canvasSize, project),
                                    event.y - projectToLocalY(previousVisible, canvasSize, project)
                                )
                                else -> Unit
                            }
                            lastVisiblePoint = visible
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            when (tool) {
                                ReleaseTool.Brush, ReleaseTool.Eraser -> if (!editor.layer.locked && livePoints.isNotEmpty()) {
                                    val preset = if (activeErase) editor.eraser else editor.brush
                                    val pressure = if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                                        (pressureTotal / pressureCount.coerceAtLeast(1)).coerceIn(.15f, 2f)
                                    } else 1f
                                    editor.layer.strokes.add(BrushEngine.createStroke(preset, livePoints, editor.color.toArgb(), activeErase, pressure))
                                    val argb = editor.color.toArgb()
                                    recentColours.remove(argb)
                                    recentColours.add(0, argb)
                                    while (recentColours.size > 16) recentColours.removeAt(recentColours.lastIndex)
                                    project.touch()
                                }
                                ReleaseTool.Lasso -> selectCurrentLayerPolygon(editor, livePoints)
                                ReleaseTool.Line, ReleaseTool.Rectangle, ReleaseTool.Ellipse -> {
                                    val start = shapeStart
                                    val end = shapeEnd
                                    if (start != null && end != null && !editor.layer.locked) {
                                        val points = when (tool) {
                                            ReleaseTool.Line -> listOf(start, end)
                                            ReleaseTool.Rectangle -> rectanglePoints(start, end)
                                            ReleaseTool.Ellipse -> ellipsePoints(start, end)
                                            else -> emptyList()
                                        }
                                        if (points.isNotEmpty()) {
                                            editor.layer.strokes.add(BrushEngine.createStroke(editor.brush, points, editor.color.toArgb(), false))
                                            project.touch()
                                        }
                                    }
                                }
                                else -> if (draggingSelection) project.touch()
                            }
                            livePoints = emptyList()
                            shapeStart = null
                            shapeEnd = null
                            lastVisiblePoint = null
                            draggingSelection = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            livePoints = emptyList()
                            shapeStart = null
                            shapeEnd = null
                            lastVisiblePoint = null
                            draggingSelection = false
                            true
                        }
                        else -> true
                    }
                }
        ) {
            ReleaseCheckerboard()
            onionFrames.forEach { (bitmap, next) ->
                Image(
                    bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    alpha = onionAlpha.coerceIn(0f, .8f) * if (next) .82f else 1f
                )
            }
            Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / project.canvasWidth.coerceAtLeast(1)
                val sy = size.height / project.canvasHeight.coerceAtLeast(1)
                if (livePoints.size >= 2 && tool in listOf(ReleaseTool.Brush, ReleaseTool.Eraser, ReleaseTool.Lasso)) {
                    val displayPoints = if (tool == ReleaseTool.Lasso) livePoints else livePoints.map { localToVisible(editor, it) }
                    val path = Path().apply {
                        moveTo(displayPoints.first().x * sx, displayPoints.first().y * sy)
                        displayPoints.drop(1).forEach { lineTo(it.x * sx, it.y * sy) }
                    }
                    val preset = if (activeErase) editor.eraser else editor.brush
                    drawPath(
                        path,
                        if (tool == ReleaseTool.Lasso) lassoColour else if (activeErase) Color(0x889E9E9E) else editor.color,
                        style = Stroke(
                            width = if (tool == ReleaseTool.Lasso) 2.dp.toPx() else (preset.width * minOf(sx, sy)).coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                val start = shapeStart
                val end = shapeEnd
                if (start != null && end != null && tool in listOf(ReleaseTool.Line, ReleaseTool.Rectangle, ReleaseTool.Ellipse)) {
                    val a = localToVisible(editor, start)
                    val b = localToVisible(editor, end)
                    val x1 = a.x * sx; val y1 = a.y * sy; val x2 = b.x * sx; val y2 = b.y * sy
                    val stroke = Stroke((editor.brush.width * minOf(sx, sy)).coerceAtLeast(1f))
                    when (tool) {
                        ReleaseTool.Line -> drawLine(editor.color, Offset(x1, y1), Offset(x2, y2), stroke.width)
                        ReleaseTool.Rectangle -> drawRect(editor.color, Offset(minOf(x1, x2), minOf(y1, y2)), androidx.compose.ui.geometry.Size(abs(x2 - x1), abs(y2 - y1)), style = stroke)
                        ReleaseTool.Ellipse -> drawOval(editor.color, Offset(minOf(x1, x2), minOf(y1, y2)), androidx.compose.ui.geometry.Size(abs(x2 - x1), abs(y2 - y1)), style = stroke)
                        else -> Unit
                    }
                }
            }

            if (editor.selectedIds.isNotEmpty() || editor.selectedRasterLayerIndex >= 0) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    LazyRow(Modifier.padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        item {
                            Text(
                                if (editor.selectedRasterLayerIndex in editor.frame.layers.indices) editor.frame.layers[editor.selectedRasterLayerIndex].part.label
                                else "${editor.selectedIds.size} selected",
                                Modifier.padding(start = 6.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        item { TextButton(onClick = { history.checkpoint(); editor.duplicateSelection() }) { Text("Duplicate") } }
                        item { TextButton(onClick = { history.checkpoint(); SmartTransformTools.rotateSelection(editor, -15f) }) { Text("↺") } }
                        item { TextButton(onClick = { history.checkpoint(); SmartTransformTools.rotateSelection(editor, 15f) }) { Text("↻") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.scaleSelection(.9f) }) { Text("−") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.scaleSelection(1.1f) }) { Text("+") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.flipSelectionHorizontal() }) { Text("Flip") } }
                        if (tool == ReleaseTool.ColourRepeat && editor.selectedRasterLayerIndex in editor.frame.layers.indices && colourRepeatPoint != null) {
                            item {
                                TextButton(onClick = {
                                    history.checkpoint()
                                    SelectionTools.replaceRepeatedRasterColor(
                                        project,
                                        editor.frame.layers[editor.selectedRasterLayerIndex],
                                        colourRepeatPoint!!,
                                        editor.color.toArgb()
                                    )
                                }) { Text("Recolour") }
                            }
                        }
                        item { TextButton(onClick = { history.checkpoint(); editor.deleteSelection() }) { Text("Delete") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCheckerboard() {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 22f
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                drawRect(if ((row + col) % 2 == 0) Color(0xFFE8E8E8) else Color(0xFFD2D2D2), Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

private fun renderOnion(project: ProjectState, frameIndex: Int, layerIndex: Int, selectedOnly: Boolean): Bitmap {
    if (!selectedOnly) return FrameRenderer.renderPreview(project, frameIndex)
    val sourceFrame = project.frames.getOrNull(frameIndex) ?: return FrameRenderer.renderPreview(project, frameIndex.coerceIn(project.frames.indices))
    val sourceLayer = sourceFrame.layers.getOrNull(layerIndex) ?: return FrameRenderer.renderPreview(project, frameIndex)
    val temporary = ProjectState(
        name = "onion",
        canvasWidth = project.canvasWidth,
        canvasHeight = project.canvasHeight,
        backgroundArgb = AndroidColor.TRANSPARENT,
        frames = listOf(
            FrameState(
                duration = sourceFrame.durationMs,
                layers = listOf(sourceLayer.cloneLayer()),
                cameraX = sourceFrame.cameraX,
                cameraY = sourceFrame.cameraY,
                cameraZoom = sourceFrame.cameraZoom,
                cameraRotation = sourceFrame.cameraRotation
            )
        )
    )
    return FrameRenderer.renderPreview(temporary, 0)
}

private fun eventPoint(event: MotionEvent, size: IntSize, project: ProjectState): CanvasPoint = eventPoint(event.x, event.y, size, project)
private fun eventPoint(x: Float, y: Float, size: IntSize, project: ProjectState): CanvasPoint = CanvasPoint(
    if (size.width <= 0) 0f else x / size.width * project.canvasWidth,
    if (size.height <= 0) 0f else y / size.height * project.canvasHeight
)
private fun projectToLocalX(point: CanvasPoint, size: IntSize, project: ProjectState): Float = if (project.canvasWidth <= 0) 0f else point.x / project.canvasWidth * size.width
private fun projectToLocalY(point: CanvasPoint, size: IntSize, project: ProjectState): Float = if (project.canvasHeight <= 0) 0f else point.y / project.canvasHeight * size.height
private fun toLayerPoint(editor: EditorState, visible: CanvasPoint): CanvasPoint = SelectionTools.inverseLayer(editor.project, editor.layer, SelectionTools.inverseCamera(editor.project, editor.frame, visible))

private fun localToVisible(editor: EditorState, local: CanvasPoint): CanvasPoint {
    val project = editor.project
    val layer = editor.layer
    val frame = editor.frame
    val cx = project.canvasWidth / 2f
    val cy = project.canvasHeight / 2f
    var x = local.x - cx
    var y = local.y - cy
    val lr = Math.toRadians(layer.rotationDeg.toDouble())
    val lsx = layer.scaleX.takeIf { abs(it) > .0001f } ?: 1f
    val lsy = layer.scaleY.takeIf { abs(it) > .0001f } ?: 1f
    val lx = x * lsx
    val ly = y * lsy
    x = lx * cos(lr).toFloat() - ly * sin(lr).toFloat() + cx + layer.offsetX
    y = lx * sin(lr).toFloat() + ly * cos(lr).toFloat() + cy + layer.offsetY
    x -= cx
    y -= cy
    val fr = Math.toRadians(frame.cameraRotation.toDouble())
    val fx = x * frame.cameraZoom
    val fy = y * frame.cameraZoom
    return CanvasPoint(
        fx * cos(fr).toFloat() - fy * sin(fr).toFloat() + cx + frame.cameraX,
        fx * sin(fr).toFloat() + fy * cos(fr).toFloat() + cy + frame.cameraY
    )
}

private fun pressureFor(event: MotionEvent, historyIndex: Int? = null): Float {
    val raw = if (historyIndex == null) event.pressure else event.getHistoricalPressure(0, historyIndex)
    return raw.takeIf { it.isFinite() }?.coerceIn(.05f, 2f) ?: 1f
}

private fun rectanglePoints(a: CanvasPoint, b: CanvasPoint): List<CanvasPoint> {
    val l = minOf(a.x, b.x); val r = maxOf(a.x, b.x); val t = minOf(a.y, b.y); val bottom = maxOf(a.y, b.y)
    return listOf(CanvasPoint(l, t), CanvasPoint(r, t), CanvasPoint(r, bottom), CanvasPoint(l, bottom), CanvasPoint(l, t))
}

private fun ellipsePoints(a: CanvasPoint, b: CanvasPoint): List<CanvasPoint> {
    val cx = (a.x + b.x) / 2f; val cy = (a.y + b.y) / 2f
    val rx = abs(b.x - a.x) / 2f; val ry = abs(b.y - a.y) / 2f
    return List(65) { i ->
        val angle = Math.PI * 2.0 * i / 64.0
        CanvasPoint(cx + cos(angle).toFloat() * rx, cy + sin(angle).toFloat() * ry)
    }
}

private fun selectCurrentLayerPolygon(editor: EditorState, polygon: List<CanvasPoint>) {
    if (polygon.size < 3) return
    val localPolygon = polygon.map { toLayerPoint(editor, it) }
    editor.selectedRasterLayerIndex = -1
    editor.selectedIds.clear()
    editor.layer.strokes.forEach { stroke ->
        if (stroke.points.any { pointInPolygonRelease(it, localPolygon) }) editor.selectedIds.add(stroke.id)
    }
}

private fun pointInPolygonRelease(point: CanvasPoint, polygon: List<CanvasPoint>): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        val denominator = (b.y - a.y).takeIf { abs(it) > .00001f } ?: .00001f
        val crosses = (a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / denominator + a.x
        if (crosses) inside = !inside
        j = i
    }
    return inside
}
