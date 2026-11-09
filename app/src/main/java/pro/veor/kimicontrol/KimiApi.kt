package pro.veor.kimicontrol

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Official Kimi Code server protocol, behind an authenticated HTTPS gateway.
 * Never connect the experimental CLI server directly to the public internet.
 * All methods are blocking; invoke via Dispatchers.IO.
 */
class KimiApi(private val endpoint: String, private val token: String) {
    init {
        require(endpoint.startsWith("https://") && !endpoint.contains("@")) { "Требуется HTTPS адрес" }
        require(token.isNotBlank()) { "Не указан токен доступа" }
    }

    private fun request(path: String, method: String = "GET", json: JSONObject? = null): JSONObject {
        val url = URL(endpoint.trimEnd('/') + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 12_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false // never leak bearer token via redirect
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(json.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
                throw KimiApiException("HTTP $status: $detail")
            }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val envelope = JSONObject(payload)
            if (envelope.optInt("code", -1) != 0) {
                throw KimiApiException("Kimi ${envelope.optInt("code")}: ${envelope.optString("msg", "Ошибка")}")
            }
            return envelope.optJSONObject("data") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }

    fun check(): JSONObject = request("/api/v1/meta")

    fun sessions(): List<KimiSession> {
        val data = request("/api/v1/sessions?page_size=40")
        val items = data.optJSONArray("items") ?: data.optJSONArray("sessions") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let {
                KimiSession(it.optString("id"), it.optString("title").ifBlank { "Сессия ${i + 1}" },
                    it.optBoolean("busy"), it.optString("pending_interaction"))
            }
        }.filter { it.id.isNotBlank() }
    }

    fun newSession(cwd: String): KimiSession {
        require(cwd.startsWith("/") && cwd.length > 1) { "Укажите абсолютный путь рабочей папки на CONTROL" }
        val data = request("/api/v1/sessions", "POST",
            JSONObject().put("metadata", JSONObject().put("cwd", cwd)))
        val id = data.optString("id")
        if (id.isBlank()) throw KimiApiException("Kimi не вернул ID сессии")
        return KimiSession(id, data.optString("title").ifBlank { "Новая сессия" }, false, "none")
    }

    fun messages(sessionId: String): List<KimiMessage> {
        val data = request("/api/v1/sessions/${segment(sessionId)}/messages?page_size=80")
        val items = data.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { obj ->
                val parts = obj.optJSONArray("content") ?: JSONArray()
                val text = buildString {
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") {
                            if (isNotEmpty()) append("\n")
                            append(part.optString("text"))
                        }
                    }
                }
                val role = obj.optString("role")
                if (text.isBlank() || role !in setOf("assistant", "user", "tool")) null
                else KimiMessage(obj.optString("id", "${i}"), role, text)
            }
        }.reversed()
    }

    fun pendingApprovals(sessionId: String): List<KimiApproval> {
        val data = request("/api/v1/sessions/${segment(sessionId)}/approvals?status=pending")
        val items = data.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("approval_id")
            if (id.isBlank()) null else KimiApproval(
                id,
                listOf(item.optString("tool_name"), item.optString("action"),
                    item.optString("tool_input_display")).filter { it.isNotBlank() }.joinToString("\\n")
            )
        }
    }

    fun runningPrompt(sessionId: String): KimiRunningPrompt? {
        val data = request("/api/v1/sessions/${segment(sessionId)}/prompts")
        val active = data.optJSONObject("active") ?: return null
        val id = active.optString("prompt_id")
        return if (id.isBlank()) null else KimiRunningPrompt(id, active.optString("status"))
    }

    fun abortPrompt(sessionId: String, promptId: String) {
        request("/api/v1/sessions/${segment(sessionId)}/prompts/${segment(promptId)}:abort",
            "POST", JSONObject())
    }

    fun resolveApproval(sessionId: String, approvalId: String, approved: Boolean) {
        request("/api/v1/sessions/${segment(sessionId)}/approvals/${segment(approvalId)}",
            "POST", JSONObject().put("decision", if (approved) "approved" else "rejected"))
    }

    fun send(sessionId: String, message: String, uploaded: List<KimiUpload>): String {
        val parts = JSONArray()
        if (message.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", message))
        uploaded.forEach { file ->
            if (file.mime.startsWith("image/")) {
                parts.put(JSONObject().put("type", "image")
                    .put("source", JSONObject().put("kind", "file").put("file_id", file.id)))
            } else {
                parts.put(JSONObject().put("type", "file").put("file_id", file.id)
                    .put("name", file.name).put("media_type", file.mime).put("size", file.size))
            }
        }
        require(parts.length() > 0) { "Пустое сообщение" }
        val accepted = request("/api/v1/sessions/${segment(sessionId)}/prompts", "POST",
            JSONObject().put("content", parts).put("prompt_id", UUID.randomUUID().toString()))
        return accepted.optString("prompt_id")
    }

    fun upload(context: Context, uri: Uri): KimiUpload {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "attachment"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (length > 30L * 1024 * 1024) throw KimiApiException("Файл больше 30 МБ")
        val boundary = "KimiControl${UUID.randomUUID().toString().replace("-", "")}"
        val connection = URL(endpoint.trimEnd('/') + "/api/v1/files").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        var transferred = 0L
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"attachment\"\r\nContent-Type: $mime\r\n\r\n").toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val bytes = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                transferred += read
                if (transferred > 30L * 1024 * 1024) throw KimiApiException("Лимит вложения 30 МБ")
                bytes.write(buffer, 0, read)
            }
        } ?: throw KimiApiException("Не удалось прочитать вложение")
        connection.setFixedLengthStreamingMode(prefix.size + bytes.size() + suffix.size)
        try {
            connection.outputStream.use { output ->
                output.write(prefix)
                bytes.writeTo(output)
                output.write(suffix)
            }
            val status = connection.responseCode
            if (status !in 200..299) throw KimiApiException("Ошибка загрузки HTTP $status")
            val envelope = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (envelope.optInt("code", -1) != 0) throw KimiApiException(envelope.optString("msg", "Ошибка загрузки"))
            val data = envelope.getJSONObject("data")
            return KimiUpload(data.getString("id"), data.optString("name", name),
                data.optString("media_type", mime), data.optLong("size", transferred))
        } finally { connection.disconnect() }
    }

    fun downloadWorkspaceFile(context: Context, sessionId: String, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") &&
            relativePath.split('/').none { it == ".." }) { "Только относительный путь внутри проекта" }
        val path = relativePath.split('/').joinToString("/") { segment(it) }
        val connection = URL(endpoint.trimEnd('/') +
            "/api/v1/sessions/${segment(sessionId)}/fs/$path:download").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (connection.responseCode !in 200..299) throw KimiApiException("Не удалось скачать файл: HTTP ${connection.responseCode}")
            val name = relativePath.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9А-Яа-я._-]"), "_")
            val file = File(context.filesDir, "kimi_${UUID.randomUUID()}_$name")
            connection.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > 100L * 1024 * 1024) throw KimiApiException("Файл больше 100 МБ")
                        out.write(buffer, 0, n)
                    }
                }
            }
            return file
        } finally { connection.disconnect() }
    }

    private fun segment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

data class KimiSession(val id: String, val title: String, val busy: Boolean, val interaction: String)
data class KimiMessage(val id: String, val role: String, val text: String)
data class KimiUpload(val id: String, val name: String, val mime: String, val size: Long)
class KimiApiException(message: String) : Exception(message)

data class KimiApproval(val id: String, val description: String)

data class KimiRunningPrompt(val id: String, val status: String)
