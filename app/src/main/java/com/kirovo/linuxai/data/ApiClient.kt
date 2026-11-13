package com.kirovo.linuxai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiClient(private val config: ConfigStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.MINUTES)
        .build()

    private fun apiRoot(): String {
        val base = config.baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1")) base else base + "/v1"
    }

    suspend fun fetchModels(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(apiRoot() + "/models")
            .header("Authorization", "Bearer " + config.apiKey).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP " + response.code + ": " + body.take(700))
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.optString("id")?.takeIf { !it.isNullOrBlank() }
            }.distinct().sorted()
        }
    }

    suspend fun complete(messages: JSONArray, tools: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("model", config.model).put("messages", messages)
            .put("temperature", 0.2).put("stream", false)
        if (tools.length() > 0) payload.put("tools", tools).put("tool_choice", "auto")
        val request = Request.Builder().url(apiRoot() + "/chat/completions")
            .header("Authorization", "Bearer " + config.apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP " + response.code + ": " + body.take(1200))
            JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: error("API returned no assistant message: " + body.take(500))
        }
    }

    suspend fun searchDuckDuckGo(query: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder().url("https://html.duckduckgo.com/html/?q=" + encoded)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36")
            .get().build()
        http.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("DuckDuckGo HTTP " + response.code)
            val linkRegex = Regex("(?is)<a[^>]*class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>")
            val snippetRegex = Regex("(?is)<a[^>]*class=\\\"result__snippet\\\"[^>]*>(.*?)</a>")
            val links = linkRegex.findAll(html).take(8).toList()
            if (links.isEmpty()) return@withContext "No results parsed. Search URL: https://duckduckgo.com/?q=" + encoded
            links.mapIndexed { index, match ->
                val title = cleanHtml(match.groupValues[2])
                val url = match.groupValues[1].replace("&amp;", "&")
                val snippet = snippetRegex.findAll(html).drop(index).firstOrNull()?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
                (index + 1).toString() + ". " + title + "\n" + url + "\n" + snippet
            }.joinToString("\n\n")
        }
    }

    private fun cleanHtml(value: String) = value.replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()
}
