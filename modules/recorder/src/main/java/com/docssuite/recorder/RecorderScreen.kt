package com.docssuite.recorder

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dictaphone (cours, réunions, idées) et dictée (parler au lieu de taper,
 * avec la ponctuation à la voix). [onCreateDocument] crée un document
 * avec le texte dicté.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(onBack: () -> Unit, onCreateDocument: (title: String, text: String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun report(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Dictaphone et dictée", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Dictaphone") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Dictée") })
            }
            when (tab) {
                0 -> RecorderTab(::report)
                else -> DictationTab(::report, onCreateDocument)
            }
        }
    }
}

// ------------------------------------------------------------- dictaphone

@Composable
private fun RecorderTab(report: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { RecordingStore(context) }
    val recordings = remember { mutableStateListOf<Recording>().apply { addAll(store.list()) } }
    val status by RecorderController.status.collectAsState()
    val player = remember { AudioPlayer() }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }

    fun refresh() {
        recordings.clear()
        recordings.addAll(store.list())
    }

    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(Unit) {
        RecorderController.events.collect { message ->
            refresh()
            report(message)
        }
    }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.poll()
            delay(200)
        }
        player.poll()
    }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            player.release()
            RecorderController.start(context)
        } else {
            report("Sans accès au micro, l'enregistrement est impossible")
        }
    }
    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            player.release()
            RecorderController.start(context)
        } else {
            askPermissions.launch(permissions)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { RecordingPanel(status, onStart = ::startRecording) }
        if (recordings.isEmpty()) {
            item {
                Text(
                    "Tes enregistrements apparaîtront ici. Pose des repères pendant l'enregistrement pour retrouver ensuite les passages importants.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                )
            }
        }
        items(recordings, key = { it.id }) { recording ->
            RecordingRow(
                recording = recording,
                player = player,
                onOpen = {
                    runCatching { player.open(recording.id, recording.file) }
                        .onFailure { report("Cet enregistrement ne peut pas être lu") }
                },
                onShare = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", recording.file)
                        val intent = Intent(Intent.ACTION_SEND)
                            .setType("audio/mp4")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, recording.name)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "Partager ${recording.name}"))
                    }.onFailure { report("Partage impossible") }
                },
                onRename = { renaming = recording },
                onDelete = { deleting = recording }
            )
        }
    }

    renaming?.let { recording ->
        var name by remember(recording.id) { mutableStateOf(recording.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { store.rename(recording.id, name); refresh(); renaming = null }, enabled = name.isNotBlank()) { Text("Renommer") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } }
        )
    }
    deleting?.let { recording ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${recording.name} » ?") },
            text = { Text("L'enregistrement (${formatDuration(recording.durationMs)}) sera définitivement effacé.") },
            confirmButton = {
                TextButton(onClick = {
                    if (player.currentId == recording.id) player.release()
                    store.delete(recording.id)
                    refresh()
                    deleting = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun RecordingPanel(status: RecorderStatus, onStart: () -> Unit) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (status) {
                RecorderStatus.Idle -> {
                    FilledIconButton(
                        onClick = onStart,
                        modifier = Modifier.size(84.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC2626))
                    ) { Icon(Icons.Filled.Mic, contentDescription = "Enregistrer", tint = Color.White, modifier = Modifier.size(40.dp)) }
                    Spacer(Modifier.height(10.dp))
                    Text("Appuie pour enregistrer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "L'enregistrement continue écran éteint ou dans une autre app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                is RecorderStatus.Active -> {
                    Text(
                        formatDuration(status.elapsedMs),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.semantics { contentDescription = "Durée enregistrée" }
                    )
                    Text(
                        if (status.paused) "En pause" else "Enregistrement…",
                        color = if (status.paused) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFDC2626),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = status.level,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color(0xFFDC2626)
                    )
                    if (status.markers.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("${status.markers.size} repère(s)", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { if (status.paused) RecorderController.resume(context) else RecorderController.pause(context) }) {
                            Icon(if (status.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (status.paused) "Reprendre" else "Pause")
                        }
                        OutlinedButton(onClick = { RecorderController.mark(context) }) {
                            Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Repère")
                        }
                        FilledIconButton(
                            onClick = { RecorderController.stop(context) },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC2626))
                        ) { Icon(Icons.Filled.Stop, contentDescription = "Arrêter", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    player: AudioPlayer,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val open = player.currentId == recording.id
    var menu by remember { mutableStateOf(false) }
    val date = remember(recording.createdAt) { SimpleDateFormat("d MMM yyyy, HH'h'mm", Locale.FRANCE).format(Date(recording.createdAt)) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics { contentDescription = "Enregistrement ${recording.name}" },
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Color(0x22DC2626), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xFFDC2626))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(recording.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "$date · ${formatDuration(recording.durationMs)}" + if (recording.markers.isNotEmpty()) " · ${recording.markers.size} repère(s)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Actions pour ${recording.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Partager") }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (open) PlayerControls(recording, player)
        }
    }
}

@Composable
private fun PlayerControls(recording: Recording, player: AudioPlayer) {
    Column(modifier = Modifier.padding(end = 10.dp, top = 6.dp)) {
        Slider(
            value = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f,
            onValueChange = { player.seekTo((it * player.durationMs).toLong()) }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(player.positionMs), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { player.skip(-10_000) }) { Icon(Icons.Filled.FastRewind, contentDescription = "Reculer de 10 secondes") }
            FilledIconButton(onClick = player::toggle) {
                Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (player.playing) "Pause" else "Lire")
            }
            IconButton(onClick = { player.skip(10_000) }) { Icon(Icons.Filled.FastForward, contentDescription = "Avancer de 10 secondes") }
            TextButton(onClick = player::cycleSpeed) { Text("×${if (player.speed % 1f == 0f) player.speed.toInt().toString() else player.speed.toString()}") }
            Spacer(Modifier.weight(1f))
            Text(formatDuration(player.durationMs), style = MaterialTheme.typography.labelSmall)
        }
        if (recording.markers.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(recording.markers) { index, at ->
                    AssistChip(onClick = { player.seekTo(at) }, label = { Text("Repère ${index + 1} · ${formatDuration(at)}") })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dictée

@Composable
private fun DictationTab(report: (String) -> Unit, onCreateDocument: (String, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (spoken.isNullOrBlank()) report("Rien n'a été compris, réessaie un peu plus fort")
        else text = VoiceCommands.append(text, spoken)
    }
    fun dictate() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Parle, puis marque une pause")
        try {
            recognizer.launch(intent)
        } catch (missing: ActivityNotFoundException) {
            report("La reconnaissance vocale n'est pas disponible sur ce téléphone (application Google requise)")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Texte dicté — modifiable") },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Dis « virgule », « point », « point à la ligne », « à la ligne », « nouveau paragraphe », « point d'interrogation », « deux points », « ouvrez / fermez les guillemets ».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = ::dictate,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Icon(Icons.Filled.Mic, contentDescription = "Dicter", modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = {
                        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)?.trimEnd('.', ',', ':') ?: "Dictée"
                        onCreateDocument(title, text)
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Créer un document") }
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)); report("Texte copié") }, enabled = text.isNotBlank()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copier")
                    }
                    TextButton(onClick = { text = "" }, enabled = text.isNotBlank()) { Text("Effacer") }
                }
            }
        }
    }
}
