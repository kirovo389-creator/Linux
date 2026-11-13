package com.kirovo.linuxai.linux

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.GZIPInputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProotLinux(private val context: Context) {
    private val base = File(context.filesDir, "linux-sandbox")
    private val root = File(base, "rootfs")
    private val binDir = File(base, "bin")
    private val prootFile = File(binDir, "proot")
    private val loaderFile = AtomicReference<File?>(null)
    private val http = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var active: Process? = null

    fun isInstalled() = File(root, "bin/sh").exists() && prootFile.canExecute()

    suspend fun ensureInstalled(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        base.mkdirs(); binDir.mkdirs()
        if (!prootFile.canExecute()) {
            onProgress("Downloading Android PRoot runtime…")
            installProot()
        }
        if (!File(root, "bin/sh").exists()) {
            onProgress("Downloading Alpine Linux ARM64 root filesystem…")
            installRootfs(onProgress)
        }
        if (!File(root, "root/.linux-ai-ready").exists()) {
            onProgress("Installing base Linux tools…")
            runCommand("mkdir -p /workspace /root/.cache /tmp", "/root", 90)
            val installResult = runCommand("apk update && apk add bash ca-certificates curl wget git python3 py3-pip nodejs npm tar xz zip unzip", "/root", 900)
            if (!installResult.contains("[exit 0]")) error("Linux package installation failed: " + installResult.takeLast(3000))
            runCommand("touch /root/.linux-ai-ready", "/root", 30)
        }
    }

    private fun installProot() {
        val request = Request.Builder().url("https://api.github.com/repos/ahmed-alnassif/proot/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "LinuxAI-Android").get().build()
        val release = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Cannot fetch PRoot release metadata: HTTP " + response.code)
            JSONObject(response.body?.string().orEmpty())
        }
        val assets = release.optJSONArray("assets") ?: error("PRoot release has no assets")
        var assetUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "proot-aarch64.zip") assetUrl = asset.optString("browser_download_url")
        }
        val requestAsset = Request.Builder().url(assetUrl ?: error("No ARM64 PRoot binary in upstream release"))
            .header("User-Agent", "LinuxAI-Android").get().build()
        http.newCall(requestAsset).execute().use { response ->
            if (!response.isSuccessful) error("PRoot download failed: HTTP " + response.code)
            ZipInputStream(BufferedInputStream(response.body?.byteStream() ?: error("Empty PRoot download"))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.isNotBlank()) {
                        val target = File(binDir, name)
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                        if (name == "proot" || name.contains("loader", true)) target.setExecutable(true, false)
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!prootFile.exists()) error("PRoot archive did not contain the proot executable")
        prootFile.setExecutable(true, false)
        loaderFile.set(binDir.listFiles()?.firstOrNull { it.name.contains("loader", true) && it.isFile })
    }

    private fun installRootfs(onProgress: (String) -> Unit) {
        val indexUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
        val indexRequest = Request.Builder().url(indexUrl).header("User-Agent", "LinuxAI-Android").get().build()
        val index = http.newCall(indexRequest).execute().use { response ->
            if (!response.isSuccessful) error("Cannot query Alpine release index: HTTP " + response.code)
            response.body?.string().orEmpty()
        }
        val versions = Regex("alpine-minirootfs-([0-9.]+)-aarch64\\.tar\\.gz")
            .findAll(index).map { it.groupValues[1] }.toList()
        val version = versions.maxWithOrNull(Comparator { a, b -> compareVersions(a, b) })
            ?: error("Alpine ARM64 minirootfs not found in the official index")
        val url = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-" + version + "-aarch64.tar.gz"
        val archive = File(base, "alpine-rootfs.tar.gz")
        onProgress("Downloading Alpine Linux " + version + "…")
        val request = Request.Builder().url(url).header("User-Agent", "LinuxAI-Android").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Alpine download failed: HTTP " + response.code)
            FileOutputStream(archive).use { out -> response.body?.byteStream()?.copyTo(out) ?: error("Empty Alpine archive") }
        }
        onProgress("Extracting Linux filesystem…")
        root.mkdirs()
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("./")
                val target = File(root, name).canonicalFile
                if (target != root.canonicalFile && !target.path.startsWith(root.canonicalPath + File.separator)) continue
                when {
                    entry.isDirectory -> {
                        target.mkdirs(); target.setReadable(true, false); target.setWritable(true, false); target.setExecutable(true, false)
                    }
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        try { Os.symlink(entry.linkName, target.absolutePath) } catch (_: Exception) { }
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val link = File(root, entry.linkName).canonicalFile
                        try { if (link.exists()) java.nio.file.Files.createLink(target.toPath(), link.toPath()) } catch (_: Exception) { }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var remaining = entry.size
                            val buffer = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count <= 0) break
                                out.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                        target.setReadable(true, false); target.setWritable(true, true)
                        if ((entry.mode and 0b001001001) != 0) target.setExecutable(true, false)
                    }
                }
            }
        }
        archive.delete()
        if (!File(root, "bin/sh").exists()) error("Extracted rootfs is incomplete: /bin/sh missing")
        File(root, "etc/resolv.conf").apply { parentFile?.mkdirs(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(root, "etc/apk/repositories").writeText("https://dl-cdn.alpinelinux.org/alpine/latest-stable/main\nhttps://dl-cdn.alpinelinux.org/alpine/latest-stable/community\n")
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val diff = aa.getOrElse(i) { 0 }.compareTo(bb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    suspend fun runCommand(command: String, cwd: String = "/workspace", timeoutSeconds: Long = 600): String = withContext(Dispatchers.IO) {
        if (!isInstalled()) error("Linux is not installed. Call ensureInstalled first.")
        val workDir = if (cwd.startsWith('/')) cwd else "/workspace/" + cwd
        val args = listOf(prootFile.absolutePath, "-0", "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", workDir, "/bin/sh", "-lc", command)
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        builder.environment()["HOME"] = "/root"
        builder.environment()["USER"] = "root"
        builder.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        builder.environment()["TERM"] = "xterm-256color"
        loaderFile.get()?.let { builder.environment()["PROOT_LOADER"] = it.absolutePath }
        val process = builder.start()
        active = process
        val output = StringBuilder()
        val reader = Thread {
            try { process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (output.length < 60000) output.append(line).append('\n') }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            reader.join(1200); active = null
            return@withContext "Command timed out after " + timeoutSeconds + " seconds.\n" + output
        }
        reader.join(1500); active = null
        output.toString().trim().ifBlank { "(no output)" } + "\n[exit " + process.exitValue() + "]"
    }

    fun cancel() {
        active?.let { p -> try { p.destroy() } catch (_: Exception) { }; if (p.isAlive) try { p.destroyForcibly() } catch (_: Exception) { } }
        active = null
    }

    fun readFile(path: String): String {
        val file = sandboxFile(path)
        if (!file.exists()) error("File not found: " + path)
        if (file.isDirectory) error("Path is a directory: " + path)
        return file.readText().take(100000)
    }

    fun writeFile(path: String, content: String) {
        val file = sandboxFile(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun listFiles(path: String): String {
        val dir = sandboxFile(path)
        if (!dir.exists()) return "Directory does not exist: " + path
        return dir.listFiles()?.sortedBy { it.name }?.joinToString("\n") {
            if (it.isDirectory) "[DIR] " + it.name else it.name + " (" + it.length() + " bytes)"
        } ?: "(empty)"
    }

    fun exportFile(path: String): File {
        val source = sandboxFile(path)
        if (!source.isFile) error("Artifact not found: " + path)
        val dir = File(context.filesDir, "artifacts").apply { mkdirs() }
        val name = source.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
        val dest = File(dir, System.currentTimeMillis().toString() + "_" + name)
        source.copyTo(dest, overwrite = true)
        return dest
    }

    private fun sandboxFile(path: String): File {
        val rel = path.removePrefix("/rootfs/").removePrefix("/")
        val file = File(root, rel).canonicalFile
        if (file != root.canonicalFile && !file.path.startsWith(root.canonicalPath + File.separator)) {
            error("Path is outside the Linux root filesystem")
        }
        return file
    }
}
