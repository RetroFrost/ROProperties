package com.frameflow.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val MAX_LAZY_CHAT_MESSAGES = 120
private const val MAX_LAZY_CHAT_TEXT = 1600

data class LazyChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class LazyChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("frameflow-lazy-chat", Context.MODE_PRIVATE)

    fun load(projectId: String): List<LazyChatMessage> {
        val raw = prefs.getString(key(projectId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = item.optString("role").takeIf { it == "user" || it == "assistant" } ?: continue
                    val text = item.optString("text").take(MAX_LAZY_CHAT_TEXT)
                    if (text.isBlank()) continue
                    add(
                        LazyChatMessage(
                            id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                            role = role,
                            text = text,
                            timestamp = item.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }.takeLast(MAX_LAZY_CHAT_MESSAGES)
        }.getOrDefault(emptyList())
    }

    fun save(projectId: String, messages: List<LazyChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_LAZY_CHAT_MESSAGES).forEach { message ->
            array.put(JSONObject().apply {
                put("id", message.id)
                put("role", message.role)
                put("text", message.text.take(MAX_LAZY_CHAT_TEXT))
                put("timestamp", message.timestamp)
            })
        }
        prefs.edit().putString(key(projectId), array.toString()).apply()
    }

    fun clear(projectId: String) {
        prefs.edit().remove(key(projectId)).apply()
    }

    private fun key(projectId: String): String = "chat_${projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128)}"
}
