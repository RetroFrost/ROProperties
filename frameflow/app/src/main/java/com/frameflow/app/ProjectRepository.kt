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
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MAX_PROJECT_IMPORT_BYTES = 512L * 1024L * 1024L
private const val MAX_AUDIO_IMPORT_BYTES = 256L * 1024L * 1024L
private const val MAX_RASTER_SIDE = 4096

class ProjectRepository(private val context: Context) {
    private val projectDir = File(context.filesDir, "frameflow-projects").apply { mkdirs() }
    private val backupDir = File(context.filesDir, "frameflow-recovery").apply { mkdirs() }
    private val mediaRoot = File(context.filesDir, "frameflow-media").apply { mkdirs() }
    private val saveLock = Any()

    fun listProjects(): List<ProjectMeta> = projectDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "frameflow" && it.length() in 1..MAX_PROJECT_IMPORT_BYTES }
        .mapNotNull { file -> runCatching { projectFromJson(file.readText()) }.getOrNull()?.toMeta() }
        .sortedByDescending { it.modifiedAt }

    fun createProject(name: String = "Untitled animation"): ProjectState {
        val project = ProjectState(name = name.ifBlank { "Untitled animation" })
        save(project)
        return project
    }

    fun load(id: String): ProjectState? {
        val safeId = safeProjectId(id) ?: return null
        val file = projectFile(safeId)
        if (!file.exists() || file.length() !in 1..MAX_PROJECT_IMPORT_BYTES) return loadRecovery(safeId)
        return runCatching { projectFromJson(file.readText()) }
            .recoverCatching {
                val backup = backupFile(safeId)
                if (!backup.exists() || backup.length() !in 1..MAX_PROJECT_IMPORT_BYTES) throw it
                projectFromJson(backup.readText())
            }.getOrNull()
    }

    fun loadRecovery(id: String): ProjectState? {
        val safeId = safeProjectId(id) ?: return null
        val file = backupFile(safeId)
        if (!file.exists() || file.length() !in 1..MAX_PROJECT_IMPORT_BYTES) return null
        return runCatching { projectFromJson(file.readText()) }.getOrNull()
    }

    fun save(project: ProjectState) {
        synchronized(saveLock) {
            val target = projectFile(project.id)
            val temporary = File(projectDir, "${project.id}.tmp")
            val json = projectToJson(project).toString()
            require(json.toByteArray().size <= MAX_PROJECT_IMPORT_BYTES) { "Project is too large to save safely" }

            FileOutputStream(temporary, false).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            projectFromJson(temporary.readText())

            if (target.exists()) {
                val backup = backupFile(project.id)
                runCatching {
                    target.inputStream().use { input ->
                        FileOutputStream(backup, false).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                }
            }

            if (!temporary.renameTo(target)) {
                temporary.inputStream().use { input ->
                    FileOutputStream(target, false).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                temporary.delete()
            }
        }
    }

    fun delete(id: String) {
        val safeId = safeProjectId(id) ?: return
        projectFile(safeId).delete()
        backupFile(safeId).delete()
        File(projectDir, "$safeId.tmp").delete()
        mediaDir(safeId).deleteRecursively()
    }

    fun duplicate(project: ProjectState): ProjectState {
        val clone = ProjectState(
            id = UUID.randomUUID().toString(),
            name = "${project.name} copy".take(64),
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
        val sourceMediaDir = mediaDir(project.id)
        val cloneMediaDir = mediaDir(clone.id)
        if (sourceMediaDir.isDirectory) {
            check(sourceMediaDir.copyRecursively(cloneMediaDir, overwrite = true)) { "Unable to duplicate project media" }
        }
        clone.audioFileName = project.audioFileName
            ?.let(::safeFileName)
            ?.takeIf { File(cloneMediaDir, it).isFile }
        save(clone)
        return clone
    }

    fun importProject(uri: Uri): ProjectState {
        val temporary = File.createTempFile("frameflow-import-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output -> copyLimited(input, output, MAX_PROJECT_IMPORT_BYTES) }
            } ?: error("Unable to read project")
            require(temporary.length() > 0) { "Project file is empty" }

            val isZip = temporary.inputStream().use { input ->
                val a = input.read(); val b = input.read()
                a == 'P'.code && b == 'K'.code
            }

            var imported: ProjectState? = null
            var importedAudioName: String? = null
            var importedAudioTemp: File? = null

            if (isZip) {
                ZipInputStream(temporary.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        when {
                            entry.name == "project.json" || entry.name == "project.frameflow" -> {
                                val json = readLimitedText(zip, MAX_PROJECT_IMPORT_BYTES)
                                imported = projectFromJson(json)
                            }
                            entry.name.startsWith("audio/") && importedAudioTemp == null -> {
                                val name = safeFileName(entry.name.substringAfterLast('/').ifBlank { "audio" })
                                val tempAudio = File.createTempFile("frameflow-audio-", ".tmp", context.cacheDir)
                                FileOutputStream(tempAudio).use { output -> copyLimited(zip, output, MAX_AUDIO_IMPORT_BYTES) }
                                importedAudioName = name
                                importedAudioTemp = tempAudio
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } else {
                imported = projectFromJson(temporary.readText())
            }

            val source = imported ?: error("Project archive does not contain project.json")
            val local = ProjectState(
                id = UUID.randomUUID().toString(),
                name = source.name,
                canvasWidth = source.canvasWidth,
                canvasHeight = source.canvasHeight,
                backgroundArgb = source.backgroundArgb,
                frames = source.frames.map { it.cloneFrame() },
                mode = source.mode,
                fps = source.fps,
                loopPlayback = source.loopPlayback,
                snapMs = source.snapMs,
                audioOffsetMs = source.audioOffsetMs,
                audioVolume = source.audioVolume
            )

            if (importedAudioTemp != null && importedAudioTemp!!.length() > 0) {
                val dir = mediaDir(local.id).apply { mkdirs() }
                val destination = File(dir, importedAudioName ?: "audio")
                importedAudioTemp!!.copyTo(destination, overwrite = true)
                local.audioFileName = destination.name
            } else local.audioFileName = null
            save(local)
            importedAudioTemp?.delete()
            return local
        } finally {
            temporary.delete()
        }
    }

    fun importImageLayer(project: ProjectState, frameIndex: Int, uri: Uri): Int {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Unable to read image")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or corrupt image" }

        var sample = 1
        while (bounds.outWidth / sample > MAX_RASTER_SIDE * 2 || bounds.outHeight / sample > MAX_RASTER_SIDE * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Unable to decode image")

        val scale = minOf(1f, MAX_RASTER_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val bytes = try {
            ByteArrayOutputStream().use { buffer ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)) { "Unable to encode imported image" }
                buffer.toByteArray()
            }
        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
        require(bytes.size <= 64 * 1024 * 1024) { "Imported image is too large" }

        val name = displayName(uri).substringBeforeLast('.').ifBlank { "Imported image" }.take(64)
        if (project.frames.isEmpty()) project.frames.add(FrameState())
        val frame = project.frames[frameIndex.coerceIn(project.frames.indices)]
        if (frame.layers.isEmpty()) frame.layers.add(LayerState("Layer 1", Part.None))
        frame.layers.add(0, LayerState(name, Part.None, rasterPngBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP), rasterName = name))
        project.touch()
        save(project)
        return 0
    }

    fun importAudio(project: ProjectState, uri: Uri): File {
        val display = displayName(uri).ifBlank { "audio" }
        val safe = safeFileName(display)
        val dir = mediaDir(project.id).apply { mkdirs() }
        val temporary = File(dir, ".$safe.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temporary).use { output -> copyLimited(input, output, MAX_AUDIO_IMPORT_BYTES) }
        } ?: error("Unable to read audio")
        require(temporary.length() > 0) { "Audio file is empty" }
        dir.listFiles()?.filter { it != temporary }?.forEach { it.delete() }
        val destination = File(dir, safe)
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
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
        val name = project.audioFileName?.let(::safeFileName) ?: return null
        return File(mediaDir(project.id), name).takeIf { it.isFile && it.exists() }
    }

    fun exportProject(project: ProjectState, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(projectToJson(project).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                audioFile(project)?.let { audio ->
                    zip.putNextEntry(ZipEntry("audio/${safeFileName(audio.name)}"))
                    audio.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Unable to create project file")
    }

    fun exportCurrentPng(project: ProjectState, frameIndex: Int, uri: Uri) {
        val bitmap = FrameRenderer.render(project, frameIndex)
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                ?: error("Unable to create PNG")
        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    fun exportFramesZip(project: ProjectState, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                project.frames.forEachIndexed { index, _ ->
                    val bitmap = FrameRenderer.render(project, index)
                    try {
                        zip.putNextEntry(ZipEntry("frame-${(index + 1).toString().padStart(4, '0')}.png"))
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                        zip.closeEntry()
                    } finally { if (!bitmap.isRecycled) bitmap.recycle() }
                }
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(projectToJson(project).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                audioFile(project)?.let { audio ->
                    zip.putNextEntry(ZipEntry("audio/${safeFileName(audio.name)}"))
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
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun safeProjectId(id: String): String? = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
    private fun safeFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "file" }
    private fun projectFile(id: String) = File(projectDir, "$id.frameflow")
    private fun backupFile(id: String) = File(backupDir, "$id.frameflow.bak")
    private fun mediaDir(id: String) = File(mediaRoot, id)

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "File is too large" }
            output.write(buffer, 0, read)
        }
    }

    private fun readLimitedText(input: InputStream, maxBytes: Long): String {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, maxBytes)
        return output.toString(Charsets.UTF_8.name())
    }
}

fun ProjectState.toMeta() = ProjectMeta(id, name, modifiedAt, frames.size, canvasWidth, canvasHeight)

fun projectToJson(project: ProjectState): JSONObject = JSONObject().apply {
    put("format", "frameflow")
    put("version", FRAMEFLOW_FORMAT_VERSION)
    put("id", project.id)
    put("name", project.name)
    put("modifiedAt", project.modifiedAt)
    put("activeFrameIndex", project.activeFrameIndex)
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
    put("folderName", layer.folderName ?: JSONObject.NULL)
    put("clipToBelow", layer.clipToBelow)
    put("isRigSource", layer.isRigSource)
    put("strokes", JSONArray().apply { layer.strokes.forEach { put(strokeToJson(it)) } })
}

private fun strokeToJson(stroke: StrokeData) = JSONObject().apply {
    put("id", stroke.id)
    put("colorArgb", stroke.colorArgb)
    put("width", stroke.width.toDouble())
    put("alpha", stroke.alpha.toDouble())
    put("erase", stroke.erase)
    put("points", JSONArray().apply { stroke.points.forEach { point -> put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble())) } })
}

fun projectFromJson(text: String): ProjectState {
    require(text.length <= MAX_PROJECT_IMPORT_BYTES) { "Project is too large" }
    val root = JSONObject(text)
    require(root.optString("format", "frameflow") == "frameflow") { "Not a Frameflow project" }
    val framesJson = root.optJSONArray("frames") ?: JSONArray()
    require(framesJson.length() <= 20_000) { "Project contains too many frames" }
    val frames = buildList {
        for (frameIndex in 0 until framesJson.length()) {
            val frameObject = framesJson.optJSONObject(frameIndex) ?: continue
            val layersJson = frameObject.optJSONArray("layers") ?: JSONArray()
            require(layersJson.length() <= 1_000) { "Frame contains too many layers" }
            val layers = buildList {
                for (layerIndex in 0 until layersJson.length()) {
                    val layerObject = layersJson.optJSONObject(layerIndex) ?: continue
                    val strokesJson = layerObject.optJSONArray("strokes") ?: JSONArray()
                    require(strokesJson.length() <= 1_000_000) { "Layer contains too many strokes" }
                    val strokes = buildList {
                        for (strokeIndex in 0 until strokesJson.length()) {
                            val strokeObject = strokesJson.optJSONObject(strokeIndex) ?: continue
                            val pointsJson = strokeObject.optJSONArray("points") ?: JSONArray()
                            require(pointsJson.length() <= 2_000_000) { "Stroke contains too many points" }
                            val points = buildList {
                                for (pointIndex in 0 until pointsJson.length()) {
                                    val point = pointsJson.optJSONArray(pointIndex) ?: continue
                                    if (point.length() >= 2) {
                                        val x = point.optDouble(0, Double.NaN).toFloat()
                                        val y = point.optDouble(1, Double.NaN).toFloat()
                                        if (x.isFinite() && y.isFinite()) add(CanvasPoint(x, y))
                                    }
                                }
                            }
                            val width = strokeObject.optDouble("width", 12.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.1f, 4096f) ?: 12f
                            val alpha = strokeObject.optDouble("alpha", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
                            add(StrokeData(
                                id = strokeObject.optString("id", UUID.randomUUID().toString()).take(128),
                                points = points,
                                colorArgb = strokeObject.optInt("colorArgb", 0xFF111111.toInt()),
                                width = width,
                                alpha = alpha,
                                erase = strokeObject.optBoolean("erase", false)
                            ))
                        }
                    }
                    add(LayerState(
                        name = layerObject.optString("name", "Layer ${layerIndex + 1}").take(64),
                        part = runCatching { Part.valueOf(layerObject.optString("part", Part.None.name)) }.getOrDefault(Part.None),
                        strokes = strokes,
                        visible = layerObject.optBoolean("visible", true),
                        locked = layerObject.optBoolean("locked", false),
                        opacity = layerObject.safeFloat("opacity", 1f, 0f, 1f),
                        offsetX = layerObject.safeFloat("offsetX", 0f, -100_000f, 100_000f),
                        offsetY = layerObject.safeFloat("offsetY", 0f, -100_000f, 100_000f),
                        scaleX = layerObject.safeFloat("scaleX", 1f, -20f, 20f),
                        scaleY = layerObject.safeFloat("scaleY", 1f, -20f, 20f),
                        rotationDeg = layerObject.safeFloat("rotationDeg", 0f, -100_000f, 100_000f),
                        rasterPngBase64 = layerObject.optString("rasterPngBase64").takeIf { it.isNotBlank() && it != "null" && it.length <= 96 * 1024 * 1024 },
                        rasterName = layerObject.optString("rasterName").takeIf { it.isNotBlank() && it != "null" }?.take(120),
                        folderName = layerObject.optString("folderName").takeIf { it.isNotBlank() && it != "null" }?.take(64),
                        clipToBelow = layerObject.optBoolean("clipToBelow", false),
                        isRigSource = layerObject.optBoolean("isRigSource", false)
                    ))
                }
            }
            add(FrameState(
                duration = frameObject.optInt("durationMs", 1000).coerceIn(50, 120000),
                layers = layers.ifEmpty { defaultLayers() },
                label = frameObject.optString("label", "").take(64),
                cameraX = frameObject.safeFloat("cameraX", 0f, -100_000f, 100_000f),
                cameraY = frameObject.safeFloat("cameraY", 0f, -100_000f, 100_000f),
                cameraZoom = frameObject.safeFloat("cameraZoom", 1f, .05f, 20f),
                cameraRotation = frameObject.safeFloat("cameraRotation", 0f, -100_000f, 100_000f)
            ))
        }
    }
    val project = ProjectState(
        id = root.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: UUID.randomUUID().toString(),
        name = root.optString("name", "Untitled animation").take(64),
        canvasWidth = root.optInt("canvasWidth", 1080).coerceIn(64, 8192),
        canvasHeight = root.optInt("canvasHeight", 1080).coerceIn(64, 8192),
        backgroundArgb = root.optInt("backgroundArgb", 0xFFFFFFFF.toInt()),
        frames = frames.ifEmpty { listOf(FrameState()) },
        modified = root.optLong("modifiedAt", System.currentTimeMillis()),
        mode = runCatching { ProjectMode.valueOf(root.optString("mode", ProjectMode.Static.name)) }.getOrDefault(ProjectMode.Static),
        fps = root.optInt("fps", 30).coerceIn(1, 60),
        loopPlayback = root.optBoolean("loopPlayback", true),
        snapMs = root.optInt("snapMs", 100).coerceIn(10, 5000),
        audioFileName = root.optString("audioFileName").takeIf { it.isNotBlank() && it != "null" }?.take(120),
        audioOffsetMs = root.optInt("audioOffsetMs", 0).coerceIn(-3_600_000, 3_600_000),
        audioVolume = root.safeFloat("audioVolume", 1f, 0f, 1f)
    )
    project.activeFrameIndex = root.optInt("activeFrameIndex", 0).coerceIn(project.frames.indices)
    return project
}

private fun JSONObject.safeFloat(name: String, fallback: Float, min: Float, max: Float): Float {
    val value = optDouble(name, fallback.toDouble()).toFloat()
    return if (value.isFinite()) value.coerceIn(min, max) else fallback
}
