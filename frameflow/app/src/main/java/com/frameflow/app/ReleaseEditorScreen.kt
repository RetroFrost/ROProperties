package com.frameflow.app

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReleaseTool(val label: String) {
    Brush("Brush"), Eraser("Eraser"), SelectPart("Select limb"), ColourRepeat("Colour repeat"),
    Fill("Fill"), Eyedropper("Eyedropper"), Lasso("Lasso"), Line("Line"),
    Rectangle("Rectangle"), Ellipse("Ellipse"), Pan("Pan")
}

enum class ReleaseSheet { Brushes, Erasers, Colour, Layers, Onion, Smart, LazyChat, Audio, Project, Camera, Tools }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseEditorScreen(
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
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(ReleaseTool.Brush) }
    var sheet by remember { mutableStateOf<ReleaseSheet?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var viewZoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var onionBefore by remember { mutableIntStateOf(1) }
    var onionAfter by remember { mutableIntStateOf(0) }
    var onionAlpha by remember { mutableFloatStateOf(.18f) }
    var onionSelectedLayerOnly by remember { mutableStateOf(false) }
    var durationDialogIndex by remember { mutableIntStateOf(-1) }
    var smartBusy by remember { mutableStateOf(false) }
    val recentColours = remember { mutableStateListOf<Int>() }

    val audioFile = remember(project.audioFileName, project.revision) { repository.audioFile(project) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
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
            runCatching { created?.release() }
            if (player === created) player = null
        }
    }
    LaunchedEffect(project.audioVolume) {
        runCatching { player?.setVolume(project.audioVolume, project.audioVolume) }
    }

    LaunchedEffect(editor.playing) {
        if (!editor.playing) {
            runCatching { player?.pause() }
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                val timelineStart = project.frames.take(editor.frameIndex).sumOf { it.durationMs }
                val waitMs = project.audioOffsetMs - timelineStart
                if (waitMs > 0) delay(waitMs.toLong())
                val audioPosition = (timelineStart - project.audioOffsetMs).coerceAtLeast(0)
                if (editor.playing) runCatching {
                    player?.seekTo(audioPosition)
                    player?.start()
                }
            }
            while (editor.playing) {
                editor.ensureIndices()
                val hold = editor.frame.durationMs.coerceIn(50, 120000)
                delay(hold.toLong())
                if (!editor.playing) break
                if (editor.frameIndex >= project.frames.lastIndex) {
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
                        DropdownMenu(showExport, onDismissRequest = { showExport = false }) {
                            ReleaseExportItem("MP4 video", "H.264 with project audio") { showExport = false; onExportMp4() }
                            ReleaseExportItem("Animated GIF", "Frame hold timing preserved") { showExport = false; onExportGif() }
                            ReleaseExportItem("Current frame PNG", "Full-resolution still") { showExport = false; onExportCurrentPng() }
                            ReleaseExportItem("All frames ZIP", "PNG sequence + project + audio") { showExport = false; onExportFramesZip() }
                            ReleaseExportItem("Editable project", "Portable .frameflow archive") { showExport = false; onExportProject() }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { history.checkpoint(); editor.cloneFrame() },
                text = { Text("+ Clone") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (project.mode != ProjectMode.Static) {
                ReleaseModeStrip(
                    project = project,
                    editor = editor,
                    history = history,
                    busy = smartBusy,
                    onBusy = { smartBusy = it },
                    onImportImage = onImportImage,
                    onMessage = { message = it }
                )
            }

            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item { FilterChip(tool == ReleaseTool.Brush, { tool = ReleaseTool.Brush; editor.tool = Tool.Brush; sheet = ReleaseSheet.Brushes }, { Text("Brush") }) }
                item { FilterChip(tool == ReleaseTool.Eraser, { tool = ReleaseTool.Eraser; editor.tool = Tool.Eraser; sheet = ReleaseSheet.Erasers }, { Text("Eraser") }) }
                item { FilterChip(tool == ReleaseTool.SelectPart, { tool = ReleaseTool.SelectPart; editor.tool = Tool.SmartSelect }, { Text("Select limb") }) }
                item { FilterChip(tool == ReleaseTool.ColourRepeat, { tool = ReleaseTool.ColourRepeat; editor.tool = Tool.ColorRepeat }, { Text("Colour repeat") }) }
                item { AssistChip({ sheet = ReleaseSheet.Tools }, label = { Text("Tools") }) }
                item { AssistChip({ sheet = ReleaseSheet.Layers }, label = { Text("Layers") }) }
                item { AssistChip({ sheet = ReleaseSheet.Onion }, label = { Text("Onion") }) }
                item { AssistChip({ sheet = ReleaseSheet.Smart }, label = { Text("Smart") }) }
                item { FilledTonalButton(onClick = { sheet = ReleaseSheet.LazyChat }) { Text("Lazy Chat") } }
                item { AssistChip({ sheet = ReleaseSheet.Audio }, label = { Text("Audio") }) }
            }

            ReleaseCanvas(
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
                onionAlpha = onionAlpha,
                onionSelectedLayerOnly = onionSelectedLayerOnly,
                recentColours = recentColours,
                onToolChange = { next ->
                    tool = next
                    when (next) {
                        ReleaseTool.Brush -> editor.tool = Tool.Brush
                        ReleaseTool.Eraser -> editor.tool = Tool.Eraser
                        ReleaseTool.SelectPart -> editor.tool = Tool.SmartSelect
                        ReleaseTool.ColourRepeat -> editor.tool = Tool.ColorRepeat
                        else -> Unit
                    }
                },
                onMessage = { message = it }
            )

            ReleaseTimeline(editor, history, onExactDuration = { durationDialogIndex = it })

            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                item { TextButton(onClick = { history.checkpoint(); editor.addBlankFrame() }) { Text("Blank") } }
                item { TextButton(onClick = { history.checkpoint(); editor.deleteFrame() }) { Text("Delete frame") } }
                item { TextButton(onClick = { sheet = ReleaseSheet.Colour }) { Text("Colour") } }
                item { TextButton(onClick = { sheet = ReleaseSheet.Camera }) { Text("Camera") } }
                item { TextButton(onClick = { onSave() }) { Text("Save") } }
                item { TextButton(onClick = { sheet = ReleaseSheet.Project }) { Text("Project") } }
            }
        }
    }

    ReleaseSheets(
        sheet = sheet,
        editor = editor,
        history = history,
        repository = repository,
        recentColours = recentColours,
        onionBefore = onionBefore,
        onionAfter = onionAfter,
        onionAlpha = onionAlpha,
        onionSelectedLayerOnly = onionSelectedLayerOnly,
        viewZoom = viewZoom,
        onViewZoom = { viewZoom = it },
        onResetView = { viewZoom = 1f; panX = 0f; panY = 0f },
        onOnionBefore = { onionBefore = it },
        onOnionAfter = { onionAfter = it },
        onOnionAlpha = { onionAlpha = it },
        onOnionLayerOnly = { onionSelectedLayerOnly = it },
        onImportImage = onImportImage,
        onImportAudio = onImportAudio,
        onSave = onSave,
        onTool = { next ->
            tool = next
            sheet = null
            when (next) {
                ReleaseTool.Brush -> editor.tool = Tool.Brush
                ReleaseTool.Eraser -> editor.tool = Tool.Eraser
                ReleaseTool.SelectPart -> editor.tool = Tool.SmartSelect
                ReleaseTool.ColourRepeat -> editor.tool = Tool.ColorRepeat
                else -> Unit
            }
        },
        onMessage = { message = it },
        onDismiss = { sheet = null }
    )

    if (durationDialogIndex in project.frames.indices) {
        ReleaseDurationDialog(
            frame = project.frames[durationDialogIndex],
            snapMs = project.snapMs,
            onDismiss = { durationDialogIndex = -1 },
            onApply = { duration ->
                history.checkpoint()
                project.frames[durationDialogIndex].durationMs = duration
                project.touch()
                durationDialogIndex = -1
            }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Frameflow") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun ReleaseExportItem(title: String, subtitle: String, action: () -> Unit) {
    DropdownMenuItem(text = { Column { Text(title); Text(subtitle, style = MaterialTheme.typography.labelSmall) } }, onClick = action)
}
