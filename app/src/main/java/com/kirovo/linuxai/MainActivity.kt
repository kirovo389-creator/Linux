package com.kirovo.linuxai

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirovo.linuxai.agent.AgentService
import com.kirovo.linuxai.data.ApiClient
import com.kirovo.linuxai.data.ChatItem
import com.kirovo.linuxai.data.ConfigStore
import com.kirovo.linuxai.data.TaskStore
import kotlinx.coroutines.launch
import java.io.File

private val Bg = Color(0xFF191919)
private val Panel = Color(0xFF242424)
private val Panel2 = Color(0xFF303030)
private val Accent = Color(0xFFD8876C)
private val Ink = Color(0xFFF0EFED)
private val Muted = Color(0xFF92908E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskStore.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 700)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, onPrimary = Color.Black, background = Bg, onBackground = Ink,
                surface = Panel, onSurface = Ink, secondary = Muted
            )) { LinuxAIApp() }
        }
    }
}

@Composable
private fun LinuxAIApp() {
    val context = LocalContext.current
    val config = remember { ConfigStore(context) }
    var showSettings by remember { mutableStateOf(!config.isReady()) }
    var showMenu by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val messages by TaskStore.messages.collectAsState()
    val working by TaskStore.working.collectAsState()
    val status by TaskStore.status.collectAsState()

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = pendingFile
        if (uri != null && file != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                TaskStore.append("assistant", "Saved: " + file.name)
            } catch (e: Exception) {
                TaskStore.append("assistant", "Could not save file: " + e.message)
            }
        }
        pendingFile = null
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(46.dp).clip(CircleShape).background(Panel)) {
                    Icon(Icons.Default.Menu, "Menu", tint = Ink)
                }
                Text(
                    if (messages.isEmpty()) "Linux AI" else messages.lastOrNull { it.role == "user" }?.text?.take(28) ?: "Linux AI",
                    color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 1
                )
                IconButton(onClick = { showSettings = true }, modifier = Modifier.size(46.dp).clip(CircleShape).background(Panel)) {
                    Icon(Icons.Default.Settings, "Settings", tint = Ink)
                }
            }

            if (messages.isEmpty()) {
                EmptyHome(Modifier.weight(1f)) { draft = it }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { item ->
                        ChatBubble(item) { file ->
                            pendingFile = file
                            saveFile.launch(file.name.substringAfter('_', file.name))
                        }
                    }
                    if (working) item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                            Text(status, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 9.dp))
                        }
                    }
                }
            }

            if (working || status.contains("Sandbox", true)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = Accent, modifier = Modifier.size(15.dp))
                    Text(status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
                    Spacer(Modifier.weight(1f))
                    Text("Linux userspace", color = Muted, fontSize = 11.sp)
                }
            }

            Composer(draft, { draft = it }, config.model.ifBlank { "Choose model" }, working, { showSettings = true }) {
                val text = draft.trim()
                if (text.isNotEmpty() && !working) {
                    if (!config.isReady()) showSettings = true
                    else {
                        TaskStore.append("user", text)
                        draft = ""
                        AgentService.start(context, text)
                    }
                }
            }
        }
    }

    if (showSettings) SettingsDialog(config, !config.isReady(), { showSettings = false })
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false }, containerColor = Panel,
            title = { Text("Linux AI", color = Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    MenuItem("New chat", "Start a fresh conversation") { TaskStore.clear(); showMenu = false }
                    MenuItem("Settings", "API provider and model") { showMenu = false; showSettings = true }
                    MenuItem("Linux sandbox", "Persistent private Linux rootfs") {
                        showMenu = false
                        TaskStore.append("assistant", "The Linux userspace is stored in private app storage. It stays installed after chat tasks finish.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AgentService.stop(context)
                    showMenu = false
                    (context as? ComponentActivity)?.finishAffinity()
                }) { Text("Exit", color = Accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showMenu = false }) { Text("Close", color = Muted) } }
        )
    }
}

@Composable
private fun EmptyHome(modifier: Modifier, onPrompt: (String) -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.TipsAndUpdates, null, tint = Accent, modifier = Modifier.size(48.dp))
        Text("Let's build something", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        Text("AI with its own Linux workspace", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Suggestion(Modifier.weight(1f), "Create a Godot game", "Build a game project") {
                onPrompt("Create a complete Godot game in Linux, obtain suitable assets, test it headlessly and export the best working artifact.")
            }
            Suggestion(Modifier.weight(1f), "Write a Python app", "Code and test") {
                onPrompt("Create a useful Python application in Linux, add tests, run them, and export the project.")
            }
        }
    }
}

@Composable
private fun Suggestion(modifier: Modifier, title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier.height(112.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Default.CheckCircle, null, tint = Muted, modifier = Modifier.size(20.dp))
            Column {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ChatBubble(item: ChatItem, onDownload: (File) -> Unit) {
    val isUser = item.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(if (isUser) 0.84f else 0.98f)
                .clip(RoundedCornerShape(if (isUser) 22.dp else 12.dp))
                .background(if (isUser) Color(0xFF292929) else Color.Transparent)
                .padding(horizontal = if (isUser) 15.dp else 5.dp, vertical = 11.dp)
        ) {
            Text(item.text, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
            val file = item.filePath?.let(::File)
            if (file != null && file.exists()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                        .clickable { onDownload(file) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Download, null, tint = Accent)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(file.name.substringAfter('_', file.name), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text((file.length() / 1024).toString() + " KB · Tap to save", color = Muted, fontSize = 11.sp)
                    }
                    Text("Save", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String, onDraft: (String) -> Unit, model: String, working: Boolean,
    onSettings: () -> Unit, onSend: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)
            .clip(RoundedCornerShape(27.dp)).background(Color(0xFF151515))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = draft, onValueChange = onDraft, modifier = Modifier.fillMaxWidth().height(58.dp),
            textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp),
            decorationBox = { inner ->
                Box { if (draft.isBlank()) Text("Ask, build, research anything…", color = Muted, fontSize = 16.sp); inner() }
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSettings, modifier = Modifier.size(38.dp).clip(CircleShape).background(Panel2)) {
                Icon(Icons.Default.Add, "API settings", tint = Ink)
            }
            Spacer(Modifier.width(9.dp))
            Surface(shape = CircleShape, color = Panel, modifier = Modifier.clickable(onClick = onSettings)) {
                Text(model, color = Ink, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onSend, enabled = !working && draft.isNotBlank(),
                modifier = Modifier.size(42.dp).clip(CircleShape).background(if (!working && draft.isNotBlank()) Accent else Panel2)
            ) {
                Icon(Icons.Default.Send, "Send", tint = if (!working && draft.isNotBlank()) Color.Black else Muted)
            }
        }
    }
}

@Composable
private fun SettingsDialog(config: ConfigStore, forceSetup: Boolean, onClose: () -> Unit) {
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var models by remember { mutableStateOf(config.savedModels) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!forceSetup) onClose() }, containerColor = Panel,
        title = { Text("Connect your AI", color = Ink, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("OpenAI-compatible provider", color = Muted, fontSize = 12.sp)
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        message = ""; loading = true
                        config.baseUrl = baseUrl; config.apiKey = apiKey
                        scope.launch {
                            try {
                                models = ApiClient(config).fetchModels()
                                config.savedModels = models
                                if (model.isBlank() || model !in models) model = models.firstOrNull().orEmpty()
                                message = if (models.isEmpty()) "The API returned no models." else "Loaded " + models.size + " models"
                            } catch (e: Exception) { message = e.message ?: "Could not load models" }
                            loading = false
                        }
                    },
                    enabled = !loading && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(17.dp), color = Color.Black, strokeWidth = 2.dp)
                    else Text("Load available models")
                }
                Box {
                    OutlinedTextField(model, { model = it }, label = { Text("Model ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                        trailingIcon = { TextButton(onClick = { expanded = true }) { Text("List", color = Accent) } })
                    DropdownMenu(expanded, { expanded = false }) {
                        models.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { model = id; expanded = false }) }
                    }
                }
                if (message.isNotBlank()) Text(message, color = if (message.startsWith("Loaded")) Color(0xFF8FD3A8) else Color(0xFFFF9D89), fontSize = 12.sp)
                Text("API key encrypted with Android Keystore. Models are loaded from GET /models.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                config.baseUrl = baseUrl; config.apiKey = apiKey; config.model = model; config.savedModels = models
                onClose()
            }, enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
                Text("Save", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { if (!forceSetup) TextButton(onClick = onClose) { Text("Cancel", color = Muted) } }
    )
}

private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink, unfocusedTextColor = Ink,
    focusedBorderColor = Accent, unfocusedBorderColor = Panel2,
    focusedLabelColor = Accent, unfocusedLabelColor = Muted, cursorColor = Accent
)

@Composable
private fun MenuItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp)) {
        Text(title, color = Ink, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}
