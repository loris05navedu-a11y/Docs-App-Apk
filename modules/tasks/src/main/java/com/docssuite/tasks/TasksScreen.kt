package com.docssuite.tasks

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TODAY = "__today__"

/** « Aujourd'hui 18:00 », « Demain 09:00 », « lun. 28 sept. 14:00 ». */
fun describeWhen(time: Long, now: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = time }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    val hour = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(time))
    return when (dayIndex(day) - dayIndex(today)) {
        0 -> "Aujourd'hui $hour"
        1 -> "Demain $hour"
        -1 -> "Hier $hour"
        else -> SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(time)) + " " + hour
    }
}

private fun isDueToday(task: Task, now: Long): Boolean {
    val at = task.remindAt ?: return false
    val end = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
    }.timeInMillis
    return at <= end
}

/**
 * Tâches et rappels : listes à cocher, avec une date comprise dans le
 * texte (« Appeler Léa demain 18h ») et un rappel qui sonne même app fermée.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TaskStore(context) }
    var version by remember { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(TaskStore.DEFAULT_LIST) }
    var input by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Task?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var listDialog by remember { mutableStateOf<String?>(null) } // "" = nouvelle liste, sinon id à renommer
    var menu by remember { mutableStateOf(false) }

    fun changed() {
        version++
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun save(task: Task) {
        store.update(task)
        Reminders.sync(context, task)
        if (task.remindAt != null) ensureNotificationPermission()
        changed()
    }

    val now = remember(version) { System.currentTimeMillis() }
    val lists = remember(version) { store.lists() }
    if (selected != TODAY && lists.none { it.id == selected }) selected = TaskStore.DEFAULT_LIST
    val shown = remember(version, selected) {
        if (selected == TODAY) store.all().filter { !it.done && isDueToday(it, now) } else store.inList(selected)
    }
    val pending = shown.filter { !it.done }.sortedWith(compareBy<Task>({ it.remindAt == null }, { it.remindAt ?: 0 }, { it.createdAt }))
    val done = shown.filter { it.done }
    val preview = remember(input) { if (input.isBlank()) null else QuickAddParser.parse(input, System.currentTimeMillis()) }

    val alarms = context.getSystemService(AlarmManager::class.java)
    val exactAllowed = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun addFromInput() {
        val parsed = preview ?: return
        if (parsed.title.isBlank()) return
        val list = if (selected == TODAY) TaskStore.DEFAULT_LIST else selected
        val task = store.add(list, parsed.title, parsed.remindAt, parsed.repeat)
        Reminders.sync(context, task)
        if (task.remindAt != null) ensureNotificationPermission()
        input = ""
        changed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tâches", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (selected != TODAY) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options de la liste") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Renommer la liste") }, onClick = { menu = false; listDialog = selected })
                                DropdownMenuItem(
                                    text = { Text("Effacer les tâches terminées") },
                                    onClick = {
                                        menu = false
                                        store.clearCompleted(selected).forEach { Reminders.cancel(context, it.id) }
                                        changed()
                                    }
                                )
                                if (lists.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text("Supprimer la liste") },
                                        onClick = {
                                            menu = false
                                            store.deleteList(selected).forEach { Reminders.cancel(context, it.id) }
                                            selected = TaskStore.DEFAULT_LIST
                                            changed()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    preview?.remindAt?.let { at ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                            Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "« ${preview.title} » · rappel ${describeWhen(at).replaceFirstChar { it.lowercase() }}" +
                                    if (preview.repeat != Repeat.NONE) " · ${preview.repeat.label.lowercase()}" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Ex. : Appeler Léa demain 18h") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addFromInput() }),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = ::addFromInput, enabled = input.isNotBlank()) {
                            Icon(Icons.Filled.Send, contentDescription = "Ajouter la tâche")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selected == TODAY,
                        onClick = { selected = TODAY },
                        label = { Text("Aujourd'hui") },
                        leadingIcon = { Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                items(lists, key = { it.id }) { list ->
                    val count = remember(version, list.id) { store.inList(list.id).count { !it.done } }
                    FilterChip(
                        selected = selected == list.id,
                        onClick = { selected = list.id },
                        label = { Text(if (count > 0) "${list.name} ($count)" else list.name) }
                    )
                }
                item {
                    IconButton(onClick = { listDialog = "" }) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle liste") }
                }
            }
            if (!exactAllowed) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Les rappels peuvent avoir quelques minutes de retard. Autorise les alarmes exactes pour qu'ils sonnent à la minute près.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            runCatching {
                                if (Build.VERSION.SDK_INT >= 31) {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:${context.packageName}")))
                                }
                            }
                        }) { Text("Autoriser") }
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (pending.isEmpty() && done.isEmpty()) {
                    item {
                        Text(
                            if (selected == TODAY) "Rien de prévu aujourd'hui."
                            else "Aucune tâche. Écris-en une en bas : une date comme « demain 18h » ou « vendredi » pose un rappel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp)
                        )
                    }
                }
                items(pending, key = { it.id }) { task ->
                    TaskRow(task, now, showList = if (selected == TODAY) lists.firstOrNull { it.id == task.listId }?.name else null,
                        onToggle = { store.toggle(task.id)?.let { Reminders.sync(context, it) }; changed() },
                        onOpen = { editing = task })
                }
                if (done.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (showDone) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            Text("Terminées (${done.size})", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    if (showDone) {
                        items(done, key = { it.id }) { task ->
                            TaskRow(task, now, null, onToggle = { store.toggle(task.id)?.let { Reminders.sync(context, it) }; changed() }, onOpen = { editing = task })
                        }
                    }
                }
            }
        }
    }

    editing?.let { task ->
        TaskDialog(
            task = task,
            lists = lists,
            onDismiss = { editing = null },
            onSave = { save(it); editing = null },
            onDelete = {
                store.delete(task.id)
                Reminders.cancel(context, task.id)
                editing = null
                changed()
            }
        )
    }
    listDialog?.let { id ->
        var name by remember(id) { mutableStateOf(lists.firstOrNull { it.id == id }?.name ?: "") }
        AlertDialog(
            onDismissRequest = { listDialog = null },
            title = { Text(if (id.isEmpty()) "Nouvelle liste" else "Renommer la liste") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Courses, Devoirs, Travail…") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (id.isEmpty()) selected = store.createList(name).id else store.renameList(id, name)
                    listDialog = null
                    changed()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { listDialog = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun TaskRow(task: Task, now: Long, showList: String?, onToggle: () -> Unit, onOpen: () -> Unit) {
    val overdue = !task.done && task.remindAt != null && task.remindAt < now
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics { contentDescription = "Tâche ${task.title}" },
        elevation = CardDefaults.cardElevation(defaultElevation = if (task.done) 0.dp else 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.done, onCheckedChange = { onToggle() }, modifier = Modifier.semantics { contentDescription = "Cocher ${task.title}" })
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                val details = listOfNotNull(
                    task.remindAt?.let { (if (overdue) "En retard · " else "") + describeWhen(it, now) },
                    task.repeat.takeIf { it != Repeat.NONE }?.label,
                    showList,
                    task.notes.takeIf { it.isNotBlank() }?.lineSequence()?.first()
                )
                if (details.isNotEmpty() && !task.done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (task.repeat != Repeat.NONE) {
                            Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (overdue) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDialog(task: Task, lists: List<TaskList>, onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(task.title) }
    var notes by remember { mutableStateOf(task.notes) }
    var remindAt by remember { mutableStateOf(task.remindAt) }
    var repeat by remember { mutableStateOf(task.repeat) }
    var listId by remember { mutableStateOf(task.listId) }
    var repeatMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }

    fun pickDateTime() {
        val start = Calendar.getInstance().apply {
            timeInMillis = remindAt ?: (System.currentTimeMillis() + 3_600_000)
            if (remindAt == null) { set(Calendar.MINUTE, 0) }
        }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                remindAt = Calendar.getInstance().apply { clear(); set(y, m, d, h, min) }.timeInMillis
            }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), true).show()
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier la tâche") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Tâche") })
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Notifications, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::pickDateTime) { Text(remindAt?.let { describeWhen(it) } ?: "Ajouter un rappel") }
                    if (remindAt != null) TextButton(onClick = { remindAt = null; repeat = Repeat.NONE }) { Text("Retirer") }
                }
                if (remindAt != null) {
                    Box {
                        OutlinedButton(onClick = { repeatMenu = true }) {
                            Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Répéter : ${repeat.label.lowercase()}")
                        }
                        DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                            Repeat.values().forEach { r -> DropdownMenuItem(text = { Text(r.label) }, onClick = { repeat = r; repeatMenu = false }) }
                        }
                    }
                }
                if (lists.size > 1) {
                    Box {
                        OutlinedButton(onClick = { listMenu = true }) { Text("Liste : ${lists.firstOrNull { it.id == listId }?.name ?: ""}") }
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            lists.forEach { l -> DropdownMenuItem(text = { Text(l.name) }, onClick = { listId = l.id; listMenu = false }) }
                        }
                    }
                }
                TextButton(onClick = onDelete) { Text("Supprimer la tâche", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val day = remindAt?.takeIf { repeat == Repeat.MONTHLY }?.let { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_MONTH) }
                // Un rappel remis dans le futur rouvre une tâche terminée.
                val reopen = task.done && remindAt != null && remindAt != task.remindAt && remindAt!! > System.currentTimeMillis()
                onSave(task.copy(title = title.trim(), notes = notes.trim(), remindAt = remindAt, repeat = if (remindAt == null) Repeat.NONE else repeat,
                    listId = listId, repeatDay = day, done = if (reopen) false else task.done))
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
