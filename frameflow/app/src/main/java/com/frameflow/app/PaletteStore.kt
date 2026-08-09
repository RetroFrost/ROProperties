package com.frameflow.app

import android.content.Context
import org.json.JSONArray

class PaletteStore(context: Context) {
    private val prefs = context.getSharedPreferences("frameflow-palettes", Context.MODE_PRIVATE)

    fun load(projectId: String): List<Int> {
        val raw = prefs.getString(key(projectId), "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until minOf(array.length(), 64)) add(array.optInt(i))
            }.distinct()
        }.getOrDefault(emptyList())
    }

    fun add(projectId: String, argb: Int): List<Int> {
        val values = load(projectId).toMutableList()
        values.remove(argb)
        values.add(0, argb)
        while (values.size > 64) values.removeAt(values.lastIndex)
        save(projectId, values)
        return values
    }

    fun remove(projectId: String, argb: Int): List<Int> {
        val values = load(projectId).filterNot { it == argb }
        save(projectId, values)
        return values
    }

    fun clear(projectId: String) = prefs.edit().remove(key(projectId)).apply()

    private fun save(projectId: String, values: List<Int>) {
        prefs.edit().putString(key(projectId), JSONArray(values).toString()).apply()
    }

    private fun key(projectId: String) = "palette-" + projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128)
}
