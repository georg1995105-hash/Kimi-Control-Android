package pro.veor.kimicontrol

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Accent = Color(0xFF57D7C2)
private val Background = Color(0xFF10161C)
private val Panel = Color(0xFF1A252D)
private val Muted = Color(0xFFAAC0C5)

private data class Attachment(val uri: Uri, val name: String, val mime: String)
private data class LocalDraft(val text: String, val attachments: List<Attachment>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, onPrimary = Background, background = Background,
                surface = Panel, onSurface = Color.White
            )) { ConnectedScreen() }
        }
    }
}

@Composable
private fun KimiScreen() {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val preferences = remember(context) { context.getSharedPreferences("kimi_local_draft", 0) }
    var typed by remember { mutableStateOf(preferences.getString("message", "") ?: "") }
    LaunchedEffect(typed) { preferences.edit().putString("message", typed).apply() }
    var status by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Attachment>() }
    val drafts = remember { mutableStateListOf<LocalDraft>() }

    fun addFile(uri: Uri) {
        if (attachments.any { it.uri == uri }) return
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "Вложение"
        attachments.add(Attachment(uri, name, context.contentResolver.getType(uri) ?: "*/*"))
    }
    fun openFile(file: Attachment) {
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, file.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Открыть вложение"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Не найдено приложение для просмотра", Toast.LENGTH_SHORT).show()
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { items ->
        items.forEach(::addFile)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { items ->
        items.forEach {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            addFile(it)
        }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (recognized.isNotBlank()) typed = listOf(typed.trim(), recognized.trim())
                .filter { it.isNotEmpty() }.joinToString(" ")
            else status = "Речь не распознана. Попробуйте ещё раз."
        } else status = "Распознавание отменено или не удалось."
    }

    fun dictate() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите по-русски")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { speech.launch(intent) }
        catch (_: ActivityNotFoundException) { status = "На телефоне нет службы распознавания речи." }
    }

    Scaffold(
        containerColor = Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().background(Background)) {
                Row(
                    Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFF163C3A),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("K", color = Accent, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Kimi Control", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (tab) {
                                0 -> "Рабочий чат"
                                1 -> "Вложения и результаты"
                                else -> "Параметры приложения"
                            },
                            color = Muted, style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Surface(color = Color(0xFF342C24), shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFFC887), modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Офлайн", color = Color(0xFFFFC887), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF27363C), thickness = 1.dp)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp, modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, null) }, label = { Text("Чат") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Файлы") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Новая сессия", fontWeight = FontWeight.SemiBold)
                            Text("Задания хранятся здесь до подключения", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("${drafts.size} черновиков", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider(color = Color(0xFF25343A))
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (drafts.isEmpty()) {
                            item {
                                Column(
                                    Modifier.fillParentMaxHeight(0.83f).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Surface(color = Color(0xFF183735), shape = RoundedCornerShape(22.dp)) {
                                        Icon(
                                            Icons.Default.SmartToy, null, tint = Accent,
                                            modifier = Modifier.padding(20.dp).size(32.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(18.dp))
                                    Text("Задайте задачу Kimi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        "Напишите, продиктуйте по-русски\nили приложите фотографию",
                                        color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Surface(
                                        color = Color(0xFF2E2A22), shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.padding(horizontal = 15.dp)
                                    ) {
                                        Text(
                                            "Подключение к агенту ещё не настроено.\nСейчас задания сохраняются как черновики.",
                                            Modifier.padding(12.dp), color = Color(0xFFFFCF8F),
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                        items(drafts) { draft ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF183835)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("Сохранённый черновик", color = Accent, style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (draft.text.isNotBlank()) Text(draft.text)
                                    if (draft.attachments.isNotEmpty()) Text(
                                        draft.attachments.joinToString { it.name },
                                        color = Muted, style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(
                            status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = Color(0xFFFFC887), style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (attachments.isNotEmpty()) {
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 116.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            items(attachments.toList()) { file ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(18.dp))
                                    Text(
                                        file.name,
                                        Modifier.weight(1f).clickable { openFile(file) }.padding(8.dp),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    IconButton(onClick = { attachments.remove(file) }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.Close, "Убрать", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        color = Panel,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                            OutlinedTextField(
                                value = typed, onValueChange = { typed = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Сообщение Kimi…") },
                                minLines = 1, maxLines = 5,
                                shape = RoundedCornerShape(18.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Color(0xFF4B5B61)
                                )
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { pickImages.launch("image/*") }) {
                                    Icon(Icons.Default.Image, "Добавить фото", tint = Accent)
                                }
                                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                                    Icon(Icons.Default.AttachFile, "Добавить файл", tint = Accent)
                                }
                                IconButton(onClick = { status = ""; dictate() }) {
                                    Icon(Icons.Default.Mic, "Голосовой ввод по-русски", tint = Accent)
                                }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        drafts.add(LocalDraft(typed.trim(), attachments.toList()))
                                        typed = ""
                                        attachments.clear()
                                        status = "Сохранено локально — агенту не отправлено"
                                    },
                                    enabled = typed.isNotBlank() || attachments.isNotEmpty(),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("Сохранить") }
                            }
                        }
                    }
                }
                1 -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text("Мои файлы", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                            Text("Вложения текущего задания", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (attachments.isEmpty()) {
                            item {
                                Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.height(10.dp))
                                        Text("Пока нет вложений", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Добавьте фото или документ в чате.\nОни появятся здесь.",
                                            color = Muted,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        TextButton(onClick = { tab = 0 }) { Text("Перейти в чат") }
                                    }
                                }
                            }
                        } else {
                            items(attachments.toList()) { file ->
                                OutlinedCard(onClick = { openFile(file) }) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Description, null, tint = Accent)
                                        Spacer(Modifier.width(12.dp))
                                        Text(file.name, Modifier.weight(1f), maxLines = 2)
                                        Text("Открыть", color = Accent, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        item {
                            Text("Результаты агента", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "После подключения здесь появятся готовые отчёты, документы и архивы Kimi. Сейчас удалённые файлы недоступны.",
                                color = Muted, style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Настройки", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Подключение", fontWeight = FontWeight.SemiBold)
                            Text("CONTROL · не настроено", color = Color(0xFFFFC887))
                            Text("Удалённые команды и сессии станут доступны после настройки защищённого соединения.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Голосовой ввод", fontWeight = FontWeight.SemiBold)
                            Text("Русский · ru-RU", color = Accent)
                            Text(
                                "Используется служба распознавания Android. Текст всегда можно проверить и исправить до отправки. Качество зависит от установленного движка.",
                                color = Muted, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Безопасность", fontWeight = FontWeight.SemiBold)
                            Text(
                                "В APK не встроены пароли, ключи API или SSH. Удалённое подключение пока отключено.",
                                color = Muted, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
