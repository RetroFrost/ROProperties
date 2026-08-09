package com.frameflow.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class BrushPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("frameflow-brush-preferences", Context.MODE_PRIVATE)

    data class SavedTool(val name: String, val family: String, val width: Float, val alpha: Float)

    fun favourites(eraser: Boolean): Set<String> = prefs.getStringSet(if (eraser) "eraser-favourites" else "brush-favourites", emptySet())?.toSet().orEmpty()

    fun toggleFavourite(eraser: Boolean, name: String): Set<String> {
        val key = if (eraser) "eraser-favourites" else "brush-favourites"
        val current = favourites(eraser).toMutableSet()
        if (!current.add(name)) current.remove(name)
        prefs.edit().putStringSet(key, current).apply()
        return current.toSet()
    }

    fun recents(eraser: Boolean): List<String> {
        val raw = prefs.getString(if (eraser) "eraser-recents" else "brush-recents", "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until minOf(array.length(), 16)) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
        }.getOrDefault(emptyList())
    }

    fun markRecent(eraser: Boolean, name: String) {
        val values = recents(eraser).toMutableList()
        values.remove(name)
        values.add(0, name)
        while (values.size > 16) values.removeAt(values.lastIndex)
        prefs.edit().putString(if (eraser) "eraser-recents" else "brush-recents", JSONArray(values).toString()).apply()
    }

    fun saveTool(projectId: String, eraser: Boolean, preset: BrushPreset) {
        val id = safeProject(projectId)
        val key = if (eraser) "tool-$id-eraser" else "tool-$id-brush"
        val json = JSONObject().apply {
            put("name", preset.name.take(80))
            put("family", preset.family.take(80))
            put("width", preset.width.toDouble())
            put("alpha", preset.alpha.toDouble())
        }
        prefs.edit().putString(key, json.toString()).apply()
    }

    fun loadTool(projectId: String, eraser: Boolean): SavedTool? {
        val id = safeProject(projectId)
        val key = if (eraser) "tool-$id-eraser" else "tool-$id-brush"
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val width = json.optDouble("width", 12.0).toFloat()
            val alpha = json.optDouble("alpha", 1.0).toFloat()
            SavedTool(
                name = json.optString("name", if (eraser) "Eraser 1" else "Ink 1").take(80),
                family = json.optString("family", if (eraser) "Eraser" else "Ink").take(80),
                width = width.takeIf { it.isFinite() }?.coerceIn(1f, 300f) ?: 12f,
                alpha = alpha.takeIf { it.isFinite() }?.coerceIn(.02f, 1f) ?: 1f
            )
        }.getOrNull()
    }

    fun restoreInto(projectId: String, editor: EditorState) {
        loadTool(projectId, false)?.let { saved ->
            val base = brushes.firstOrNull { it.name == saved.name } ?: brushes.firstOrNull { it.family == saved.family }
            if (base != null) editor.brush = base.copy(width = saved.width, alpha = saved.alpha)
        }
        loadTool(projectId, true)?.let { saved ->
            val base = erasers.firstOrNull { it.name == saved.name } ?: erasers.firstOrNull()
            if (base != null) editor.eraser = base.copy(width = saved.width, alpha = saved.alpha)
        }
    }

    private fun safeProject(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128)
}
