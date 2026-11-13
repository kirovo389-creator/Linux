package com.kirovo.linuxai.agent

import android.content.Context
import com.kirovo.linuxai.data.ApiClient
import com.kirovo.linuxai.data.ConfigStore
import com.kirovo.linuxai.data.TaskStore
import com.kirovo.linuxai.linux.ProotLinux
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

class AgentEngine(context: Context, private val onStatus: (String) -> Unit) {
    private val config = ConfigStore(context)
    private val api = ApiClient(config)
    private val linux = ProotLinux(context)

    fun cancel() = linux.cancel()

    suspend fun runTask(task: String) {
        try {
            if (!config.isReady()) error("Сначала открой Settings и укажи API Base URL, API Key и модель.")
            onStatus("Starting Linux sandbox")
            linux.ensureInstalled { onStatus(it) }
            onStatus("Linux is ready · AI is planning")

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
            TaskStore.messages.value.takeLast(24).forEach { item ->
                if ((item.role == "user" || item.role == "assistant") && item.filePath == null) {
                    messages.put(JSONObject().put("role", item.role).put("content", item.text))
                }
            }

            val tools = toolDefinitions()
            var exported: File? = null
            var completed = false
            for (round in 1..500) {
                coroutineContext.ensureActive()
                onStatus("AI working · step " + round)
                val answer = api.complete(messages, tools)
                messages.put(answer)
                val calls = answer.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val text = answer.optString("content").trim().ifBlank { "Задача завершена." }
                    TaskStore.append("assistant", text, exported?.absolutePath)
                    completed = true
                    break
                }

                for (i in 0 until calls.length()) {
                    coroutineContext.ensureActive()
                    val call = calls.getJSONObject(i)
                    val callId = call.optString("id")
                    val function = call.optJSONObject("function") ?: JSONObject()
                    val name = function.optString("name")
                    val args = try { JSONObject(function.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }
                    onStatus("Linux · " + name)
                    val output = try {
                        when (name) {
                            "run_linux_command" -> {
                                val command = args.optString("command")
                                if (command.isBlank()) "Error: command is empty"
                                else linux.runCommand(command, args.optString("working_directory", "/workspace"),
                                    args.optLong("timeout_seconds", 600).coerceIn(5, 1800))
                            }
                            "read_file" -> linux.readFile(args.optString("path"))
                            "write_file" -> {
                                val path = args.optString("path")
                                val content = args.optString("content")
                                linux.writeFile(path, content)
                                "Wrote " + content.length + " characters to " + path
                            }
                            "list_files" -> linux.listFiles(args.optString("path", "/workspace"))
                            "web_search" -> api.searchDuckDuckGo(args.optString("query"))
                            "export_artifact" -> {
                                exported = linux.exportFile(args.optString("path"))
                                "Artifact exported: " + exported!!.name + " (" + exported!!.length() +
                                    " bytes). It will be attached to the chat for download."
                            }
                            else -> "Unknown tool: " + name
                        }
                    } catch (e: Exception) {
                        "Tool error: " + (e.message ?: e.javaClass.simpleName)
                    }
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", output.take(24000)))
                }
            }
            if (!completed) {
                TaskStore.append("assistant",
                    "Достигнут лимит одного задания (500 шагов). Файлы и песочница сохранены; отправь «продолжай», чтобы продолжить.",
                    exported?.absolutePath)
            }
            onStatus("Sandbox ready · task finished")
        } catch (e: kotlinx.coroutines.CancellationException) {
            onStatus("Task stopped")
            throw e
        } catch (e: Exception) {
            TaskStore.append("assistant", "Ошибка: " + (e.message ?: e.javaClass.simpleName))
            onStatus("Task failed · sandbox retained")
        }
    }

    private fun systemPrompt() = """
        You are Linux AI, an autonomous software engineering agent running on an Android phone.
        You have a persistent Alpine Linux ARM64 userspace sandbox with apk, Bash, Python 3, Git,
        Node.js, npm, curl, wget, zip and tar installed. The Linux root is isolated in the app's
        private files directory and is not a full virtual machine: it shares the Android kernel.
        Work in /workspace. Use web_search for current documentation, versions, assets and downloads.

        Execute real work using tools; do not merely provide instructions. For coding tasks: plan,
        create the project, obtain needed dependencies/assets, run tests/builds, inspect failures,
        fix them, and repeat. For Godot projects prefer headless import/export workflows and verify
        the generated artifact. Never claim a build succeeded unless the output was checked.
        If an APK cannot be built on ARM64 Linux, explain the blocker and export the best working
        project/archive instead. Keep intermediate files in /workspace. Export final deliverables
        with export_artifact, then summarize contents and usage.

        Use Alpine commands (apk, not apt). Search before relying on changing versions or URLs.
        Keep commands focused; inspect output and fix errors. Do not write outside the Linux root.
        Never put API secrets in generated files. Be honest about limitations and partial results.
    """.trimIndent()

    private fun toolDefinitions(): JSONArray {
        fun tool(name: String, description: String, properties: JSONObject, required: JSONArray) =
            JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", required)))

        val tools = JSONArray()
        tools.put(tool("run_linux_command", "Run a shell command inside the isolated Linux root filesystem. Work in /workspace. Install packages, compile code and run tests.",
            JSONObject().put("command", JSONObject().put("type", "string"))
                .put("working_directory", JSONObject().put("type", "string"))
                .put("timeout_seconds", JSONObject().put("type", "integer")), JSONArray().put("command")))
        tools.put(tool("read_file", "Read a UTF-8 file inside Linux.", JSONObject().put("path", JSONObject().put("type", "string")), JSONArray().put("path")))
        tools.put(tool("write_file", "Create or replace a UTF-8 file inside Linux.",
            JSONObject().put("path", JSONObject().put("type", "string")).put("content", JSONObject().put("type", "string")),
            JSONArray().put("path").put("content")))
        tools.put(tool("list_files", "List files in the Linux root filesystem.",
            JSONObject().put("path", JSONObject().put("type", "string")), JSONArray()))
        tools.put(tool("web_search", "Search public web pages with DuckDuckGo for current docs, versions, downloads and assets.",
            JSONObject().put("query", JSONObject().put("type", "string")), JSONArray().put("query")))
        tools.put(tool("export_artifact", "Copy a finished file from Linux into the Android chat as a downloadable attachment.",
            JSONObject().put("path", JSONObject().put("type", "string")), JSONArray().put("path")))
        return tools
    }
}
