package com.frameflow.app

import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullEditorScreen(
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
    var sheet by remember { mutableStateOf<FullSheet?>(null) }
    var showExportMenu by remember { mutableStateOf(false) }
    var viewZoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var handMode by remember { mutableStateOf(false) }
    var smartBusy by remember { mutableStateOf(false) }
    var smartMessage by remember { mutableStateOf<String?>(null) }
    val recentColors = remember { mutableStateListOf<Int>() }

    val audioFile = remember(project.audioFileName, project.revision) { repository.audioFile(project) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(audioFile?.absolutePath) {
        val player = audioFile?.let { file ->
            runCatching {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setVolume(project.audioVolume, project.audioVolume)
                }
            }.getOrNull()
        }
        mediaPlayer = player
        onDispose {
            runCatching { player?.stop() }
            player?.release()
            if (mediaPlayer === player) mediaPlayer = null
        }
    }
    LaunchedEffect(project.audioVolume) {
        mediaPlayer?.setVolume(project.audioVolume, project.audioVolume)
    }

    LaunchedEffect(editor.playing) {
        if (!editor.playing) {
            mediaPlayer?.pause()
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                val timelineStart = project.frames.take(editor.frameIndex).sumOf { it.durationMs }
                val waitMs = project.audioOffsetMs - timelineStart
                if (waitMs > 0) delay(waitMs.toLong())
                if (editor.playing) {
                    val audioPosition = (timelineStart - project.audioOffsetMs).coerceAtLeast(0)
                    runCatching {
                        mediaPlayer?.seekTo(audioPosition)
                        mediaPlayer?.start()
                    }
                }
            }
            while (editor.playing) {
                editor.ensureIndices()
                delay(editor.frame.durationMs.toLong())
                if (!editor.playing) break
                if (editor.frameIndex >= project.frames.lastIndex) {
                    if (project.loopPlayback) editor.frameIndex = 0 else {
                        editor.playing = false
                        break
                    }
                } else editor.frameIndex++
                editor.layerIndex = editor.layerIndex.coerceIn(editor.frame.layers.indices)
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
                            "${project.mode.label} · ${project.frames.size} frames · ${"%.1f".format(project.totalDurationMs / 1000f)}s",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    TextButton(enabled = history.canUndo, onClick = { if (history.undo()) editor.ensureIndices() }) { Text("Undo") }
                    TextButton(enabled = history.canRedo, onClick = { if (history.redo()) editor.ensureIndices() }) { Text("Redo") }
                    TextButton(onClick = { editor.playing = !editor.playing }) { Text(if (editor.playing) "Stop" else "Play") }
                    Box {
                        TextButton(onClick = { showExportMenu = true }) { Text("Export") }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                            FullExportItem("MP4 video", "H.264 animation", onExportMp4) { showExportMenu = false }
                            FullExportItem("Animated GIF", "Uses each frame's hold duration", onExportGif) { showExportMenu = false }
                            FullExportItem("Current frame PNG", "Full-resolution still", onExportCurrentPng) { showExportMenu = false }
                            FullExportItem("All frames ZIP", "PNG sequence + project + audio", onExportFramesZip) { showExportMenu = false }
                            FullExportItem("Editable project", ".frameflow document", onExportProject) { showExportMenu = false }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                history.checkpoint()
                editor.cloneFrame()
            }, text = { Text("+ Clone") })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(selected = editor.onionPrevious, onClick = { editor.onionPrevious = !editor.onionPrevious }, label = { Text("Prev onion") })
                }
                item {
                    FilterChip(selected = editor.onionNext, onClick = { editor.onionNext = !editor.onionNext }, label = { Text("Next onion") })
                }
                item {
                    FilterChip(selected = handMode, onClick = { handMode = !handMode }, label = { Text(if (handMode) "Pan mode" else "Draw mode") })
                }
                item { AssistChip(onClick = { viewZoom = (viewZoom - .25f).coerceAtLeast(.5f) }, label = { Text("−") }) }
                item { AssistChip(onClick = { viewZoom = (viewZoom + .25f).coerceAtMost(4f) }, label = { Text("+") }) }
                item { AssistChip(onClick = { viewZoom = 1f; panX = 0f; panY = 0f }, label = { Text("Fit") }) }
                item { AssistChip(onClick = { sheet = FullSheet.Camera }, label = { Text("Camera") }) }
            }

            FullCanvas(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                editor = editor,
                history = history,
                zoom = viewZoom,
                panX = panX,
                panY = panY,
                handMode = handMode,
                onPan = { dx, dy -> panX += dx; panY += dy },
                recentColors = recentColors
            )

            FullTimeline(editor, history)

            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { FullToolButton("Brush", editor.tool == Tool.Brush) { sheet = FullSheet.Brushes } }
                item { FullToolButton("Eraser", editor.tool == Tool.Eraser) { sheet = FullSheet.Erasers } }
                item { FullToolButton("Colour", false) { sheet = FullSheet.Colour } }
                item { FullToolButton("Select", editor.tool == Tool.SmartSelect) { editor.tool = Tool.SmartSelect } }
                item { FullToolButton("Colour repeat", editor.tool == Tool.ColorRepeat) { editor.tool = Tool.ColorRepeat } }
                item { FullToolButton("Layers", false) { sheet = FullSheet.Layers } }
                item { FullToolButton("Smart", false) { sheet = FullSheet.Smart } }
                item { FullToolButton("Audio", false) { sheet = FullSheet.Audio } }
                item { FullToolButton("Project", false) { sheet = FullSheet.Project } }
            }
        }
    }

    smartMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { smartMessage = null },
            title = { Text("Frameflow") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { smartMessage = null }) { Text("OK") } }
        )
    }

    when (sheet) {
        FullSheet.Brushes -> FullBrushSheet(editor, false) { sheet = null }
        FullSheet.Erasers -> FullBrushSheet(editor, true) { sheet = null }
        FullSheet.Colour -> FullColourSheet(editor, recentColors) { sheet = null }
        FullSheet.Layers -> FullLayersSheet(editor, history, onImportImage) { sheet = null }
        FullSheet.Camera -> FullCameraSheet(editor, history) { sheet = null }
        FullSheet.Audio -> FullAudioSheet(project, history, audioFile?.name, onImportAudio, {
            repository.removeAudio(project)
        }) { sheet = null }
        FullSheet.Project -> FullProjectSheet(project, history, onSave) { sheet = null }
        FullSheet.Smart -> FullSmartSheet(
            editor = editor,
            history = history,
            busy = smartBusy,
            onIdentify = {
                val layer = editor.layer
                if (!layer.hasRaster) {
                    smartMessage = "Import an image or select a raster layer first."
                } else {
                    smartBusy = true
                    SmartLimbIdentifier.splitLayer(
                        layer,
                        onSuccess = { parts ->
                            smartBusy = false
                            if (parts.isEmpty()) {
                                smartMessage = "No usable parts were found. You can still tag layers manually."
                            } else {
                                history.checkpoint()
                                val index = editor.layerIndex
                                val original = editor.frame.layers.removeAt(index)
                                val newLayers = parts.map { part ->
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
                                editor.frame.layers.addAll(index, newLayers)
                                editor.layerIndex = index.coerceIn(editor.frame.layers.indices)
                                editor.selectLayer(editor.layerIndex)
                                project.touch()
                                smartMessage = "Identified ${parts.size} parts. Tap a limb in Select mode and the whole painted limb moves/deletes together."
                            }
                        },
                        onFailure = {
                            smartBusy = false
                            smartMessage = "Limb identification failed: ${it.message ?: "unknown error"}"
                        }
                    )
                }
            },
            onMessage = { smartMessage = it },
            dismiss = { sheet = null }
        )
        null -> Unit
    }
}

private enum class FullSheet { Brushes, Erasers, Colour, Layers, Smart, Audio, Project, Camera }

@Composable
private fun FullCanvas(
    modifier: Modifier,
    editor: EditorState,
    history: ProjectHistory,
    zoom: Float,
    panX: Float,
    panY: Float,
    handMode: Boolean,
    onPan: (Float, Float) -> Unit,
    recentColors: MutableList<Int>
) {
    val project = editor.project
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var livePoints by remember { mutableStateOf<List<CanvasPoint>>(emptyList()) }
    var moveCheckpoint by remember { mutableStateOf(false) }
    var lastColorPoint by remember { mutableStateOf<CanvasPoint?>(null) }
    val preview = remember(project.revision, editor.frameIndex) { renderPreview(project, editor.frameIndex) }
    val previous = remember(project.revision, editor.frameIndex, editor.onionPrevious) {
        if (editor.onionPrevious && editor.frameIndex > 0) renderPreview(project, editor.frameIndex - 1) else null
    }
    val next = remember(project.revision, editor.frameIndex, editor.onionNext) {
        if (editor.onionNext && editor.frameIndex < project.frames.lastIndex) renderPreview(project, editor.frameIndex + 1) else null
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = project.canvasWidth.toFloat() / project.canvasHeight
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
                .pointerInput(handMode, editor.tool, editor.frameIndex, editor.layerIndex, project.revision, canvasSize) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            moveCheckpoint = false
                            if (handMode) return@detectDragGestures
                            val point = pos.fullToProject(canvasSize, project)
                            lastColorPoint = point
                            when (editor.tool) {
                                Tool.Brush, Tool.Eraser -> if (!editor.layer.locked) {
                                    history.checkpoint()
                                    livePoints = listOf(SelectionTools.inverseLayer(project, editor.layer, SelectionTools.inverseCamera(project, editor.frame, point)))
                                }
                                Tool.SmartSelect -> SelectionTools.selectPartAt(editor, point)
                                Tool.ColorRepeat -> SelectionTools.selectRepeatedColorAt(editor, point)
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            if (handMode) {
                                onPan(drag.x, drag.y)
                                return@detectDragGestures
                            }
                            when (editor.tool) {
                                Tool.Brush, Tool.Eraser -> if (!editor.layer.locked) {
                                    val point = change.position.fullToProject(canvasSize, project)
                                    livePoints = livePoints + SelectionTools.inverseLayer(project, editor.layer, SelectionTools.inverseCamera(project, editor.frame, point))
                                }
                                Tool.SmartSelect, Tool.ColorRepeat -> if (editor.selectedIds.isNotEmpty() || editor.selectedRasterLayerIndex >= 0) {
                                    if (!moveCheckpoint) {
                                        history.checkpoint()
                                        moveCheckpoint = true
                                    }
                                    val dx = if (canvasSize.width == 0) 0f else drag.x / canvasSize.width * project.canvasWidth
                                    val dy = if (canvasSize.height == 0) 0f else drag.y / canvasSize.height * project.canvasHeight
                                    editor.moveSelection(dx, dy)
                                }
                            }
                        },
                        onDragEnd = {
                            if (!handMode && livePoints.isNotEmpty() && !editor.layer.locked && (editor.tool == Tool.Brush || editor.tool == Tool.Eraser)) {
                                val preset = if (editor.tool == Tool.Eraser) editor.eraser else editor.brush
                                editor.layer.strokes.add(
                                    StrokeData(
                                        points = livePoints,
                                        colorArgb = editor.color.toArgb(),
                                        width = preset.width,
                                        alpha = preset.alpha,
                                        erase = editor.tool == Tool.Eraser
                                    )
                                )
                                val argb = editor.color.toArgb()
                                recentColors.remove(argb)
                                recentColors.add(0, argb)
                                while (recentColors.size > 12) recentColors.removeAt(recentColors.lastIndex)
                                project.touch()
                            }
                            livePoints = emptyList()
                        },
                        onDragCancel = { livePoints = emptyList() }
                    )
                }
        ) {
            FullCheckerboard()
            previous?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = editor.onionAlpha) }
            next?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = editor.onionAlpha) }
            Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                if (livePoints.size >= 2) {
                    val sx = size.width / project.canvasWidth
                    val sy = size.height / project.canvasHeight
                    val path = Path().apply {
                        moveTo(livePoints.first().x * sx, livePoints.first().y * sy)
                        livePoints.drop(1).forEach { lineTo(it.x * sx, it.y * sy) }
                    }
                    val preset = if (editor.tool == Tool.Eraser) editor.eraser else editor.brush
                    drawPath(
                        path,
                        color = if (editor.tool == Tool.Eraser) Color(0x889E9E9E) else editor.color,
                        style = Stroke((preset.width * minOf(sx, sy)).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            if (editor.selectedIds.isNotEmpty() || editor.selectedRasterLayerIndex >= 0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    LazyRow(
                        Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            Text(
                                if (editor.selectedRasterLayerIndex >= 0) editor.frame.layers[editor.selectedRasterLayerIndex].part.label else "${editor.selectedIds.size} strokes",
                                Modifier.padding(start = 6.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        item { TextButton(onClick = { history.checkpoint(); editor.duplicateSelection() }) { Text("Duplicate") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.rotateSelection(-15f) }) { Text("↺") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.rotateSelection(15f) }) { Text("↻") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.scaleSelection(.9f) }) { Text("Smaller") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.scaleSelection(1.1f) }) { Text("Bigger") } }
                        item { TextButton(onClick = { history.checkpoint(); editor.flipSelectionHorizontal() }) { Text("Flip") } }
                        if (editor.tool == Tool.ColorRepeat && editor.selectedRasterLayerIndex >= 0 && lastColorPoint != null) {
                            item {
                                TextButton(onClick = {
                                    history.checkpoint()
                                    SelectionTools.replaceRepeatedRasterColor(project, editor.frame.layers[editor.selectedRasterLayerIndex], lastColorPoint!!, editor.color.toArgb())
                                }) { Text("Recolour repeat") }
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
private fun FullCheckerboard() {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 22f
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                drawRect(if ((row + col) % 2 == 0) Color(0xFFE8E8E8) else Color(0xFFD2D2D2), Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
                x += cell; col++
            }
            y += cell; row++
        }
    }
}

@Composable
private fun FullTimeline(editor: EditorState, history: ProjectHistory) {
    val project = editor.project
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            itemsIndexed(project.frames, key = { index, _ -> "${index}-${project.revision}" }) { index, frame ->
                val selected = index == editor.frameIndex
                val thumb = remember(project.revision, index) { renderThumbnail(project, index) }
                Surface(
                    modifier = Modifier.width(112.dp).height(78.dp).clickable {
                        editor.frameIndex = index
                        editor.ensureIndices()
                        editor.clearSelection()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Box {
                        Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .62f)
                        Column(Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.surface.copy(alpha = .78f), RoundedCornerShape(topEnd = 10.dp)).padding(5.dp)) {
                            Text(frame.label.ifBlank { "Frame ${index + 1}" }, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            Text("${"%.2f".format(frame.durationMs / 1000f)}s", style = MaterialTheme.typography.labelSmall)
                        }
                        Box(
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(14.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .32f), RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                                .pointerInput(index, project.snapMs) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val raw = frame.durationMs + (drag.x * 18f).roundToInt()
                                        val snap = project.snapMs.coerceAtLeast(10)
                                        frame.durationMs = ((raw / snap.toFloat()).roundToInt() * snap).coerceIn(50, 60000)
                                        project.touch()
                                    }
                                }
                        )
                    }
                }
            }
            item { Spacer(Modifier.width(80.dp)) }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item { FilledTonalButton(onClick = { history.checkpoint(); editor.cloneFrame() }) { Text("Clone") } }
            item { TextButton(onClick = { history.checkpoint(); editor.addBlankFrame() }) { Text("Blank") } }
            item { TextButton(onClick = { history.checkpoint(); editor.deleteFrame() }) { Text("Delete") } }
            item { TextButton(onClick = { history.checkpoint(); editor.moveFrame(-1) }, enabled = editor.frameIndex > 0) { Text("← Frame") } }
            item { TextButton(onClick = { history.checkpoint(); editor.moveFrame(1) }, enabled = editor.frameIndex < project.frames.lastIndex) { Text("Frame →") } }
            item { Text("${project.fps} fps export", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun FullToolButton(text: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        )
    ) { Text(text) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullBrushSheet(editor: EditorState, eraserMode: Boolean, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var family by remember { mutableStateOf<String?>(null) }
    val source = if (eraserMode) erasers else brushes
    val filtered = source.filter {
        (query.isBlank() || it.name.contains(query, true) || it.family.contains(query, true)) && (family == null || it.family == family)
    }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(if (eraserMode) "Erasers · ${erasers.size}" else "Brushes · ${brushes.size}", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (!eraserMode) {
                LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    item { FilterChip(family == null, { family = null }, { Text("All") }) }
                    items(brushFamilies) { item -> FilterChip(family == item, { family = item }, { Text(item) }) }
                }
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(filtered) { preset ->
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = { Text("${preset.family} · ${preset.width.toInt()} px · ${(preset.alpha * 100).toInt()}%") },
                        modifier = Modifier.clickable {
                            if (eraserMode) { editor.eraser = preset; editor.tool = Tool.Eraser }
                            else { editor.brush = preset; editor.tool = Tool.Brush }
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullColourSheet(editor: EditorState, recentColors: List<Int>, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Colour", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).background(editor.color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text("#${editor.color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')}")
            }
            FullSlider("Hue", editor.hue, 0f..360f) { editor.hue = it }
            FullSlider("Saturation", editor.saturation, 0f..1f) { editor.saturation = it }
            FullSlider("Brightness", editor.value, 0f..1f) { editor.value = it }
            FullSlider("Opacity", editor.alpha, 0f..1f) { editor.alpha = it }
            if (recentColors.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recentColors) { argb ->
                        Box(Modifier.size(38.dp).background(Color(argb), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable {
                            val c = Color(argb)
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(argb, hsv)
                            editor.hue = hsv[0]; editor.saturation = hsv[1]; editor.value = hsv[2]; editor.alpha = c.alpha
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullLayersSheet(editor: EditorState, history: ProjectHistory, onImportImage: () -> Unit, dismiss: () -> Unit) {
    var rename by remember { mutableStateOf<LayerState?>(null) }
    var renameText by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onImportImage) { Text("Import image") }
            }
            LazyRow(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                item { FilledTonalButton(onClick = { history.checkpoint(); editor.addLayer() }) { Text("+ Layer") } }
                item { FilledTonalButton(onClick = { history.checkpoint(); editor.duplicateLayer() }) { Text("Duplicate") } }
                item { TextButton(onClick = { history.checkpoint(); editor.deleteLayer() }) { Text("Delete") } }
                item { TextButton(onClick = { history.checkpoint(); editor.moveLayer(-1) }, enabled = editor.layerIndex > 0) { Text("Up") } }
                item { TextButton(onClick = { history.checkpoint(); editor.moveLayer(1) }, enabled = editor.layerIndex < editor.frame.layers.lastIndex) { Text("Down") } }
            }
            LazyColumn(Modifier.heightIn(max = 330.dp)) {
                items(editor.frame.layers.indices.toList()) { index ->
                    val layer = editor.frame.layers[index]
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { editor.selectLayer(index) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (index == editor.layerIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(layer.name, fontWeight = FontWeight.Medium)
                                Text("${layer.part.label} · ${if (layer.hasRaster) "image + " else ""}${layer.strokes.size} strokes", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { history.checkpoint(); layer.visible = !layer.visible; editor.project.touch() }) { Text(if (layer.visible) "👁" else "Hidden") }
                            TextButton(onClick = { history.checkpoint(); layer.locked = !layer.locked; editor.project.touch() }) { Text(if (layer.locked) "🔒" else "Lock") }
                        }
                    }
                }
            }
            val layer = editor.layer
            Text("Selected layer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TextButton(onClick = { rename = layer; renameText = layer.name }) { Text("Rename") }
                Part.entries.forEach { part ->
                    FilterChip(layer.part == part, { history.checkpoint(); layer.part = part; editor.project.touch() }, { Text(part.label) })
                }
            }
            FullSlider("Opacity", layer.opacity, 0f..1f) { history.checkpoint(); layer.opacity = it; editor.project.touch() }
            FullSlider("X", layer.offsetX, -editor.project.canvasWidth.toFloat()..editor.project.canvasWidth.toFloat()) { layer.offsetX = it; editor.project.touch() }
            FullSlider("Y", layer.offsetY, -editor.project.canvasHeight.toFloat()..editor.project.canvasHeight.toFloat()) { layer.offsetY = it; editor.project.touch() }
            FullSlider("Scale", kotlin.math.abs(layer.scaleX), .1f..4f) {
                val sign = if (layer.scaleX < 0) -1f else 1f
                layer.scaleX = it * sign; layer.scaleY = it; editor.project.touch()
            }
            FullSlider("Rotation", layer.rotationDeg, -180f..180f) { layer.rotationDeg = it; editor.project.touch() }
        }
    }
    rename?.let { layer ->
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename layer") },
            text = { OutlinedTextField(renameText, { renameText = it.take(48) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { history.checkpoint(); layer.name = renameText.trim().ifBlank { "Layer" }; editor.project.touch(); rename = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullCameraSheet(editor: EditorState, history: ProjectHistory, dismiss: () -> Unit) {
    val frame = editor.frame
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Camera · current frame", style = MaterialTheme.typography.titleLarge)
            Text("Pan, zoom or rotate the entire scene without redrawing anything.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FullSlider("Pan X", frame.cameraX, -editor.project.canvasWidth.toFloat()..editor.project.canvasWidth.toFloat()) { frame.cameraX = it; editor.project.touch() }
            FullSlider("Pan Y", frame.cameraY, -editor.project.canvasHeight.toFloat()..editor.project.canvasHeight.toFloat()) { frame.cameraY = it; editor.project.touch() }
            FullSlider("Zoom", frame.cameraZoom, .25f..4f) { frame.cameraZoom = it; editor.project.touch() }
            FullSlider("Rotate", frame.cameraRotation, -180f..180f) { frame.cameraRotation = it; editor.project.touch() }
            FilledTonalButton(onClick = { history.checkpoint(); frame.cameraX = 0f; frame.cameraY = 0f; frame.cameraZoom = 1f; frame.cameraRotation = 0f; editor.project.touch() }, modifier = Modifier.fillMaxWidth()) { Text("Reset camera") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullAudioSheet(
    project: ProjectState,
    history: ProjectHistory,
    fileName: String?,
    onImportAudio: () -> Unit,
    onRemove: () -> Unit,
    dismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Audio", style = MaterialTheme.typography.titleLarge)
            Text(fileName ?: "No audio track imported", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onImportAudio, modifier = Modifier.fillMaxWidth()) { Text(if (fileName == null) "Import audio" else "Replace audio") }
            if (fileName != null) {
                FullSlider("Volume", project.audioVolume, 0f..1f) { project.audioVolume = it; project.touch() }
                FullSlider("Offset", project.audioOffsetMs.toFloat(), -10000f..10000f) { project.audioOffsetMs = it.toInt(); project.touch() }
                TextButton(onClick = { history.checkpoint(); onRemove() }, modifier = Modifier.fillMaxWidth()) { Text("Remove audio") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullProjectSheet(project: ProjectState, history: ProjectHistory, onSave: () -> Unit, dismiss: () -> Unit) {
    var name by remember(project.name) { mutableStateOf(project.name) }
    var width by remember(project.canvasWidth) { mutableStateOf(project.canvasWidth.toString()) }
    var height by remember(project.canvasHeight) { mutableStateOf(project.canvasHeight.toString()) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Project", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it.take(64) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(width, { width = it.filter(Char::isDigit).take(4) }, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(height, { height = it.filter(Char::isDigit).take(4) }, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Button(onClick = {
                history.checkpoint()
                project.name = name.trim().ifBlank { "Untitled animation" }
                project.canvasWidth = (width.toIntOrNull() ?: project.canvasWidth).coerceIn(64, 4096)
                project.canvasHeight = (height.toIntOrNull() ?: project.canvasHeight).coerceIn(64, 4096)
                project.touch(); onSave()
            }, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
            Text("Mode", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ProjectMode.entries) { mode -> FilterChip(project.mode == mode, { history.checkpoint(); project.mode = mode; project.touch() }, { Text(mode.label) }) }
            }
            FullSlider("FPS", project.fps.toFloat(), 12f..60f) { project.fps = it.roundToInt(); project.touch() }
            FullSlider("Snap ms", project.snapMs.toFloat(), 10f..1000f) { project.snapMs = it.roundToInt().coerceAtLeast(10); project.touch() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(project.loopPlayback, { project.loopPlayback = !project.loopPlayback; project.touch() }, { Text("Loop playback") })
                FilterChip(project.backgroundArgb == 0x00000000, { history.checkpoint(); project.backgroundArgb = 0x00000000; project.touch() }, { Text("Transparent") })
                FilterChip(project.backgroundArgb == 0xFFFFFFFF.toInt(), { history.checkpoint(); project.backgroundArgb = 0xFFFFFFFF.toInt(); project.touch() }, { Text("White") })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullSmartSheet(
    editor: EditorState,
    history: ProjectHistory,
    busy: Boolean,
    onIdentify: () -> Unit,
    onMessage: (String) -> Unit,
    dismiss: () -> Unit
) {
    var command by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Smart", style = MaterialTheme.typography.titleLarge)
            Text("Local-first helpers. Human limb detection uses the bundled on-device pose model; non-human art falls back to geometry splitting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onIdentify, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Identifying…" else "AI identify + split limbs")
            }
            FilledTonalButton(onClick = {
                history.checkpoint()
                onMessage(if (SmartAnimationTools.insertInbetween(editor.project, editor.frameIndex)) "Inserted an in-between frame." else "A next frame is required.")
            }, modifier = Modifier.fillMaxWidth()) { Text("Make smoother · insert in-between") }
            FilledTonalButton(onClick = {
                history.checkpoint()
                onMessage(if (SmartAnimationTools.continueMotion(editor.project, editor.frameIndex)) "Continued the previous motion into a new frame." else "A previous frame is required.")
            }, modifier = Modifier.fillMaxWidth()) { Text("Continue motion") }
            FilledTonalButton(onClick = { history.checkpoint(); SmartAnimationTools.closeLoop(editor.project); onMessage("Added a loop-closing frame.") }, modifier = Modifier.fillMaxWidth()) { Text("Close loop") }
            OutlinedTextField(command, { command = it }, label = { Text("Command") }, placeholder = { Text("clone it, rotate 15, hold 2 seconds…") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { history.checkpoint(); onMessage(SmartAnimationTools.applyCommand(editor, command)) }, enabled = command.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Run command") }
        }
    }
}

@Composable
private fun FullSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(if (range.endInclusive > 10f) value.roundToInt().toString() else "%.2f".format(value), Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FullExportItem(title: String, subtitle: String, action: () -> Unit, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        supportingText = { Text(subtitle) },
        onClick = { close(); action() }
    )
}

private fun Offset.fullToProject(size: IntSize, project: ProjectState): CanvasPoint {
    if (size.width <= 0 || size.height <= 0) return CanvasPoint(0f, 0f)
    return CanvasPoint(
        (x / size.width * project.canvasWidth).coerceIn(0f, project.canvasWidth.toFloat()),
        (y / size.height * project.canvasHeight).coerceIn(0f, project.canvasHeight.toFloat())
    )
}

private fun renderPreview(project: ProjectState, index: Int): Bitmap {
    val full = FrameRenderer.render(project, index)
    val maxSide = 1400
    if (maxOf(full.width, full.height) <= maxSide) return full
    val scale = maxSide.toFloat() / maxOf(full.width, full.height)
    return Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt(), (full.height * scale).roundToInt(), true).also { full.recycle() }
}

private fun renderThumbnail(project: ProjectState, index: Int): Bitmap {
    val full = FrameRenderer.render(project, index)
    val thumb = Bitmap.createScaledBitmap(full, 160, 100, true)
    full.recycle()
    return thumb
}
