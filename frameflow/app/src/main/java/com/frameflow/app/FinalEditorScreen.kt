package com.frameflow.app

import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class FinalTool(val label: String) {
    Brush("Brush"), Eraser("Eraser"), Select("Select"), ColourRepeat("Colour repeat"),
    Fill("Fill"), Eyedropper("Eyedropper"), Lasso("Lasso"), Line("Line"),
    Rectangle("Rectangle"), Ellipse("Ellipse"), Hand("Hand")
}

private enum class FinalSheet { Brushes, Colour, Layers, Onion, Smart, Audio, Project, Camera, Tools }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalEditorScreen(
    project: ProjectState,
    repository: ProjectRepository,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
    onExportProject: () -> Unit,
    onExportCurrentPng: () -> Unit,
    onExportFramesZip: () -> Unit,
    onExportGif: () -> Unit,
    onExportMp4: () -> Unit
) {
    val editor = remember(project.id) { EditorState(project) }
    val history = remember(project.id) { ProjectHistory(project) }
    var tool by remember { mutableStateOf(FinalTool.Brush) }
    var sheet by remember { mutableStateOf<FinalSheet?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var viewZoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var onionBefore by remember { mutableIntStateOf(1) }
    var onionAfter by remember { mutableIntStateOf(0) }
    var durationDialogIndex by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    var smartBusy by remember { mutableStateOf(false) }
    val recentColors = remember { mutableStateListOf<Int>() }
    val audioFile = remember(project.audioFileName, project.revision) { repository.audioFile(project) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun setTool(next: FinalTool) {
        tool = next
        when (next) {
            FinalTool.Brush -> editor.tool = Tool.Brush
            FinalTool.Eraser -> editor.tool = Tool.Eraser
            FinalTool.Select -> editor.tool = Tool.SmartSelect
            FinalTool.ColourRepeat -> editor.tool = Tool.ColorRepeat
            else -> Unit
        }
    }

    DisposableEffect(audioFile?.absolutePath) {
        val created = audioFile?.let { file ->
            runCatching {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setVolume(project.audioVolume, project.audioVolume)
                }
            }.getOrNull()
        }
        player = created
        onDispose {
            runCatching { created?.stop() }
            created?.release()
            if (player === created) player = null
        }
    }
    LaunchedEffect(project.audioVolume) { player?.setVolume(project.audioVolume, project.audioVolume) }

    LaunchedEffect(editor.playing) {
        if (!editor.playing) {
            player?.pause()
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                val timelineStart = project.frames.take(editor.frameIndex).sumOf { it.durationMs }
                val delayMs = project.audioOffsetMs - timelineStart
                if (delayMs > 0) delay(delayMs.toLong())
                if (editor.playing) {
                    val audioPos = (timelineStart - project.audioOffsetMs).coerceAtLeast(0)
                    runCatching { player?.seekTo(audioPos); player?.start() }
                }
            }
            while (editor.playing) {
                editor.ensureIndices()
                delay(editor.frame.durationMs.toLong())
                if (!editor.playing) break
                if (editor.frameIndex == project.frames.lastIndex) {
                    if (project.loopPlayback) editor.frameIndex = 0 else {
                        editor.playing = false
                        break
                    }
                } else editor.frameIndex++
                editor.ensureIndices()
                editor.clearSelection()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                title = {
                    Column {
                        Text(project.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${project.mode.label} · ${editor.frameIndex + 1}/${project.frames.size} · ${"%.2f".format(project.totalDurationMs / 1000f)} s",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    TextButton(enabled = history.canUndo, onClick = { if (history.undo()) editor.ensureIndices() }) { Text("Undo") }
                    TextButton(enabled = history.canRedo, onClick = { if (history.redo()) editor.ensureIndices() }) { Text("Redo") }
                    TextButton(onClick = { editor.playing = !editor.playing }) { Text(if (editor.playing) "Stop" else "Play") }
                    Box {
                        TextButton(onClick = { showExport = true }) { Text("Export") }
                        DropdownMenu(showExport, { showExport = false }) {
                            FinalExportItem("MP4 video", "H.264 + project audio") { showExport = false; onExportMp4() }
                            FinalExportItem("Animated GIF", "Frame hold timing preserved") { showExport = false; onExportGif() }
                            FinalExportItem("Current frame PNG", "Full-resolution still") { showExport = false; onExportCurrentPng() }
                            FinalExportItem("All frames ZIP", "PNG sequence + project + audio") { showExport = false; onExportFramesZip() }
                            FinalExportItem("Editable project", ".frameflow") { showExport = false; onExportProject() }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { history.checkpoint(); editor.cloneFrame() }, text = { Text("+ Clone") })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item { FilterChip(tool == FinalTool.Brush, { setTool(FinalTool.Brush); sheet = FinalSheet.Brushes }, { Text("Brush") }) }
                item { FilterChip(tool == FinalTool.Eraser, { setTool(FinalTool.Eraser); sheet = FinalSheet.Brushes }, { Text("Eraser") }) }
                item { FilterChip(tool == FinalTool.Select, { setTool(FinalTool.Select) }, { Text("Select limb") }) }
                item { FilterChip(tool == FinalTool.ColourRepeat, { setTool(FinalTool.ColourRepeat) }, { Text("Colour repeat") }) }
                item { AssistChip({ sheet = FinalSheet.Tools }, label = { Text("More tools") }) }
                item { AssistChip({ sheet = FinalSheet.Layers }, label = { Text("Layers") }) }
                item { AssistChip({ sheet = FinalSheet.Onion }, label = { Text("Onion") }) }
                item { AssistChip({ sheet = FinalSheet.Smart }, label = { Text("Smart") }) }
                item { AssistChip({ sheet = FinalSheet.Audio }, label = { Text("Audio") }) }
            }

            FinalCanvas(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                editor = editor,
                history = history,
                tool = tool,
                zoom = viewZoom,
                panX = panX,
                panY = panY,
                onPan = { dx, dy -> panX += dx; panY += dy },
                onionBefore = onionBefore,
                onionAfter = onionAfter,
                recentColors = recentColors,
                onMessage = { message = it }
            )

            FinalTimeline(editor, history, onExactDuration = { durationDialogIndex = it })

            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                item { TextButton(onClick = { history.checkpoint(); editor.addBlankFrame() }) { Text("Blank") } }
                item { TextButton(onClick = { history.checkpoint(); editor.deleteFrame() }) { Text("Delete frame") } }
                item { TextButton(onClick = { sheet = FinalSheet.Camera }) { Text("Camera") } }
                item { TextButton(onClick = { sheet = FinalSheet.Colour }) { Text("Colour") } }
                item { TextButton(onClick = { onSave(); message = "Saved" }) { Text("Save") } }
                item { TextButton(onClick = { sheet = FinalSheet.Project }) { Text("Project") } }
            }
        }
    }

    when (sheet) {
        FinalSheet.Brushes -> FinalBrushSheet(editor, tool == FinalTool.Eraser, { setTool(if (tool == FinalTool.Eraser) FinalTool.Eraser else FinalTool.Brush) }) { sheet = null }
        FinalSheet.Colour -> FinalColourSheet(editor, recentColors) { sheet = null }
        FinalSheet.Layers -> FinalLayersSheet(editor, history, onImportImage) { sheet = null }
        FinalSheet.Onion -> FinalOnionSheet(editor, onionBefore, onionAfter, { onionBefore = it }, { onionAfter = it }) { sheet = null }
        FinalSheet.Smart -> FinalSmartSheet(editor, history, smartBusy, onIdentify = {
            if (!editor.layer.hasRaster) {
                message = "Select a raster/image layer first."
            } else {
                smartBusy = true
                SmartLimbIdentifier.splitLayer(
                    editor.layer,
                    onSuccess = { parts ->
                        smartBusy = false
                        if (parts.isEmpty()) message = "No useful parts were detected."
                        else {
                            history.checkpoint()
                            val index = editor.layerIndex
                            val original = editor.frame.layers.removeAt(index)
                            val created = parts.map { part ->
                                LayerState(
                                    name = part.name,
                                    part = part.part,
                                    rasterPngBase64 = part.pngBase64,
                                    rasterName = part.name,
                                    opacity = original.opacity,
                                    offsetX = original.offsetX,
                                    offsetY = original.offsetY,
                                    scaleX = original.scaleX,
                                    scaleY = original.scaleY,
                                    rotationDeg = original.rotationDeg
                                )
                            }
                            editor.frame.layers.addAll(index, created)
                            editor.layerIndex = index.coerceIn(editor.frame.layers.indices)
                            editor.selectLayer(editor.layerIndex)
                            project.touch()
                            message = "Identified ${parts.size} parts. Select Limb now moves each painted limb as one object."
                        }
                    },
                    onFailure = { smartBusy = false; message = "Part identification failed: ${it.message ?: "unknown error"}" }
                )
            }
        }, onMessage = { message = it }) { sheet = null }
        FinalSheet.Audio -> FinalAudioSheet(project, history, audioFile?.name, onImportAudio, { repository.removeAudio(project) }) { sheet = null }
        FinalSheet.Project -> FinalProjectSheet(project, history, onSave) { sheet = null }
        FinalSheet.Camera -> FinalCameraSheet(editor, history, viewZoom, { viewZoom = it }, { viewZoom = 1f; panX = 0f; panY = 0f }) { sheet = null }
        FinalSheet.Tools -> FinalToolsSheet(tool, onTool = { setTool(it); sheet = null }) { sheet = null }
        null -> Unit
    }

    if (durationDialogIndex in project.frames.indices) {
        FinalDurationDialog(
            frame = project.frames[durationDialogIndex],
            onDismiss = { durationDialogIndex = -1 },
            onApply = { ms ->
                history.checkpoint()
                project.frames[durationDialogIndex].durationMs = ms
                project.touch()
                durationDialogIndex = -1
            }
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Frameflow") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun FinalCanvas(
    modifier: Modifier,
    editor: EditorState,
    history: ProjectHistory,
    tool: FinalTool,
    zoom: Float,
    panX: Float,
    panY: Float,
    onPan: (Float, Float) -> Unit,
    onionBefore: Int,
    onionAfter: Int,
    recentColors: MutableList<Int>,
    onMessage: (String) -> Unit
) {
    val project = editor.project
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var liveVisible by remember { mutableStateOf<List<CanvasPoint>>(emptyList()) }
    var liveLocal by remember { mutableStateOf<List<CanvasPoint>>(emptyList()) }
    var dragStartVisible by remember { mutableStateOf<CanvasPoint?>(null) }
    var dragEndVisible by remember { mutableStateOf<CanvasPoint?>(null) }
    var moveCheckpoint by remember { mutableStateOf(false) }
    var colourRepeatPoint by remember { mutableStateOf<CanvasPoint?>(null) }

    val preview = remember(project.revision, editor.frameIndex) { finalPreview(project, editor.frameIndex) }
    val onions = remember(project.revision, editor.frameIndex, onionBefore, onionAfter) {
        buildList<Pair<Bitmap, Boolean>> {
            for (offset in onionBefore downTo 1) {
                val index = editor.frameIndex - offset
                if (index >= 0) add(finalPreview(project, index) to false)
            }
            for (offset in 1..onionAfter) {
                val index = editor.frameIndex + offset
                if (index <= project.frames.lastIndex) add(finalPreview(project, index) to true)
            }
        }
    }
    DisposableEffect(preview) { onDispose { if (!preview.isRecycled) preview.recycle() } }
    DisposableEffect(onions) { onDispose { onions.forEach { if (!it.first.isRecycled) it.first.recycle() } } }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = project.canvasWidth.toFloat() / project.canvasHeight.toFloat()
        val fit = if (maxWidth.value / maxHeight.value > aspect) Modifier.fillMaxHeight().aspectRatio(aspect) else Modifier.fillMaxWidth().aspectRatio(aspect)
        Box(
            fit.graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = panX
                translationY = panY
            }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInput(tool, editor.frameIndex, editor.layerIndex, canvasSize, project.snapMs) {
                    if (tool == FinalTool.Fill || tool == FinalTool.Eyedropper) {
                        detectTapGestures { pos ->
                            val point = pos.finalProjectPoint(canvasSize, project)
                            if (tool == FinalTool.Eyedropper) {
                                RasterEditingTools.sampleVisibleColor(project, editor.frameIndex, point)?.let { argb ->
                                    val hsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(argb, hsv)
                                    editor.hue = hsv[0]
                                    editor.saturation = hsv[1]
                                    editor.value = hsv[2]
                                    editor.alpha = android.graphics.Color.alpha(argb) / 255f
                                    recentColors.remove(argb)
                                    recentColors.add(0, argb)
                                    while (recentColors.size > 12) recentColors.removeAt(recentColors.lastIndex)
                                }
                            } else {
                                history.checkpoint()
                                val changed = RasterEditingTools.floodFillVisible(project, editor.frameIndex, point, editor.color.toArgb())
                                if (changed == 0) onMessage("Nothing changed at that point.") else {
                                    editor.layerIndex = 0
                                    onMessage("Filled ${"%,d".format(changed)} pixels on a new editable layer.")
                                }
                            }
                        }
                    } else {
                        detectDragGestures(
                            onDragStart = { pos ->
                                moveCheckpoint = false
                                val visible = pos.finalProjectPoint(canvasSize, project)
                                dragStartVisible = visible
                                dragEndVisible = visible
                                colourRepeatPoint = visible
                                val scene = SelectionTools.inverseCamera(project, editor.frame, visible)
                                val local = SelectionTools.inverseLayer(project, editor.layer, scene)
                                when (tool) {
                                    FinalTool.Brush, FinalTool.Eraser -> if (!editor.layer.locked) {
                                        history.checkpoint()
                                        liveVisible = listOf(visible)
                                        liveLocal = listOf(local)
                                    }
                                    FinalTool.Lasso -> liveVisible = listOf(visible)
                                    FinalTool.Select -> SelectionTools.selectPartAt(editor, visible)
                                    FinalTool.ColourRepeat -> SelectionTools.selectRepeatedColorAt(editor, visible)
                                    FinalTool.Line, FinalTool.Rectangle, FinalTool.Ellipse -> if (!editor.layer.locked) history.checkpoint()
                                    else -> Unit
                                }
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                val visible = change.position.finalProjectPoint(canvasSize, project)
                                dragEndVisible = visible
                                val scene = SelectionTools.inverseCamera(project, editor.frame, visible)
                                val local = SelectionTools.inverseLayer(project, editor.layer, scene)
                                when (tool) {
                                    FinalTool.Hand -> onPan(drag.x, drag.y)
                                    FinalTool.Brush, FinalTool.Eraser -> if (!editor.layer.locked) {
                                        liveVisible = liveVisible + visible
                                        liveLocal = liveLocal + local
                                    }
                                    FinalTool.Lasso -> liveVisible = liveVisible + visible
                                    FinalTool.Select, FinalTool.ColourRepeat -> if (editor.selectedIds.isNotEmpty() || editor.selectedRasterLayerIndex >= 0) {
                                        if (!moveCheckpoint) { history.checkpoint(); moveCheckpoint = true }
                                        val dx = drag.x / canvasSize.width.coerceAtLeast(1) * project.canvasWidth
                                        val dy = drag.y / canvasSize.height.coerceAtLeast(1) * project.canvasHeight
                                        editor.moveSelection(dx, dy)
                                    }
                                    else -> Unit
                                }
                            },
                            onDragEnd = {
                                val start = dragStartVisible
                                val end = dragEndVisible
                                when (tool) {
                                    FinalTool.Brush, FinalTool.Eraser -> if (liveLocal.isNotEmpty() && !editor.layer.locked) {
                                        val preset = if (tool == FinalTool.Eraser) editor.eraser else editor.brush
                                        editor.layer.strokes.add(
                                            StrokeData(
                                                points = liveLocal,
                                                colorArgb = editor.color.toArgb(),
                                                width = preset.width,
                                                alpha = preset.alpha,
                                                erase = tool == FinalTool.Eraser
                                            )
                                        )
                                        val argb = editor.color.toArgb()
                                        recentColors.remove(argb); recentColors.add(0, argb)
                                        while (recentColors.size > 12) recentColors.removeAt(recentColors.lastIndex)
                                        project.touch()
                                    }
                                    FinalTool.Lasso -> {
                                        RasterEditingTools.selectInsidePolygon(editor, liveVisible)
                                        liveVisible = emptyList()
                                    }
                                    FinalTool.Line, FinalTool.Rectangle, FinalTool.Ellipse -> if (start != null && end != null && !editor.layer.locked) {
                                        val startScene = SelectionTools.inverseCamera(project, editor.frame, start)
                                        val endScene = SelectionTools.inverseCamera(project, editor.frame, end)
                                        val localStart = SelectionTools.inverseLayer(project, editor.layer, startScene)
                                        val localEnd = SelectionTools.inverseLayer(project, editor.layer, endScene)
                                        val preset = editor.brush
                                        when (tool) {
                                            FinalTool.Line -> RasterEditingTools.addLine(editor.layer, localStart, localEnd, editor.color.toArgb(), preset.width, preset.alpha)
                                            FinalTool.Rectangle -> RasterEditingTools.addRectangle(editor.layer, localStart, localEnd, editor.color.toArgb(), preset.width, preset.alpha)
                                            FinalTool.Ellipse -> RasterEditingTools.addEllipse(editor.layer, localStart, localEnd, editor.color.toArgb(), preset.width, preset.alpha)
                                            else -> Unit
                                        }
                                        project.touch()
                                    }
                                    else -> Unit
                                }
                                liveVisible = emptyList()
                                liveLocal = emptyList()
                                dragStartVisible = null
                                dragEndVisible = null
                            },
                            onDragCancel = {
                                liveVisible = emptyList(); liveLocal = emptyList(); dragStartVisible = null; dragEndVisible = null
                            }
                        )
                    }
                }
        ) {
            finalCheckerboard()
            onions.forEach { (bitmap, next) ->
                Image(
                    bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                    alpha = editor.onionAlpha * if (next) .8f else 1f
                )
            }
            Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / project.canvasWidth
                val sy = size.height / project.canvasHeight
                if (liveVisible.size >= 2) {
                    val p = Path().apply {
                        moveTo(liveVisible.first().x * sx, liveVisible.first().y * sy)
                        liveVisible.drop(1).forEach { lineTo(it.x * sx, it.y * sy) }
                    }
                    drawPath(
                        p,
                        if (tool == FinalTool.Lasso) MaterialTheme.colorScheme.primary else if (tool == FinalTool.Eraser) Color.Gray else editor.color,
                        style = Stroke(
                            width = if (tool == FinalTool.Lasso) 2.dp.toPx() else ((if (tool == FinalTool.Eraser) editor.eraser.width else editor.brush.width) * minOf(sx, sy)).coerceAtLeast(1f),
                            cap = StrokeCap.Round, join = StrokeJoin.Round
                        )
                    )
                }
                val start = dragStartVisible
                val end = dragEndVisible
                if (start != null && end != null && tool in listOf(FinalTool.Line, FinalTool.Rectangle, FinalTool.Ellipse)) {
                    val x1 = start.x * sx; val y1 = start.y * sy; val x2 = end.x * sx; val y2 = end.y * sy
                    val stroke = Stroke((editor.brush.width * minOf(sx, sy)).coerceAtLeast(1f))
                    when (tool) {
                        FinalTool.Line -> drawLine(editor.color, Offset(x1, y1), Offset(x2, y2), stroke.width)
                        FinalTool.Rectangle -> drawRect(editor.color, Offset(minOf(x1, x2), minOf(y1, y2)), androidx.compose.ui.geometry.Size(kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1)), style = stroke)
                        FinalTool.Ellipse -> drawOval(editor.color, Offset(minOf(x1, x2), minOf(y1, y2)), androidx.compose.ui.geometry.Size(kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1)), style = stroke)
                        else -> Unit
                    }
                }
            }
            if (editor.selectedIds.isNotEmpty() || editor.selectedRasterLayerIndex >= 0) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    LazyRow(Modifier.padding(horizontal = 5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        item { Text(if (editor.selectedRasterLayerIndex >= 0) editor.frame.layers[editor.selectedRasterLayerIndex].part.label else "${editor.selectedIds.size} selected", Modifier.padding(start = 6.dp)) }
                        item { TextButton(onClick = { history.checkpoint(); editor.duplicateSelection() }) { Text("Duplicate") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.rotateSelection(-15f) }) { Text("↺") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.rotateSelection(15f) }) { Text("↻") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.scaleSelection(.9f) }) { Text("−") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.scaleSelection(1.1f) }) { Text("+") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.flipSelectionHorizontal() }) { Text("Flip") } }
                        if (tool == FinalTool.ColourRepeat && editor.selectedRasterLayerIndex >= 0 && colourRepeatPoint != null) {
                            item { TextButton(onClick = { history.checkpoint(); SelectionTools.replaceRepeatedRasterColor(project, editor.frame.layers[editor.selectedRasterLayerIndex], colourRepeatPoint!!, editor.color.toArgb()) }) { Text("Recolour") } }
                        }
                        item { TextButton(onClick = { history.checkpoint(); editor.deleteSelection() }) { Text("Delete") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinalTimeline(editor: EditorState, history: ProjectHistory, onExactDuration: (Int) -> Unit) {
    val project = editor.project
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                count = project.frames.size,
                key = { index -> project.frames[index] }
            ) { index ->
                val frame = project.frames[index]
                val selected = index == editor.frameIndex
                val thumb = remember(project.revision, index) { finalThumbnail(project, index) }
                DisposableEffect(thumb) { onDispose { if (!thumb.isRecycled) thumb.recycle() } }
                var workingDuration by remember(frame) { mutableIntStateOf(frame.durationMs) }
                Surface(
                    modifier = Modifier.width((92f + frame.durationMs / 1000f * 24f).coerceIn(92f, 220f).dp).height(82.dp).clickable {
                        editor.frameIndex = index; editor.ensureIndices(); editor.clearSelection()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Box {
                        Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .63f)
                        Column(
                            Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.surface.copy(alpha = .82f), RoundedCornerShape(topEnd = 10.dp)).padding(5.dp)
                                .clickable { onExactDuration(index) }
                        ) {
                            Text(frame.label.ifBlank { "Frame ${index + 1}" }, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            Text("${"%.2f".format(frame.durationMs / 1000f)} s", style = MaterialTheme.typography.labelSmall)
                        }
                        Box(
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(24.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .35f), RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                                .pointerInput(frame, project.snapMs) {
                                    detectDragGestures(
                                        onDragStart = { history.checkpoint(); workingDuration = frame.durationMs },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            workingDuration = (workingDuration + (drag.x * 20f).roundToInt()).coerceIn(50, 120000)
                                            val snap = project.snapMs.coerceAtLeast(10)
                                            val snapped = (workingDuration / snap.toFloat()).roundToInt() * snap
                                            frame.durationMs = snapped.coerceIn(50, 120000)
                                            project.touch()
                                            if (change.position.x > size.width * 2f) scope.launch { listState.scrollBy((drag.x * .8f).coerceAtLeast(2f)) }
                                            if (change.position.x < -size.width) scope.launch { listState.scrollBy((drag.x * .8f).coerceAtMost(-2f)) }
                                        }
                                    )
                                }
                        )
                    }
                }
            }
            item { Spacer(Modifier.width(84.dp)) }
        }
        Text(
            "Drag a frame's right edge continuously · tap its duration for an exact value",
            Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalBrushSheet(editor: EditorState, eraser: Boolean, setMode: () -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val source = if (eraser) erasers else brushes
    val selected = if (eraser) editor.eraser else editor.brush
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (eraser) "Erasers · ${source.size}" else "Brushes · ${source.size}", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            SliderRow("Size", selected.width, 1f..180f, "${selected.width.roundToInt()} px") {
                if (eraser) editor.eraser = editor.eraser.copy(width = it) else editor.brush = editor.brush.copy(width = it)
            }
            SliderRow("Flow", selected.alpha, .05f..1f, "${(selected.alpha * 100).roundToInt()}%") {
                if (eraser) editor.eraser = editor.eraser.copy(alpha = it) else editor.brush = editor.brush.copy(alpha = it)
            }
            LazyColumn(Modifier.heightIn(max = 470.dp)) {
                items(source.filter { query.isBlank() || it.name.contains(query, true) || it.family.contains(query, true) }) { preset ->
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = { Text("${preset.family} · ${preset.width.roundToInt()} px · ${(preset.alpha * 100).roundToInt()}%") },
                        modifier = Modifier.clickable {
                            if (eraser) editor.eraser = preset else editor.brush = preset
                            setMode()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalColourSheet(editor: EditorState, recent: List<Int>, dismiss: () -> Unit) {
    var hex by remember(editor.color.toArgb()) { mutableStateOf("#${editor.color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')}") }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Colour", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(52.dp).background(editor.color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                OutlinedTextField(hex, { hex = it.take(9) }, label = { Text("ARGB hex") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    val raw = hex.removePrefix("#")
                    val argb = runCatching {
                        when (raw.length) {
                            6 -> (0xFF000000L or raw.toLong(16)).toInt()
                            8 -> raw.toLong(16).toInt()
                            else -> error("hex")
                        }
                    }.getOrNull()
                    if (argb != null) {
                        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(argb, hsv)
                        editor.hue = hsv[0]; editor.saturation = hsv[1]; editor.value = hsv[2]; editor.alpha = android.graphics.Color.alpha(argb) / 255f
                    }
                }) { Text("Apply") }
            }
            SliderRow("Hue", editor.hue, 0f..360f, "${editor.hue.roundToInt()}°") { editor.hue = it }
            SliderRow("Saturation", editor.saturation, 0f..1f, "${(editor.saturation * 100).roundToInt()}%") { editor.saturation = it }
            SliderRow("Brightness", editor.value, 0f..1f, "${(editor.value * 100).roundToInt()}%") { editor.value = it }
            SliderRow("Opacity", editor.alpha, 0f..1f, "${(editor.alpha * 100).roundToInt()}%") { editor.alpha = it }
            if (recent.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent) { argb ->
                        Box(Modifier.size(38.dp).background(Color(argb), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable {
                            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(argb, hsv)
                            editor.hue = hsv[0]; editor.saturation = hsv[1]; editor.value = hsv[2]; editor.alpha = android.graphics.Color.alpha(argb) / 255f
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalLayersSheet(editor: EditorState, history: ProjectHistory, onImportImage: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(14.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onImportImage) { Text("Import image") }
                TextButton(onClick = { history.checkpoint(); editor.addLayer() }) { Text("+ Layer") }
            }
            LazyColumn(Modifier.heightIn(max = 330.dp)) {
                items(editor.frame.layers) { layer ->
                    val index = editor.frame.layers.indexOf(layer)
                    val selected = index == editor.layerIndex
                    ListItem(
                        headlineContent = { Text(layer.name) },
                        supportingContent = { Text("${layer.part.label}${if (layer.hasRaster) " · image" else " · ${layer.strokes.size} strokes"}") },
                        leadingContent = { Checkbox(layer.visible, { history.checkpoint(); layer.visible = it; editor.project.touch() }) },
                        trailingContent = { Text(if (layer.locked) "Locked" else if (selected) "Selected" else "") },
                        modifier = Modifier.clickable { editor.selectLayer(index) }
                    )
                }
            }
            val layer = editor.layer
            Text("Selected: ${layer.name}", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(Part.entries) { part -> FilterChip(layer.part == part, { history.checkpoint(); layer.part = part; editor.project.touch() }, { Text(part.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TextButton(onClick = { history.checkpoint(); editor.duplicateLayer() }) { Text("Duplicate") }
                TextButton(onClick = { history.checkpoint(); layer.locked = !layer.locked; editor.project.touch() }) { Text(if (layer.locked) "Unlock" else "Lock") }
                TextButton(onClick = { history.checkpoint(); editor.moveLayer(-1) }) { Text("Up") }
                TextButton(onClick = { history.checkpoint(); editor.moveLayer(1) }) { Text("Down") }
                TextButton(onClick = { history.checkpoint(); editor.deleteLayer() }) { Text("Delete") }
            }
            SliderRow("Opacity", layer.opacity, 0f..1f, "${(layer.opacity * 100).roundToInt()}%") { layer.opacity = it; editor.project.touch() }
            SliderRow("X", layer.offsetX, -editor.project.canvasWidth.toFloat()..editor.project.canvasWidth.toFloat(), layer.offsetX.roundToInt().toString()) { layer.offsetX = it; editor.project.touch() }
            SliderRow("Y", layer.offsetY, -editor.project.canvasHeight.toFloat()..editor.project.canvasHeight.toFloat(), layer.offsetY.roundToInt().toString()) { layer.offsetY = it; editor.project.touch() }
            SliderRow("Scale", kotlin.math.abs(layer.scaleX), .1f..4f, "${"%.2f".format(kotlin.math.abs(layer.scaleX))}×") {
                val sign = if (layer.scaleX < 0f) -1f else 1f; layer.scaleX = it * sign; layer.scaleY = it; editor.project.touch()
            }
            SliderRow("Rotate", layer.rotationDeg, -180f..180f, "${layer.rotationDeg.roundToInt()}°") { layer.rotationDeg = it; editor.project.touch() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalOnionSheet(editor: EditorState, before: Int, after: Int, setBefore: (Int) -> Unit, setAfter: (Int) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Onion skin", style = MaterialTheme.typography.titleLarge)
            Text("Held frames appear once, regardless of how long their duration is.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SliderRow("Previous", before.toFloat(), 0f..5f, before.toString()) { setBefore(it.roundToInt().coerceIn(0, 5)) }
            SliderRow("Next", after.toFloat(), 0f..5f, after.toString()) { setAfter(it.roundToInt().coerceIn(0, 5)) }
            SliderRow("Opacity", editor.onionAlpha, .02f...6f, "${(editor.onionAlpha * 100).roundToInt()}%") { editor.onionAlpha = it }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalSmartSheet(editor: EditorState, history: ProjectHistory, busy: Boolean, onIdentify: () -> Unit, onMessage: (String) -> Unit, dismiss: () -> Unit) {
    var command by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (editor.project.mode == ProjectMode.ObjectShow) "Object Show smart tools" else "Smart tools", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onIdentify, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Identifying…" else "Identify + split limbs/parts") }
            FilledTonalButton(onClick = { history.checkpoint(); onMessage(if (SmartAnimationTools.insertInbetween(editor.project, editor.frameIndex)) "Inserted an in-between frame." else "A next frame is required.") }, modifier = Modifier.fillMaxWidth()) { Text("Make smoother · in-between") }
            FilledTonalButton(onClick = { history.checkpoint(); onMessage(if (SmartAnimationTools.continueMotion(editor.project, editor.frameIndex)) "Continued motion into a new frame." else "A previous frame is required.") }, modifier = Modifier.fillMaxWidth()) { Text("Continue motion") }
            FilledTonalButton(onClick = { history.checkpoint(); SmartAnimationTools.closeLoop(editor.project); onMessage("Added a loop-closing frame.") }, modifier = Modifier.fillMaxWidth()) { Text("Close loop") }
            OutlinedTextField(command, { command = it }, label = { Text("Natural-language command") }, placeholder = { Text("clone it, rotate 15, hold 2 seconds…") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { history.checkpoint(); onMessage(SmartAnimationTools.applyCommand(editor, command)) }, enabled = command.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Run locally") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalAudioSheet(project: ProjectState, history: ProjectHistory, fileName: String?, onImport: () -> Unit, onRemove: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Audio", style = MaterialTheme.typography.titleLarge)
            Text(fileName ?: "No audio imported", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text(if (fileName == null) "Import audio" else "Replace audio") }
            if (fileName != null) {
                SliderRow("Volume", project.audioVolume, 0f..1f, "${(project.audioVolume * 100).roundToInt()}%") { project.audioVolume = it; project.touch() }
                SliderRow("Offset", project.audioOffsetMs.toFloat(), -10000f..10000f, "${project.audioOffsetMs} ms") { project.audioOffsetMs = it.roundToInt(); project.touch() }
                Text("MP4 export now carries this audio track and timing offset.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { history.checkpoint(); onRemove() }, modifier = Modifier.fillMaxWidth()) { Text("Remove audio") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalProjectSheet(project: ProjectState, history: ProjectHistory, onSave: () -> Unit, dismiss: () -> Unit) {
    var name by remember(project.name) { mutableStateOf(project.name) }
    var width by remember(project.canvasWidth) { mutableStateOf(project.canvasWidth.toString()) }
    var height by remember(project.canvasHeight) { mutableStateOf(project.canvasHeight.toString()) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Project", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it.take(64) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(width, { width = it.filter(Char::isDigit).take(4) }, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(height, { height = it.filter(Char::isDigit).take(4) }, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(ProjectMode.entries) { mode -> FilterChip(project.mode == mode, { history.checkpoint(); project.mode = mode; project.touch() }, { Text(mode.label) }) }
            }
            SliderRow("FPS", project.fps.toFloat(), 12f..60f, project.fps.toString()) { project.fps = it.roundToInt(); project.touch() }
            SliderRow("Snap", project.snapMs.toFloat(), 10f..1000f, "${project.snapMs} ms") { project.snapMs = it.roundToInt().coerceAtLeast(10); project.touch() }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(project.loopPlayback, { project.loopPlayback = !project.loopPlayback; project.touch() }, { Text("Loop") })
                FilterChip(project.backgroundArgb == 0x00000000, { history.checkpoint(); project.backgroundArgb = 0; project.touch() }, { Text("Transparent") })
                FilterChip(project.backgroundArgb == 0xFFFFFFFF.toInt(), { history.checkpoint(); project.backgroundArgb = 0xFFFFFFFF.toInt(); project.touch() }, { Text("White") })
            }
            Button(onClick = {
                history.checkpoint()
                project.name = name.trim().ifBlank { "Untitled animation" }
                project.canvasWidth = (width.toIntOrNull() ?: project.canvasWidth).coerceIn(64, 4096)
                project.canvasHeight = (height.toIntOrNull() ?: project.canvasHeight).coerceIn(64, 4096)
                project.touch(); onSave(); dismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text("Apply and save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalCameraSheet(editor: EditorState, history: ProjectHistory, viewZoom: Float, setViewZoom: (Float) -> Unit, resetView: () -> Unit, dismiss: () -> Unit) {
    val frame = editor.frame
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Camera", style = MaterialTheme.typography.titleLarge)
            SliderRow("Pan X", frame.cameraX, -editor.project.canvasWidth.toFloat()..editor.project.canvasWidth.toFloat(), frame.cameraX.roundToInt().toString()) { frame.cameraX = it; editor.project.touch() }
            SliderRow("Pan Y", frame.cameraY, -editor.project.canvasHeight.toFloat()..editor.project.canvasHeight.toFloat(), frame.cameraY.roundToInt().toString()) { frame.cameraY = it; editor.project.touch() }
            SliderRow("Zoom", frame.cameraZoom, .25f..4f, "${"%.2f".format(frame.cameraZoom)}×") { frame.cameraZoom = it; editor.project.touch() }
            SliderRow("Rotate", frame.cameraRotation, -180f..180f, "${frame.cameraRotation.roundToInt()}°") { frame.cameraRotation = it; editor.project.touch() }
            Divider()
            Text("Editor view", style = MaterialTheme.typography.titleSmall)
            SliderRow("View zoom", viewZoom, .5f..4f, "${"%.2f".format(viewZoom)}×", setViewZoom)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { history.checkpoint(); frame.cameraX = 0f; frame.cameraY = 0f; frame.cameraZoom = 1f; frame.cameraRotation = 0f; editor.project.touch() }) { Text("Reset camera") }
                TextButton(onClick = resetView) { Text("Fit view") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalToolsSheet(current: FinalTool, onTool: (FinalTool) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 26.dp)) {
            Text("Tools", style = MaterialTheme.typography.titleLarge)
            FinalTool.entries.forEach { tool ->
                ListItem(
                    headlineContent = { Text(tool.label) },
                    supportingContent = {
                        Text(
                            when (tool) {
                                FinalTool.Fill -> "Flood fill to a separate editable layer"
                                FinalTool.Eyedropper -> "Pick a colour from the rendered frame"
                                FinalTool.Lasso -> "Freehand-select vector strokes"
                                FinalTool.Line, FinalTool.Rectangle, FinalTool.Ellipse -> "Draw a clean ${tool.label.lowercase()}"
                                FinalTool.Hand -> "Pan the editor view"
                                FinalTool.Select -> "Tap a semantic limb/part; all artwork in it is selected"
                                FinalTool.ColourRepeat -> "Select/recolour repeated colours"
                                else -> "Drawing tool"
                            }
                        )
                    },
                    trailingContent = { if (tool == current) Text("Active") },
                    modifier = Modifier.clickable { onTool(tool) }
                )
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(display, Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FinalDurationDialog(frame: FrameState, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var value by remember(frame) { mutableStateOf("${frame.durationMs / 1000f}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frame duration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter seconds. The frame is stored once and held for this duration.")
                OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text("Seconds") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(((value.toFloatOrNull() ?: 1f) * 1000f).roundToInt().coerceIn(50, 120000)) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FinalExportItem(title: String, subtitle: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(title) }, supportingText = { Text(subtitle) }, onClick = onClick)
}

@Composable
private fun finalCheckerboard() {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 22f
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = 0f; var col = 0
            while (x < size.width) {
                drawRect(if ((row + col) % 2 == 0) Color(0xFFE8E8E8) else Color(0xFFD3D3D3), Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
                x += cell; col++
            }
            y += cell; row++
        }
    }
}

private fun Offset.finalProjectPoint(size: IntSize, project: ProjectState): CanvasPoint {
    if (size.width <= 0 || size.height <= 0) return CanvasPoint(0f, 0f)
    return CanvasPoint(
        (x / size.width * project.canvasWidth).coerceIn(0f, project.canvasWidth.toFloat()),
        (y / size.height * project.canvasHeight).coerceIn(0f, project.canvasHeight.toFloat())
    )
}

private fun finalPreview(project: ProjectState, index: Int): Bitmap {
    val full = FrameRenderer.render(project, index)
    val maxSide = 1400
    if (maxOf(full.width, full.height) <= maxSide) return full
    val scale = maxSide.toFloat() / maxOf(full.width, full.height)
    return Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt(), (full.height * scale).roundToInt(), true).also { full.recycle() }
}

private fun finalThumbnail(project: ProjectState, index: Int): Bitmap {
    val full = FrameRenderer.render(project, index)
    val thumb = Bitmap.createScaledBitmap(full, 180, 112, true)
    full.recycle()
    return thumb
}
