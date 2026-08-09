package com.frameflow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseMultiAudioSheet(
    editor: EditorState,
    history: ProjectHistory,
    onImportAudio: () -> Unit,
    onMessage: (String) -> Unit,
    dismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val audioRepo = remember { AudioTimelineRepository(context) }
    val scope = rememberCoroutineScope()
    var timeline by remember(editor.project.id, editor.project.revision) { mutableStateOf(audioRepo.load(editor.project)) }
    val totalTimelineMs = maxOf(
        editor.project.totalDurationMs,
        timeline.clips.maxOfOrNull { it.endMs } ?: 0,
        1000
    )

    fun refresh() { timeline = audioRepo.load(editor.project) }
    fun updateClip(id: String, block: (AudioClipState) -> Unit) {
        timeline = audioRepo.update(editor.project) { state -> state.clips.firstOrNull { it.id == id }?.let(block) }
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(14.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Audio timeline", style = MaterialTheme.typography.titleLarge)
                    Text("${timeline.clips.size} clips · ${timeline.markers.size} markers", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = onImportAudio) { Text("+ Clip") }
            }

            if (timeline.clips.isEmpty()) {
                Text("Import a clip. New audio is placed at the current frame/playhead and does not replace existing clips.")
            } else {
                ReleaseAudioTrackView(
                    clips = timeline.clips,
                    totalMs = totalTimelineMs,
                    onCommitStart = { clipId, start -> updateClip(clipId) { it.startMs = start } }
                )
            }

            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(timeline.clips, key = { it.id }) { clip ->
                    ReleaseAudioClipCard(
                        clip = clip,
                        totalTimelineMs = totalTimelineMs,
                        onUpdate = { mutate -> updateClip(clip.id, mutate) },
                        onDelete = {
                            history.checkpoint()
                            timeline = audioRepo.deleteClip(editor.project, clip.id)
                            onMessage("Removed ${clip.name}")
                        },
                        onAnalyse = {
                            scope.launch {
                                val file = audioRepo.file(editor.project.id, clip)
                                if (file == null) { onMessage("Audio file is missing"); return@launch }
                                val result = runCatching { withContext(Dispatchers.Default) { AudioAnalysisTools.analyze(file, clip) } }
                                result.onSuccess { analysis ->
                                    timeline = audioRepo.update(editor.project) { state ->
                                        state.markers.removeAll { marker -> marker.timeMs in clip.startMs..clip.endMs && marker.type != AudioMarkerType.Manual }
                                        state.markers.addAll(analysis.markers)
                                        state.markers.sortBy { it.timeMs }
                                    }
                                    onMessage("Detected ${analysis.markers.count { it.type == AudioMarkerType.Beat }} beats and ${analysis.markers.count { it.type == AudioMarkerType.SpeechStart }} speech regions")
                                }.onFailure { onMessage("Audio analysis failed: ${it.message ?: "decoder error"}") }
                            }
                        },
                        onLipSync = {
                            val file = audioRepo.file(editor.project.id, clip)
                            if (file == null) { onMessage("Audio file is missing") }
                            else {
                                history.checkpoint()
                                scope.launch {
                                    runCatching { withContext(Dispatchers.Default) { LipSyncTools.generate(editor, clip, file) } }
                                        .onSuccess { onMessage("Generated ${it.frames} editable lip-sync frames") }
                                        .onFailure { onMessage("Lip sync failed: ${it.message ?: "decoder error"}") }
                                }
                            }
                        }
                    )
                }
                if (timeline.markers.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ReleaseSectionTitle("Markers")
                            Text(timeline.markers.take(40).joinToString(" · ") { marker -> "${marker.label}@${"%.2f".format(marker.timeMs / 1000f)}s" }, style = MaterialTheme.typography.bodySmall)
                            if (timeline.markers.size > 40) Text("+ ${timeline.markers.size - 40} more", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { timeline = audioRepo.update(editor.project) { it.markers.clear() } }) { Text("Clear markers") }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TextButton(onClick = {
                    val playhead = editor.project.frames.take(editor.frameIndex).sumOf { it.durationMs }
                    timeline = audioRepo.update(editor.project) { state ->
                        state.markers += AudioMarkerState(timeMs = playhead, type = AudioMarkerType.Manual, label = "Marker")
                        state.markers.sortBy { it.timeMs }
                    }
                }) { Text("+ Marker at playhead") }
                if (timeline.clips.isNotEmpty()) {
                    TextButton(onClick = {
                        history.checkpoint()
                        audioRepo.clear(editor.project)
                        refresh()
                        onMessage("Removed all audio")
                    }) { Text("Remove all") }
                }
            }
        }
    }
}

@Composable
private fun ReleaseAudioTrackView(
    clips: List<AudioClipState>,
    totalMs: Int,
    onCommitStart: (String, Int) -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height((clips.size.coerceAtMost(5) * 40 + 14).dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
            .padding(7.dp)
    ) {
        val fullWidth = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            clips.take(5).forEach { clip ->
                var workingStart by remember(clip.id, clip.startMs) { mutableIntStateOf(clip.startMs) }
                val visibleStart = workingStart.coerceAtLeast(0)
                val visibleEnd = clip.endMs.coerceAtLeast(visibleStart + 1)
                val startFraction = (visibleStart.toFloat() / totalMs).coerceIn(0f, 1f)
                val durationFraction = ((visibleEnd - visibleStart).toFloat() / totalMs).coerceIn(.035f, 1f - startFraction)
                Row(Modifier.fillMaxWidth().height(36.dp)) {
                    Spacer(Modifier.width(fullWidth * startFraction))
                    Surface(
                        modifier = Modifier.width(fullWidth * durationFraction).fillMaxHeight()
                            .pointerInput(clip.id, totalMs) {
                                var accumulated = 0f
                                var start = clip.startMs
                                detectDragGestures(
                                    onDragStart = { start = clip.startMs; accumulated = 0f },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        accumulated += drag.x
                                        val deltaMs = (accumulated / size.width.coerceAtLeast(1) * clip.sourceTrimmedDurationMs.coerceAtLeast(1)).roundToInt()
                                        workingStart = (start + deltaMs).coerceIn(-3_600_000, 86_400_000)
                                    },
                                    onDragEnd = { onCommitStart(clip.id, workingStart) },
                                    onDragCancel = { workingStart = clip.startMs }
                                )
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (clip.muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                            Text(clip.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseAudioClipCard(
    clip: AudioClipState,
    totalTimelineMs: Int,
    onUpdate: ((AudioClipState) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onAnalyse: () -> Unit,
    onLipSync: () -> Unit
) {
    var startText by remember(clip.id, clip.startMs) { mutableStateOf("%.3f".format(clip.startMs / 1000.0)) }
    var trimStartText by remember(clip.id, clip.trimStartMs) { mutableStateOf("%.3f".format(clip.trimStartMs / 1000.0)) }
    var trimEndText by remember(clip.id, clip.trimEndMs) { mutableStateOf("%.3f".format(clip.trimEndMs / 1000.0)) }
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(clip.name, style = MaterialTheme.typography.titleSmall)
                    Text("${"%.2f".format(clip.sourceTrimmedDurationMs / 1000f)} s trimmed · starts ${"%.2f".format(clip.startMs / 1000f)} s", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onUpdate { it.muted = !it.muted } }) { Text(if (clip.muted) "Unmute" else "Mute") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            ReleaseSliderRow("Volume", clip.volume, 0f..1f, "${(clip.volume * 100).roundToInt()}%") { value -> onUpdate { it.volume = value } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(startText, { startText = it.take(10) }, Modifier.weight(1f), label = { Text("Start s") }, singleLine = true)
                OutlinedTextField(trimStartText, { trimStartText = it.take(10) }, Modifier.weight(1f), label = { Text("Trim in s") }, singleLine = true)
                OutlinedTextField(trimEndText, { trimEndText = it.take(10) }, Modifier.weight(1f), label = { Text("Trim out s") }, singleLine = true)
                Button(onClick = {
                    val start = (startText.replace(',', '.').toDoubleOrNull()?.times(1000.0)?.roundToInt() ?: clip.startMs).coerceIn(-3_600_000, 86_400_000)
                    val trimIn = (trimStartText.replace(',', '.').toDoubleOrNull()?.times(1000.0)?.roundToInt() ?: clip.trimStartMs).coerceIn(0, clip.sourceDurationMs - 1)
                    val trimOut = (trimEndText.replace(',', '.').toDoubleOrNull()?.times(1000.0)?.roundToInt() ?: clip.trimEndMs).coerceIn(trimIn + 1, clip.sourceDurationMs)
                    onUpdate { it.startMs = start; it.trimStartMs = trimIn; it.trimEndMs = trimOut }
                }) { Text("Apply") }
            }
            ReleaseSliderRow("Timeline position", clip.startMs.toFloat().coerceIn(0f, totalTimelineMs.toFloat()), 0f..totalTimelineMs.toFloat().coerceAtLeast(1f), "${"%.2f".format(clip.startMs / 1000f)} s") {
                onUpdate { clipState -> clipState.startMs = it.roundToInt() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onAnalyse) { Text("Detect beats/speech") }
                FilledTonalButton(onClick = onLipSync) { Text("Generate lip sync") }
            }
        }
    }
}
