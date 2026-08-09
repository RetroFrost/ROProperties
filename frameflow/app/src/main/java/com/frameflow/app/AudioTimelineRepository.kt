package com.frameflow.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val MAX_AUDIO_CLIP_BYTES = 256L * 1024L * 1024L

data class AudioClipState(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var fileName: String,
    var startMs: Int,
    var trimStartMs: Int = 0,
    var trimEndMs: Int,
    var sourceDurationMs: Int,
    var volume: Float = 1f,
    var muted: Boolean = false
) {
    val sourceTrimmedDurationMs: Int get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
    val endMs: Int get() = (startMs.toLong() + sourceTrimmedDurationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

enum class AudioMarkerType { Beat, SpeechStart, SpeechEnd, Manual }

data class AudioMarkerState(
    val id: String = UUID.randomUUID().toString(),
    var timeMs: Int,
    var type: AudioMarkerType,
    var label: String
)

data class AudioTimelineState(
    val clips: MutableList<AudioClipState> = mutableListOf(),
    val markers: MutableList<AudioMarkerState> = mutableListOf()
)

class AudioTimelineRepository(private val context: Context) {
    private val mediaRoot = File(context.filesDir, "frameflow-media").apply { mkdirs() }
    private val lock = Any()

    fun load(project: ProjectState): AudioTimelineState = synchronized(lock) {
        val timeline = timelineFile(project.id)
        if (timeline.isFile && timeline.length() in 1..2L * 1024L * 1024L) {
            runCatching { decode(timeline.readText(), project.id) }.getOrElse { migrateLegacy(project) }
        } else migrateLegacy(project)
    }

    fun save(project: ProjectState, state: AudioTimelineState) = synchronized(lock) {
        val dir = projectDir(project.id).apply { mkdirs() }
        val target = File(dir, "timeline.json")
        val temp = File(dir, ".timeline.tmp")
        val json = encode(state).toString()
        require(json.length <= 2 * 1024 * 1024) { "Audio timeline is too large" }
        FileOutputStream(temp, false).use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        syncLegacy(project, state)
    }

    fun importClip(project: ProjectState, uri: Uri, startMs: Int): AudioClipState = synchronized(lock) {
        val dir = projectDir(project.id).apply { mkdirs() }
        val display = displayName(uri).ifBlank { "audio" }
        val safe = uniqueName(dir, safeFileName(display))
        val temp = File(dir, ".$safe.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_AUDIO_CLIP_BYTES) { "Audio clip is too large" }
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("Unable to read audio")
        require(temp.length() > 0) { "Audio clip is empty" }
        val destination = File(dir, safe)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        val duration = mediaDurationMs(destination)
        require(duration > 0) { "Audio duration could not be read" }
        val state = load(project)
        val clip = AudioClipState(
            name = display.take(80),
            fileName = destination.name,
            startMs = startMs.coerceIn(-3_600_000, 86_400_000),
            trimStartMs = 0,
            trimEndMs = duration,
            sourceDurationMs = duration
        )
        state.clips += clip
        save(project, state)
        project.touch()
        clip
    }

    fun update(project: ProjectState, mutate: (AudioTimelineState) -> Unit): AudioTimelineState = synchronized(lock) {
        val state = load(project)
        mutate(state)
        sanitize(state, project.id)
        save(project, state)
        project.touch()
        state
    }

    fun deleteClip(project: ProjectState, clipId: String): AudioTimelineState = update(project) { state ->
        val removed = state.clips.firstOrNull { it.id == clipId }
        if (removed != null) {
            state.clips.remove(removed)
            val stillUsed = state.clips.any { it.fileName == removed.fileName }
            if (!stillUsed) File(projectDir(project.id), safeFileName(removed.fileName)).delete()
        }
    }

    fun clear(project: ProjectState) = synchronized(lock) {
        projectDir(project.id).deleteRecursively()
        project.audioFileName = null
        project.audioOffsetMs = 0
        project.audioVolume = 1f
        project.touch()
    }

    fun file(projectId: String, clip: AudioClipState): File? =
        File(projectDir(projectId), safeFileName(clip.fileName)).takeIf { it.isFile && it.length() in 1..MAX_AUDIO_CLIP_BYTES }

    fun timelineFileForExport(projectId: String): File? = timelineFile(projectId).takeIf { it.isFile }
    fun allFilesForExport(project: ProjectState): List<File> = load(project).clips.mapNotNull { file(project.id, it) }.distinctBy { it.absolutePath }

    private fun migrateLegacy(project: ProjectState): AudioTimelineState {
        val state = AudioTimelineState()
        val legacy = project.audioFileName?.let { File(projectDir(project.id), safeFileName(it)) }
        if (legacy?.isFile == true) {
            val duration = mediaDurationMs(legacy)
            if (duration > 0) {
                state.clips += AudioClipState(
                    name = legacy.name,
                    fileName = legacy.name,
                    startMs = project.audioOffsetMs,
                    trimEndMs = duration,
                    sourceDurationMs = duration,
                    volume = project.audioVolume.coerceIn(0f, 1f)
                )
            }
        }
        return state
    }

    private fun syncLegacy(project: ProjectState, state: AudioTimelineState) {
        val first = state.clips.firstOrNull()
        project.audioFileName = first?.fileName
        project.audioOffsetMs = first?.startMs ?: 0
        project.audioVolume = first?.volume ?: 1f
    }

    private fun sanitize(state: AudioTimelineState, projectId: String) {
        state.clips.removeAll { clip ->
            clip.id.isBlank() || file(projectId, clip) == null
        }
        state.clips.forEach { clip ->
            clip.name = clip.name.take(80).ifBlank { "Audio clip" }
            clip.fileName = safeFileName(clip.fileName)
            clip.sourceDurationMs = clip.sourceDurationMs.coerceIn(1, 86_400_000)
            clip.trimStartMs = clip.trimStartMs.coerceIn(0, clip.sourceDurationMs - 1)
            clip.trimEndMs = clip.trimEndMs.coerceIn(clip.trimStartMs + 1, clip.sourceDurationMs)
            clip.startMs = clip.startMs.coerceIn(-3_600_000, 86_400_000)
            clip.volume = clip.volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        }
        state.markers.removeAll { it.timeMs !in -3_600_000..86_400_000 }
        if (state.markers.size > 20_000) state.markers.subList(20_000, state.markers.size).clear()
    }

    private fun encode(state: AudioTimelineState) = JSONObject().apply {
        put("version", 1)
        put("clips", JSONArray().apply {
            state.clips.forEach { clip ->
                put(JSONObject().apply {
                    put("id", clip.id)
                    put("name", clip.name)
                    put("fileName", clip.fileName)
                    put("startMs", clip.startMs)
                    put("trimStartMs", clip.trimStartMs)
                    put("trimEndMs", clip.trimEndMs)
                    put("sourceDurationMs", clip.sourceDurationMs)
                    put("volume", clip.volume.toDouble())
                    put("muted", clip.muted)
                })
            }
        })
        put("markers", JSONArray().apply {
            state.markers.forEach { marker ->
                put(JSONObject().apply {
                    put("id", marker.id)
                    put("timeMs", marker.timeMs)
                    put("type", marker.type.name)
                    put("label", marker.label.take(80))
                })
            }
        })
    }

    private fun decode(text: String, projectId: String): AudioTimelineState {
        require(text.length <= 2 * 1024 * 1024) { "Audio timeline is too large" }
        val root = JSONObject(text)
        val clipsArray = root.optJSONArray("clips") ?: JSONArray()
        require(clipsArray.length() <= 512) { "Too many audio clips" }
        val clips = mutableListOf<AudioClipState>()
        for (i in 0 until clipsArray.length()) {
            val item = clipsArray.optJSONObject(i) ?: continue
            val duration = item.optInt("sourceDurationMs", 1).coerceIn(1, 86_400_000)
            clips += AudioClipState(
                id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: UUID.randomUUID().toString(),
                name = item.optString("name", "Audio clip").take(80),
                fileName = safeFileName(item.optString("fileName", "audio")),
                startMs = item.optInt("startMs", 0),
                trimStartMs = item.optInt("trimStartMs", 0),
                trimEndMs = item.optInt("trimEndMs", duration),
                sourceDurationMs = duration,
                volume = item.optDouble("volume", 1.0).toFloat(),
                muted = item.optBoolean("muted", false)
            )
        }
        val markersArray = root.optJSONArray("markers") ?: JSONArray()
        require(markersArray.length() <= 20_000) { "Too many audio markers" }
        val markers = mutableListOf<AudioMarkerState>()
        for (i in 0 until markersArray.length()) {
            val item = markersArray.optJSONObject(i) ?: continue
            markers += AudioMarkerState(
                id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: UUID.randomUUID().toString(),
                timeMs = item.optInt("timeMs", 0),
                type = runCatching { AudioMarkerType.valueOf(item.optString("type", AudioMarkerType.Manual.name)) }.getOrDefault(AudioMarkerType.Manual),
                label = item.optString("label", "Marker").take(80)
            )
        }
        return AudioTimelineState(clips, markers).also { sanitize(it, projectId) }
    }

    private fun mediaDurationMs(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceIn(1L, 86_400_000L)?.toInt() ?: 0
        } finally { runCatching { retriever.release() } }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index) ?: "audio"
        }
        return uri.lastPathSegment ?: "audio"
    }

    private fun projectDir(projectId: String) = File(mediaRoot, projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128))
    private fun timelineFile(projectId: String) = File(projectDir(projectId), "timeline.json")
    private fun safeFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "audio" }

    private fun uniqueName(dir: File, preferred: String): String {
        if (!File(dir, preferred).exists()) return preferred
        val base = preferred.substringBeforeLast('.', preferred)
        val ext = preferred.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var n = 2
        while (n < 10_000) {
            val candidate = "$base-$n$ext"
            if (!File(dir, candidate).exists()) return candidate
            n++
        }
        return "audio-${UUID.randomUUID()}$ext"
    }
}
