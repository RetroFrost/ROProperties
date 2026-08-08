package com.frameflow.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectRepository(private val context: Context) {
    private val projectDir = File(context.filesDir, "frameflow-projects").apply { mkdirs() }

    fun listProjects(): List<ProjectMeta> = projectDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "frameflow" }
        .mapNotNull { file ->
            runCatching { projectFromJson(file.readText()).toMeta() }.getOrNull()
        }
        .sortedByDescending { it.modifiedAt }

    fun createProject(name: String = "Untitled animation"): ProjectState {
        val project = ProjectState(name = name.ifBlank { "Untitled animation" })
        save(project)
        return project
    }

    fun load(id: String): ProjectState? {
        val file = projectFile(id)
        if (!file.exists()) return null
        return runCatching { projectFromJson(file.readText()) }.getOrNull()
    }

    fun save(project: ProjectState) {
        projectFile(project.id).writeText(projectToJson(project).toString())
    }

    fun delete(id: String) {
        projectFile(id).delete()
    }

    fun duplicate(project: ProjectState): ProjectState {
        val clone = ProjectState(
            id = UUID.randomUUID().toString(),
            name = "${project.name} copy",
            canvasWidth = project.canvasWidth,
            canvasHeight = project.canvasHeight,
            backgroundArgb = project.backgroundArgb,
            frames = project.frames.map { it.cloneFrame() }
        )
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
            frames = imported.frames.map { it.cloneFrame() }
        )
        save(local)
        return local
    }

    fun exportProject(project: ProjectState, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
            it.write(projectToJson(project).toString(2))
        } ?: error("Unable to create project file")
    }

    fun exportCurrentPng(project: ProjectState, frameIndex: Int, uri: Uri) {
        val bitmap = FrameRenderer.render(project, frameIndex)
        context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        } ?: error("Unable to create PNG")
        bitmap.recycle()
    }

    fun exportFramesZip(project: ProjectState, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                project.frames.forEachIndexed { index, _ ->
                    val bitmap = FrameRenderer.render(project, index)
                    zip.putNextEntry(ZipEntry("frame-${(index + 1).toString().padStart(4, '0')}.png"))
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    bitmap.recycle()
                }
                zip.putNextEntry(ZipEntry("project.frameflow"))
                zip.write(projectToJson(project).toString(2).toByteArray())
                zip.closeEntry()
            }
        } ?: error("Unable to create frame archive")
    }

    private fun projectFile(id: String) = File(projectDir, "$id.frameflow")
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
    put("frames", JSONArray().apply {
        project.frames.forEach { frame -> put(frameToJson(frame)) }
    })
}

private fun frameToJson(frame: FrameState) = JSONObject().apply {
    put("durationMs", frame.durationMs)
    put("layers", JSONArray().apply {
        frame.layers.forEach { layer -> put(layerToJson(layer)) }
    })
}

private fun layerToJson(layer: LayerState) = JSONObject().apply {
    put("name", layer.name)
    put("part", layer.part.name)
    put("visible", layer.visible)
    put("locked", layer.locked)
    put("strokes", JSONArray().apply {
        layer.strokes.forEach { stroke -> put(strokeToJson(stroke)) }
    })
}

private fun strokeToJson(stroke: StrokeData) = JSONObject().apply {
    put("id", stroke.id)
    put("colorArgb", stroke.colorArgb)
    put("width", stroke.width.toDouble())
    put("alpha", stroke.alpha.toDouble())
    put("erase", stroke.erase)
    put("points", JSONArray().apply {
        stroke.points.forEach { point ->
            put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
        }
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
                                    if (point.length() >= 2) {
                                        add(CanvasPoint(point.optDouble(0).toFloat(), point.optDouble(1).toFloat()))
                                    }
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
                            locked = layerObject.optBoolean("locked", false)
                        )
                    )
                }
            }
            add(
                FrameState(
                    duration = frameObject.optInt("durationMs", 1000).coerceIn(50, 60000),
                    layers = layers.ifEmpty { defaultLayers() }
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
        modified = root.optLong("modifiedAt", System.currentTimeMillis())
    )
}
