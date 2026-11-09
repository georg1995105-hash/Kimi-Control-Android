package pro.veor.kimicontrol

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mint = Color(0xFF57D7C2)
private val Amber = Color(0xFFFFC887)
private val Dim = Color(0xFFAABEC2)
private val SurfaceDark = Color(0xFF1B2931)
private val BaseDark = Color(0xFF10161C)

@Composable
fun ConnectedScreen() {
    val context = LocalContext.current
    val safe = remember { SecureConnection(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var endpoint by remember { mutableStateOf(safe.endpoint()) }
    var workdir by remember { mutableStateOf(safe.workdir()) }
    var accessToken by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var session by remember { mutableStateOf(safe.lastSession()) }
    var sessions by remember { mutableStateOf(emptyList<KimiSession>()) }
    var history by remember { mutableStateOf(emptyList<KimiMessage>()) }
    var approvals by remember { mutableStateOf(emptyList<KimiApproval>()) }
    val files = remember { mutableStateListOf<Uri>() }
    var downloadPath by remember { mutableStateOf("") }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }

    fun api(): KimiApi = KimiApi(safe.endpoint(), safe.token())
    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            working = true
            error = ""
            try { action() }
            catch (t: Exception) { error = t.message ?: "Ошибка соединения" }
            finally { working = false }
        }
    }
    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            val client = api()
            Triple(client.sessions(),
                if (session.isNotBlank()) client.messages(session) else emptyList(),
                if (session.isNotBlank()) client.pendingApprovals(session) else emptyList())
        }
        sessions = result.first
        history = result.second
        approvals = result.third
    }
    fun connect() = runAction {
        withContext(Dispatchers.IO) { api().check() }
        connected = true
        refresh()
        tab = 0
    }
    LaunchedEffect(connected, session) {
        if (connected) {
            while (true) {
                delay(5000)
                if (!working) {
                    try { refresh() }
                    catch (t: Exception) { error = t.message ?: "Соединение прервано"; connected = false }
                }
            }
        }
    }
    val selectPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
        selected.forEach { if (files.size < 10 && it !in files) files.add(it) }
    }
    val selectFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        selected.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (files.size < 10 && uri !in files) files.add(uri)
        }
    }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                prompt = listOf(prompt.trim(), it.trim()).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }
    val saveDownload = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val local = downloaded
        if (uri != null && local != null) {
            runAction {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { destination ->
                        local.inputStream().use { it.copyTo(destination) }
                    } ?: throw IllegalStateException("Не удалось сохранить файл")
                    local.delete()
                }
                downloaded = null
                Toast.makeText(context, "Файл сохранён", Toast.LENGTH_SHORT).show()
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = BaseDark,
        background = BaseDark, surface = SurfaceDark, onSurface = Color.White)) {
        Scaffold(
            containerColor = BaseDark,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF153C3A)) {
                            Text("K", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = Mint, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Kimi Control", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (tab) { 0 -> "Рабочие сессии"; 1 -> "Передача файлов"; else -> "Подключение и настройки" },
                                color = Dim, style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Surface(
                            color = if (connected) Color(0xFF193A2D) else Color(0xFF3A2C23),
                            shape = RoundedCornerShape(30.dp)
                        ) {
                            Text(
                                if (connected) "● Онлайн" else "● Офлайн",
                                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                color = if (connected) Mint else Amber,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF293A41))
                }
            },
            bottomBar = {
                NavigationBar(containerColor = SurfaceDark, modifier = Modifier.navigationBarsPadding()) {
                    NavigationBarItem(tab == 0, { tab = 0 },
                        icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("Чат") })
                    NavigationBarItem(tab == 1, { tab = 1 },
                        icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Файлы") })
                    NavigationBarItem(tab == 2, { tab = 2 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
                }
            }
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                when (tab) {
                    0 -> {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (connected) {
                                var open by remember { mutableStateOf(false) }
                                Box(Modifier.weight(1f)) {
                                    TextButton(onClick = { open = true }) {
                                        Text(sessions.firstOrNull { it.id == session }?.title ?: "Выбрать сессию",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                        sessions.forEach { item ->
                                            DropdownMenuItem(
                                                text = { Text(item.title, maxLines = 2) },
                                                onClick = {
                                                    session = item.id
                                                    safe.saveSession(item.id)
                                                    open = false
                                                    runAction { refresh() }
                                                }
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = {
                                    runAction {
                                        val created = withContext(Dispatchers.IO) { api().newSession(safe.workdir()) }
                                        session = created.id
                                        safe.saveSession(created.id)
                                        refresh()
                                    }
                                }, enabled = !working) { Icon(Icons.Default.AddCircleOutline, "Новая сессия", tint = Mint) }
                                IconButton(onClick = { runAction { refresh() } }, enabled = !working) {
                                    Icon(Icons.Default.Refresh, "Обновить", tint = Mint)
                                }
                            } else {
                                Text("Для работы подключите Kimi Code в настройках",
                                    Modifier.weight(1f), color = Dim,
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { tab = 2 }) { Text("Подключить") }
                            }
                        }
                        HorizontalDivider(color = Color(0xFF293A41))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!connected || history.isEmpty()) {
                                item {
                                    Column(Modifier.fillParentMaxHeight(0.75f).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center) {
                                        Icon(Icons.Default.SmartToy, null, tint = Mint, modifier = Modifier.size(42.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text(if (connected) "Начните разговор" else "Kimi Code не подключён",
                                            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            if (connected) "Отправьте задание, фото или документ"
                                            else "В настройках укажите защищённый HTTPS адрес и токен",
                                            color = Dim,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            items(history, key = { it.id }) { item ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(if (item.role == "user") 0.92f else 1f),
                                    colors = CardDefaults.cardColors(containerColor =
                                        if (item.role == "user") Color(0xFF1A403C) else SurfaceDark)
                                ) {
                                    Column(Modifier.padding(13.dp)) {
                                        Text(when (item.role) {
                                            "user" -> "Вы"; "tool" -> "Инструмент"; else -> "Kimi"
                                        }, color = Mint, style = MaterialTheme.typography.labelSmall)
                                        Spacer(Modifier.height(5.dp))
                                        Text(item.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            items(approvals, key = { it.id }) { approval ->
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF493927))) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("Требуется подтверждение", color = Amber, fontWeight = FontWeight.Bold)
                                        Text(approval.description, color = Color.White)
                                        Row {
                                            TextButton(onClick = {
                                                runAction {
                                                    withContext(Dispatchers.IO) { api().resolveApproval(session, approval.id, false) }
                                                    refresh()
                                                }
                                            }) { Text("Отклонить", color = Color.White) }
                                            Spacer(Modifier.width(12.dp))
                                            Button(onClick = {
                                                runAction {
                                                    withContext(Dispatchers.IO) { api().resolveApproval(session, approval.id, true) }
                                                    refresh()
                                                }
                                            }) { Text("Разрешить") }
                                        }
                                    }
                                }
                            }
                        }
                        if (error.isNotBlank()) Text(error,
                            Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                            color = Amber, style = MaterialTheme.typography.bodySmall)
                        if (files.isNotEmpty()) {
                            LazyColumn(Modifier.heightIn(max = 90.dp)) {
                                items(files.toList()) { uri ->
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp)) {
                                        Icon(Icons.Default.AttachFile, null, tint = Mint, modifier = Modifier.size(18.dp))
                                        Text(uri.lastPathSegment?.substringAfterLast('/') ?: "Файл",
                                            Modifier.weight(1f).padding(7.dp), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                        IconButton(onClick = { files.remove(uri) },
                                            modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Удалить") }
                                    }
                                }
                            }
                        }
                        Surface(color = SurfaceDark,
                            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                OutlinedTextField(
                                    value = prompt, onValueChange = { prompt = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Сообщение Kimi…") },
                                    minLines = 1, maxLines = 5,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { selectPhoto.launch("image/*") }) {
                                        Icon(Icons.Default.Image, "Фотографии", tint = Mint)
                                    }
                                    IconButton(onClick = { selectFile.launch(arrayOf("*/*")) }) {
                                        Icon(Icons.Default.AttachFile, "Документ", tint = Mint)
                                    }
                                    IconButton(onClick = {
                                        try {
                                            dictate.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите по-русски")
                                            })
                                        } catch (_: ActivityNotFoundException) {
                                            error = "На устройстве нет системного распознавания речи"
                                        }
                                    }) { Icon(Icons.Default.Mic, "Голосовой ввод", tint = Mint) }
                                    Spacer(Modifier.weight(1f))
                                    Button(
                                        onClick = {
                                            val text = prompt.trim()
                                            val chosen = files.toList()
                                            val target = session
                                            runAction {
                                                withContext(Dispatchers.IO) {
                                                    val client = api()
                                                    val uploaded = chosen.map { client.upload(context, it) }
                                                    client.send(target, text, uploaded)
                                                }
                                                prompt = ""
                                                files.clear()
                                                refresh()
                                            }
                                        },
                                        enabled = connected && session.isNotBlank() && !working &&
                                            (prompt.isNotBlank() || files.isNotEmpty()),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        if (working) CircularProgressIndicator(modifier = Modifier.size(19.dp),
                                            strokeWidth = 2.dp)
                                        else Icon(Icons.AutoMirrored.Filled.Send, "Отправить")
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        Column(Modifier.fillMaxSize().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(15.dp)) {
                            Text("Получить файл", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Text(
                                "Введите относительный путь к готовому файлу внутри папки текущего проекта, например reports/result.md.",
                                color = Dim, style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(downloadPath, onValueChange = { downloadPath = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Путь к файлу") },
                                placeholder = { Text("reports/result.md") }, singleLine = true)
                            Button(onClick = {
                                val target = session
                                val path = downloadPath.trim()
                                runAction {
                                    val local = withContext(Dispatchers.IO) {
                                        api().downloadWorkspaceFile(context, target, path)
                                    }
                                    downloaded = local
                                    saveDownload.launch(path.substringAfterLast('/'))
                                }
                            }, enabled = connected && session.isNotBlank() && downloadPath.isNotBlank() && !working) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Скачать на телефон")
                            }
                            if (error.isNotBlank()) Text(error, color = Amber)
                            HorizontalDivider(color = Color(0xFF293A41))
                            Text("Вложения", style = MaterialTheme.typography.titleMedium)
                            Text("${files.size} подготовлено для отправки из чата", color = Dim)
                            Text("Загрузка результатов доступна только после подключения к Kimi Code.",
                                color = Dim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        Column(Modifier.fillMaxSize().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Подключение", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Text("Используйте только защищённый HTTPS-шлюз. Не открывайте Kimi API напрямую в интернет.",
                                color = Amber, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(endpoint, onValueChange = { endpoint = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Адрес HTTPS") },
                                placeholder = { Text("https://your-private-gateway.example") },
                                singleLine = true)
                            OutlinedTextField(workdir, onValueChange = { workdir = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Рабочая папка") },
                                placeholder = { Text("/home/user/projects") }, singleLine = true)
                            OutlinedTextField(accessToken, onValueChange = { accessToken = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Токен доступа") },
                                placeholder = { Text(if (safe.token().isNotBlank()) "Сохранён в Keystore" else "Вставьте токен") },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                singleLine = true)
                            Button(onClick = {
                                runAction {
                                    safe.save(endpoint.trim(), workdir.trim(), accessToken.trim().ifBlank { null })
                                    if (safe.token().isBlank()) throw IllegalStateException("Введите токен")
                                    accessToken = ""
                                    withContext(Dispatchers.IO) { api().check() }
                                    connected = true
                                    refresh()
                                    tab = 0
                                }
                            }, enabled = !working) {
                                Icon(Icons.Default.Link, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Сохранить и проверить")
                            }
                            if (error.isNotBlank()) Text(error, color = Amber)
                            if (connected) Text("Соединение подтверждено Kimi Code", color = Mint)
                            HorizontalDivider(color = Color(0xFF293A41))
                            Text("Голосовой ввод: русский (ru-RU)", color = Mint)
                            Text("Токен зашифрован через Android Keystore. Не добавляйте его в GitHub и не передавайте посторонним.",
                                color = Dim, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                safe.clear()
                                connected = false
                                session = ""
                                sessions = emptyList()
                                history = emptyList()
                                approvals = emptyList()
                                accessToken = ""
                                error = ""
                            }) { Text("Удалить настройки подключения", color = Amber) }
                        }
                    }
                }
            }
        }
    }
}
