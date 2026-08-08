package com.frameflow.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectRepository(private val context: Context) {
    private val projectDir = File(context.filesDir, "frameflow-projects").apply { mkdirs() }
    private val backupDir = File(context.filesDir, "frameflow-recovery").apply { mkdirs() }
    private val mediaRoot = File(context.filesDir, "frameflow-media").apply { mkdirs() }

    fun listProjects(): List<ProjectMeta> = projectDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "frameflow" }
        .mapNotNull { file -> runCatching { projectFromJson(file.readText()).toMeta() }.getOrNull() }
        .sortedByDescending { it.modifiedAt }

    fun createProject(name: String = "Untitled animation"): ProjectState {
        val project = ProjectState(name = name.ifBlank { "Untitled animation" })
        save(project)
        return project
    }

    fun load(id: String): ProjectState? {
        val file = projectFile(id)
        if (!file.exists()) return null
        return runCatching { projectFromJson(file.readText()) }
            .recoverCatching {
                val backup = backupFile(id)
                if (!backup.exists()) throw it
                projectFromJson(backup.readText())
            }.getOrNull()
    }

    fun loadRecovery(id: String): ProjectState? {
        val file = backupFile(id)
        if (!file.exists()) return null
        return runCatching { projectFromJson(file.readText()) }.getOrNull()
    }

    fun save(project: ProjectState) {
        val target = projectFile(project.id)
        val temporary = File(projectDir, "${project.id}.tmp")
        if (target.exists()) runCatching { target.copyTo(backupFile(project.id), overwrite = true) }
        temporary.writeText(projectToJson(project).toString())
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    fun delete(id: String) {
        projectFile(id).delete()
        backupFile(id).delete()
        mediaDir(id).deleteRecursively()
    }

    fun duplicate(project: ProjectState): ProjectState {
        val clone = ProjectState(
            id = UUID.randomUUID().toString(),
            name = "${project.name} copy",
            canvasWidth = project.canvasWidth,
            canvasHeight = project.canvasHeight,
            backgroundArgb = project.backgroundArgb,
            frames = project.frames.map { it.cloneFrame() },
            mode = project.mode,
            fps = project.fps,
            loopPlayback = project.loopPlayback,
            snapMs = project.snapMs,
            audioOffsetMs = project.audioOffsetMs,
            audioVolume = project.audioVolume
        )
        val audio = audioFile(project)
        if (audio != null) {
            val dest = File(mediaDir(clone.id).apply { mkdirs() }, audio.name)
            audio.copyTo(dest, overwrite = true)
            clone.audioFileName = dest.name
        }
        save(clone)
        return clone
    }

    fun importProject(uri: Uri): ProjectState {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Unable to read project")
        val imported = projectFromJson(text)
        val local = ProjectState(
            id = UUID.randomUUID().toString(),
            name = imported.name,
            canvasWidth = imported.canvasWidth,
            canvasHeight = imported.canvasHeight,
            backgroundArgb = imported.backgroundArgb,
            frames = imported.frames.map { it.cloneFrame() },
            mode = imported.mode,
            fps = imported.fps,
            loopPlayback = imported.loopPlayback,
            snapMs = imported.snapMs,
            audioOffsetMs = imported.audioOffsetMs,
            audioVolume = imported.audioVolume
        )
        save(local)
        return local
    }

    fun importImageLayer(project: ProjectState, frameIndex: Int, uri: Uri): Int {
        val source = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("Unable to decode image")
        val maxDimension = 4096
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
                .also { source.recycle() }
        } else source
        val bytes = ByteArrayOutputStream().use { buffer ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer))
            buffer.toByteArray()
        }
        bitmap.recycle()
        val name = displayName(uri).substringBeforeLast('.').ifBlank { "Imported image" }
        val frame = project.frames[frameIndex.coerceIn(project.frames.indices)]
        frame.layers.add(0, LayerState(name, Part.None, rasterPngBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP), rasterName = name))
        project.touch()
        save(project)
        return 0
    }

    fun importAudio(project: ProjectState, uri: Uri): File {
        val display = displayName(uri).ifBlank { "audio" }
        val safe = display.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val dir = mediaDir(project.id).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val destination = File(dir, safe)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to read audio")
        project.audioFileName = destination.name
        project.touch()
        save(project)
        return destination
    }

    fun removeAudio(project: ProjectState) {
        mediaDir(project.id).deleteRecursively()
        project.audioFileName = null
        project.audioOffsetMs = 0
        project.touch()
        save(project)
    }

    fun audioFile(project: ProjectState): File? {
        val name = project.audioFileName ?: return null
        return File(mediaDir(project.id), name).takeIf { it.exists() }
    }

    fun exportProject(project: ProjectState, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
            it.write(projectToJson(project).toString(2))
        } ?: error("Unable to create project file")
    }

    fun exportCurrentPng(project: ProjectState, frameIndex: Int, uri: Uri) {
        val bitmap = FrameRenderer.render(project, frameIndex)
        context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        } ?: error("Unable to create PNG")
        bitmap.recycle()
    }

    fun exportFramesZip(project: ProjectState, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                project.frames.forEachIndexed { index, _ ->
                    val bitmap = FrameRenderer.render(project, index)
                    zip.putNextEntry(ZipEntry("frame-${(index + 1).toString().padStart(4, '0')}.png"))
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    bitmap.recycle()
                }
                zip.putNextEntry(ZipEntry("project.frameflow"))
                zip.write(projectToJson(project).toString(2).toByteArray())
                zip.closeEntry()
                audioFile(project)?.let { audio ->
                    zip.putNextEntry(ZipEntry("audio/${audio.name}"))
                    audio.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Unable to create frame archive")
    }

    fun exportGif(project: ProjectState, uri: Uri) = MediaExporter.exportGif(context, project, uri)

    fun exportMp4(project: ProjectState, uri: Uri) = MediaExporter.exportMp4(context, project, uri, project.fps)

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun projectFile(id: String) = File(projectDir, "$id.frameflow")
    private fun backupFile(id: String) = File(backupDir, "$id.frameflow.bak")
    private fun mediaDir(id: String) = File(mediaRoot, id)
}

fun ProjectState.toMeta() = ProjectMeta(
    id = id,
    name = name,
    modifiedAt = modifiedAt,
    frameCount = frames.size,
    width = canvasWidth,
    height = canvasHeight
)

fun projectToJson(project: ProjectState): JSONObject = JSONObject().apply {
    put("format", "frameflow")
    put("version", FRAMEFLOW_FORMAT_VERSION)
    put("id", project.id)
    put("name", project.name)
    put("modifiedAt", project.modifiedAt)
    put("canvasWidth", project.canvasWidth)
    put("canvasHeight", project.canvasHeight)
    put("backgroundArgb", project.backgroundArgb)
    put("mode", project.mode.name)
    put("fps", project.fps)
    put("loopPlayback", project.loopPlayback)
    put("snapMs", project.snapMs)
    put("audioFileName", project.audioFileName ?: JSONObject.NULL)
    put("audioOffsetMs", project.audioOffsetMs)
    put("audioVolume", project.audioVolume.toDouble())
    put("frames", JSONArray().apply { project.frames.forEach { put(frameToJson(it)) } })
}

private fun frameToJson(frame: FrameState) = JSONObject().apply {
    put("durationMs", frame.durationMs)
    put("label", frame.label)
    put("cameraX", frame.cameraX.toDouble())
    put("cameraY", frame.cameraY.toDouble())
    put("cameraZoom", frame.cameraZoom.toDouble())
    put("cameraRotation", frame.cameraRotation.toDouble())
    put("layers", JSONArray().apply { frame.layers.forEach { put(layerToJson(it)) } })
}

private fun layerToJson(layer: LayerState) = JSONObject().apply {
    put("name", layer.name)
    put("part", layer.part.name)
    put("visible", layer.visible)
    put("locked", layer.locked)
    put("opacity", layer.opacity.toDouble())
    put("offsetX", layer.offsetX.toDouble())
    put("offsetY", layer.offsetY.toDouble())
    put("scaleX", layer.scaleX.toDouble())
    put("scaleY", layer.scaleY.toDouble())
    put("rotationDeg", layer.rotationDeg.toDouble())
    put("rasterPngBase64", layer.rasterPngBase64 ?: JSONObject.NULL)
    put("rasterName", layer.rasterName ?: JSONObject.NULL)
    put("strokes", JSONArray().apply { layer.strokes.forEach { put(strokeToJson(it)) } })
}

private fun strokeToJson(stroke: StrokeData) = JSONObject().apply {
    put("id", stroke.id)
    put("colorArgb", stroke.colorArgb)
    put("width", stroke.width.toDouble())
    put("alpha", stroke.alpha.toDouble())
    put("erase", stroke.erase)
    put("points", JSONArray().apply {
        stroke.points.forEach { point -> put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble())) }
    })
}

fun projectFromJson(text: String): ProjectState {
    val root = JSONObject(text)
    require(root.optString("format", "frameflow") == "frameflow") { "Not a Frameflow project" }
    val framesJson = root.optJSONArray("frames") ?: JSONArray()
    val frames = buildList {
        for (frameIndex in 0 until framesJson.length()) {
            val frameObject = framesJson.optJSONObject(frameIndex) ?: continue
            val layersJson = frameObject.optJSONArray("layers") ?: JSONArray()
            val layers = buildList {
                for (layerIndex in 0 until layersJson.length()) {
                    val layerObject = layersJson.optJSONObject(layerIndex) ?: continue
                    val strokesJson = layerObject.optJSONArray("strokes") ?: JSONArray()
                    val strokes = buildList {
                        for (strokeIndex in 0 until strokesJson.length()) {
                            val strokeObject = strokesJson.optJSONObject(strokeIndex) ?: continue
                            val pointsJson = strokeObject.optJSONArray("points") ?: JSONArray()
                            val points = buildList {
                                for (pointIndex in 0 until pointsJson.length()) {
                                    val point = pointsJson.optJSONArray(pointIndex) ?: continue
                                    if (point.length() >= 2) add(CanvasPoint(point.optDouble(0).toFloat(), point.optDouble(1).toFloat()))
                                }
                            }
                            add(
                                StrokeData(
                                    id = strokeObject.optString("id", UUID.randomUUID().toString()),
                                    points = points,
                                    colorArgb = strokeObject.optInt("colorArgb", 0xFF111111.toInt()),
                                    width = strokeObject.optDouble("width", 12.0).toFloat(),
                                    alpha = strokeObject.optDouble("alpha", 1.0).toFloat(),
                                    erase = strokeObject.optBoolean("erase", false)
                                )
                            )
                        }
                    }
                    add(
                        LayerState(
                            name = layerObject.optString("name", "Layer ${layerIndex + 1}"),
                            part = runCatching { Part.valueOf(layerObject.optString("part", Part.None.name)) }.getOrDefault(Part.None),
                            strokes = strokes,
                            visible = layerObject.optBoolean("visible", true),
                            locked = layerObject.optBoolean("locked", false),
                            opacity = layerObject.optDouble("opacity", 1.0).toFloat(),
                            offsetX = layerObject.optDouble("offsetX", 0.0).toFloat(),
                            offsetY = layerObject.optDouble("offsetY", 0.0).toFloat(),
                            scaleX = layerObject.optDouble("scaleX", 1.0).toFloat(),
                            scaleY = layerObject.optDouble("scaleY", 1.0).toFloat(),
                            rotationDeg = layerObject.optDouble("rotationDeg", 0.0).toFloat(),
                            rasterPngBase64 = layerObject.optString("rasterPngBase64").takeIf { it.isNotBlank() && it != "null" },
                            rasterName = layerObject.optString("rasterName").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
            add(
                FrameState(
                    duration = frameObject.optInt("durationMs", 1000).coerceIn(50, 60000),
                    layers = layers.ifEmpty { defaultLayers() },
                    label = frameObject.optString("label", ""),
                    cameraX = frameObject.optDouble("cameraX", 0.0).toFloat(),
                    cameraY = frameObject.optDouble("cameraY", 0.0).toFloat(),
                    cameraZoom = frameObject.optDouble("cameraZoom", 1.0).toFloat(),
                    cameraRotation = frameObject.optDouble("cameraRotation", 0.0).toFloat()
                )
            )
        }
    }
    return ProjectState(
        id = root.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        name = root.optString("name", "Untitled animation"),
        canvasWidth = root.optInt("canvasWidth", 1080).coerceIn(64, 4096),
        canvasHeight = root.optInt("canvasHeight", 1080).coerceIn(64, 4096),
        backgroundArgb = root.optInt("backgroundArgb", 0xFFFFFFFF.toInt()),
        frames = frames.ifEmpty { listOf(FrameState()) },
        modified = root.optLong("modifiedAt", System.currentTimeMillis()),
        mode = runCatching { ProjectMode.valueOf(root.optString("mode", ProjectMode.Static.name)) }.getOrDefault(ProjectMode.Static),
        fps = root.optInt("fps", 30).coerceIn(1, 60),
        loopPlayback = root.optBoolean("loopPlayback", true),
        snapMs = root.optInt("snapMs", 100).coerceIn(10, 5000),
        audioFileName = root.optString("audioFileName").takeIf { it.isNotBlank() && it != "null" },
        audioOffsetMs = root.optInt("audioOffsetMs", 0),
        audioVolume = root.optDouble("audioVolume", 1.0).toFloat().coerceIn(0f, 1f)
    )
}
