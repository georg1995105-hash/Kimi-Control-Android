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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
            )) { KimiScreen() }
        }
    }
}

@Composable
private fun KimiScreen() {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
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

    Scaffold(containerColor = Background,
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SmartToy, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Kimi Control", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text("Русскоязычный клиент Android", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Не подключено", color = Color(0xFFFFC887), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider(color = Color(0xFF304049))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.AutoMirrored.Filled.Send, null) }, label = { Text("Чат") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Файлы") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Подготовка к Kimi Code", color = Accent, fontWeight = FontWeight.Bold)
                                    Text("Пока доступны локальные черновики, фотографии, файлы и русская диктовка. Подключение к CONTROL ещё не настроено.", color = Muted)
                                }
                            }
                        }
                        items(drafts) { item ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF173E3C))) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("Черновик • не отправлен", color = Accent, style = MaterialTheme.typography.labelSmall)
                                    if (item.text.isNotBlank()) Text(item.text)
                                    if (item.attachments.isNotEmpty()) Text(item.attachments.joinToString { it.name },
                                        color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(status, Modifier.padding(horizontal = 16.dp), color = Color(0xFFFFC887))
                    }
                    if (attachments.isNotEmpty()) {
                        Column(Modifier.heightIn(max = 140.dp)) {
                            attachments.toList().forEach { file ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Description, null, tint = Accent)
                                    Text(file.name, Modifier.weight(1f).clickable { openFile(file) }.padding(8.dp),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    IconButton(onClick = { attachments.remove(file) }) { Icon(Icons.Default.Close, "Убрать") }
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = typed, onValueChange = { typed = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        placeholder = { Text("Напиши или продиктуй задание…") },
                        minLines = 2, maxLines = 5, shape = RoundedCornerShape(18.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { pickImages.launch("image/*") }) { Icon(Icons.Default.Image, "Фото", tint = Accent) }
                        IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, "Файл", tint = Accent) }
                        IconButton(onClick = { status = ""; dictate() }) { Icon(Icons.Default.Mic, "Русская диктовка", tint = Accent) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            drafts.add(LocalDraft(typed.trim(), attachments.toList()))
                            typed = ""
                            attachments.clear()
                        }, enabled = typed.isNotBlank() || attachments.isNotEmpty()) { Text("Сохранить") }
                    }
                }
                1 -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Файлы", style = MaterialTheme.typography.headlineSmall)
                    Text("Скачивание результатов Kimi будет доступно после подключения к серверу.", color = Muted)
                    attachments.forEach { file ->
                        OutlinedCard(onClick = { openFile(file) }) {
                            Text(file.name, Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                }
                else -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Настройки", style = MaterialTheme.typography.headlineSmall)
                    Text("Диктовка: русский язык (ru-RU).", color = Accent)
                    Text("Текст после распознавания можно редактировать. Автоматическая отправка отключена.")
                    Text("CONTROL: не подключён", color = Color(0xFFFFC887))
                    Text("В приложении нет встроенных API-ключей, паролей или SSH-доступа.", color = Muted)
                }
            }
        }
    }
}
