package com.kirovo.linuxai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatItem(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val filePath: String? = null
)

object TaskStore {
    private const val PREFS = "linux_ai_chat"
    private const val KEY_MESSAGES = "messages"
    private lateinit var appContext: Context
    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working
    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status

    fun init(context: Context) {
        appContext = context.applicationContext
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MESSAGES, "[]")
        _messages.value = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatItem(o.optString("id"), o.optString("role"), o.optString("text"),
                    o.optString("file").takeIf { it.isNotBlank() })
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun append(role: String, text: String, filePath: String? = null) {
        _messages.value = _messages.value + ChatItem(role = role, text = text, filePath = filePath)
        persist()
    }

    @Synchronized fun clear() {
        _messages.value = emptyList()
        persist()
    }

    fun setWorking(value: Boolean, label: String = if (value) "Working in Linux sandbox" else "Sandbox ready") {
        _working.value = value
        _status.value = label
    }

    fun setStatus(label: String) { _status.value = label }

    private fun persist() {
        val arr = JSONArray()
        _messages.value.takeLast(150).forEach { item ->
            arr.put(JSONObject().put("id", item.id).put("role", item.role)
                .put("text", item.text).put("file", item.filePath ?: ""))
        }
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MESSAGES, arr.toString()).apply()
        }
    }
}
