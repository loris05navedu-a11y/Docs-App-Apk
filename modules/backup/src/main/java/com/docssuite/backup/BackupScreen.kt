package com.docssuite.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docssuite.core.shareBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sauvegarder tous les documents dans un seul fichier (à garder sur Drive,
 * une clé USB, un ordinateur), et tout retrouver sur un nouveau téléphone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { BackupService(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var refresh by remember { mutableStateOf(0) }
    val documentCount = remember(refresh) { service.currentDocuments().size }
    val folderCount = remember(refresh) { service.folderCount }
    val lastBackup = remember(refresh) { service.lastBackupAt() }
    var busy by remember { mutableStateOf<String?>(null) }
    var pendingArchive by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<Pair<BackupContents, RestorePlan>?>(null) }
    var keepCopies by remember { mutableStateOf(true) }

    fun report(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val fileName = remember { "DocsApp-sauvegarde-" + SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date()) + ".zip" }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingArchive
        pendingArchive = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Emplacement inaccessible") }
            }
            result
                .onSuccess { service.markSaved(); refresh++; report("Sauvegarde enregistrée") }
                .onFailure { report(it.message ?: "Enregistrement impossible") }
        }
    }

    fun withArchive(action: (ByteArray) -> Unit) {
        scope.launch {
            busy = "Préparation de la sauvegarde…"
            val result = withContext(Dispatchers.IO) { runCatching { service.createBackup() } }
            busy = null
            result.onSuccess(action).onFailure { report(it.message ?: "Sauvegarde impossible") }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Vérification de la sauvegarde…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Fichier illisible")
                    val contents = BackupArchive.read(bytes)
                    contents to service.plan(contents)
                }
            }
            busy = null
            result.onSuccess { preview = it }.onFailure { report(it.message ?: "Fichier illisible") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sauvegarde", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Section(Icons.Filled.Backup, "Sauvegarder", Color(0xFF2563EB)) {
                    Text(
                        "$documentCount document(s)" + (if (folderCount > 0) " et $folderCount dossier(s)" else "") +
                            " dans un seul fichier, à garder hors du téléphone : Google Drive, clé USB, ordinateur, e-mail.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    LastBackupLine(lastBackup)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            withArchive { bytes ->
                                pendingArchive = bytes
                                runCatching { saveLauncher.launch(fileName) }.onFailure { report("Aucune application de fichiers n'est disponible") }
                            }
                        },
                        enabled = documentCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enregistrer la sauvegarde") }
                    OutlinedButton(
                        onClick = {
                            withArchive { bytes ->
                                shareBytes(context, fileName, "application/zip", bytes) { report(it) }
                                // Partagée n'est pas forcément enregistrée : on ne change pas la date.
                            }
                        },
                        enabled = documentCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Envoyer vers Drive, e-mail…") }
                }

                Section(Icons.Filled.Restore, "Restaurer", Color(0xFF16A34A)) {
                    Text(
                        "Retrouve tes documents depuis une sauvegarde, par exemple sur un nouveau téléphone. Rien n'est supprimé : les documents déjà présents sont gardés, seule la version la plus récente de chacun compte.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching { openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
                                .onFailure { report("Aucune application de fichiers n'est disponible") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choisir une sauvegarde") }

                    preview?.let { (contents, plan) ->
                        Spacer(Modifier.height(12.dp))
                        RestorePreview(contents, plan, keepCopies, onKeepCopies = { keepCopies = it })
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { preview = null }) { Text("Annuler") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = "Restauration…"
                                        val result = withContext(Dispatchers.IO) { runCatching { service.restore(contents, plan, keepCopies) } }
                                        busy = null
                                        preview = null
                                        refresh++
                                        result
                                            .onSuccess { r ->
                                                report(
                                                    "Restauration terminée : ${r.added} ajouté(s), ${r.updated} mis à jour" +
                                                        (if (r.copies > 0) ", ${r.copies} copie(s)" else "")
                                                )
                                            }
                                            .onFailure { report(it.message ?: "Restauration impossible") }
                                    }
                                },
                                enabled = plan.added.isNotEmpty() || plan.updated.isNotEmpty() || (keepCopies && plan.conflicts.isNotEmpty()) || contents.library != null
                            ) { Text("Restaurer") }
                        }
                    }
                }

                Text(
                    "La sauvegarde contient les documents, tableurs et présentations de l'app, leurs dossiers et favoris. Les scans en cours, les PDF et les enregistrements audio n'en font pas partie : ce sont déjà des fichiers de ton téléphone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            busy?.let { message ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, color: Color, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun LastBackupLine(lastBackup: Long?) {
    val days = lastBackup?.let { TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - it) }
    val text = when {
        lastBackup == null -> "Aucune sauvegarde enregistrée depuis cet appareil"
        days == 0L -> "Dernière sauvegarde : aujourd'hui"
        days == 1L -> "Dernière sauvegarde : hier"
        else -> "Dernière sauvegarde : il y a $days jours"
    }
    val warn = lastBackup == null || (days ?: 0) > 30
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (warn) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (warn) Color(0xFFB45309) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RestorePreview(contents: BackupContents, plan: RestorePlan, keepCopies: Boolean, onKeepCopies: (Boolean) -> Unit) {
    val date = SimpleDateFormat("d MMMM yyyy 'à' HH'h'mm", Locale.FRANCE).format(Date(contents.createdAt))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Sauvegarde du $date", fontWeight = FontWeight.SemiBold)
            Text("${contents.documents.size} document(s) dedans", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            PlanLine(plan.added.size, "nouveau(x), ajouté(s)")
            PlanLine(plan.updated.size, "plus récent(s) dans la sauvegarde, mis à jour")
            PlanLine(plan.unchanged.size, "déjà à jour")
            if (plan.conflicts.isNotEmpty()) {
                PlanLine(plan.conflicts.size, "modifié(s) depuis sur ce téléphone : ta version est gardée")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepCopies, onCheckedChange = onKeepCopies)
                    Text("Ajouter aussi leur ancienne version en copie", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PlanLine(count: Int, label: String) {
    if (count == 0) return
    Text("• $count $label", style = MaterialTheme.typography.bodyMedium)
}
