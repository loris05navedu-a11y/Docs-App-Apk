package com.docssuite.mailmerge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docssuite.core.rememberFileOpener

private val accent = Color(0xFF9333EA)

/**
 * Publipostage : un modèle contenant des champs `{{Nom}}`, un tableur de
 * destinataires, et autant de courriers personnalisés que de lignes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailMergeScreen(onBack: () -> Unit, onCreate: (List<MergedLetter>) -> Unit) {
    val context = LocalContext.current
    val setup = remember { MergeSetup(context) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(setup.problem) {
        setup.problem?.let {
            snackbar.showSnackbar(it)
            setup.dismissProblem()
        }
    }

    BackHandler(enabled = setup.step != MergeStep.MODEL) { setup.back() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Courriers en série", fontWeight = FontWeight.Bold)
                        Text(
                            when (setup.step) {
                                MergeStep.MODEL -> "1 sur 3 · le modèle"
                                MergeStep.DATA -> "2 sur 3 · les destinataires"
                                MergeStep.READY -> "3 sur 3 · vérifier et créer"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (!setup.back()) onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (setup.step) {
                MergeStep.MODEL -> ModelStep(setup)
                MergeStep.DATA -> DataStep(setup)
                MergeStep.READY -> ReadyStep(setup, onCreate)
            }
        }
    }
}

@Composable
private fun ModelStep(setup: MergeSetup) {
    val opener = rememberFileOpener(onError = {}) { setup.chooseModelFile(it.name, it.bytes) }
    val documents = setup.savedModels

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Explain(
                "Prends un document où les parties à personnaliser sont écrites entre doubles " +
                    "accolades : « Bonjour {{Prénom}}, » . Chaque {{champ}} ira chercher la colonne " +
                    "du même nom dans ton tableau. {{date}} se remplit tout seul."
            )
        }
        item { Header("Ouvrir un fichier") }
        item {
            PickRow("Word, OpenDocument, RTF, texte…", Icons.Filled.FolderOpen) {
                opener.open(arrayOf("*/*"))
            }
        }
        if (documents.isNotEmpty()) {
            item { Header("Mes documents") }
            items(documents, key = { it.id }) { meta ->
                PickRow(meta.name, Icons.Filled.Description) { setup.chooseSavedModel(meta.id) }
            }
        }
    }
}

@Composable
private fun DataStep(setup: MergeSetup) {
    val opener = rememberFileOpener(onError = {}) { setup.chooseDataFile(it.name, it.bytes) }
    val sheets = setup.savedData

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Chosen("Modèle", setup.modelName, Icons.Filled.Description) }
        item {
            Explain(
                if (setup.fields.isEmpty()) {
                    "Ce modèle ne contient aucun champ {{…}} : tous les courriers seront identiques. " +
                        "Reviens en arrière pour en choisir un autre, ou continue si c'est voulu."
                } else {
                    "Champs trouvés : " + setup.fields.joinToString(", ") { "{{$it}}" } +
                        ". Choisis maintenant le tableau dont la première ligne porte ces noms de colonnes."
                }
            )
        }
        item { Header("Ouvrir un fichier") }
        item {
            PickRow("Excel, OpenDocument, CSV…", Icons.Filled.FolderOpen) { opener.open(arrayOf("*/*")) }
        }
        if (sheets.isNotEmpty()) {
            item { Header("Mes tableurs") }
            items(sheets, key = { it.id }) { meta ->
                PickRow(meta.name, Icons.Filled.TableChart) { setup.chooseSavedData(meta.id) }
            }
        }
    }
}

@Composable
private fun ReadyStep(setup: MergeSetup, onCreate: (List<MergedLetter>) -> Unit) {
    val plan = setup.plan
    val count = setup.recipients.records.size

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Chosen("Modèle", setup.modelName, Icons.Filled.Description) }
        item { Chosen("Destinataires", "${setup.dataName} · $count", Icons.Filled.Groups) }

        if (setup.sheets.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    setup.sheets.forEachIndexed { i, sheet ->
                        FilterChip(
                            selected = i == setup.sheetIndex,
                            onClick = { setup.chooseSheet(i) },
                            label = { Text(sheet.name) }
                        )
                    }
                }
            }
        }

        if (plan.unknown.isNotEmpty()) {
            item {
                Notice(
                    Icons.Filled.Warning,
                    Color(0xFFB45309),
                    "Sans colonne : " + plan.unknown.joinToString(", ") { "{{$it}}" } +
                        ". Ces champs resteront visibles dans les courriers, à compléter à la main."
                )
            }
        }
        if (plan.unused.isNotEmpty()) {
            item {
                Explain("Colonnes non utilisées par le modèle : " + plan.unused.joinToString(", ") + ".")
            }
        }

        if (setup.recipients.headers.isNotEmpty()) {
            item { Header("Nommer les courriers d'après") }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    setup.recipients.headers.forEach { header ->
                        FilterChip(
                            selected = setup.nameField?.let { foldKey(it) == foldKey(header) } == true,
                            onClick = { setup.nameField = header },
                            label = { Text(header) }
                        )
                    }
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { setup.single = !setup.single }
            ) {
                Checkbox(checked = setup.single, onCheckedChange = { setup.single = it })
                Text(
                    "Tout dans un seul document",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "Tout dans un seul document" }
                )
            }
        }

        item { Header("Aperçu du premier courrier") }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Text(
                    setup.preview().lines().take(24).joinToString("\n"),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            Button(
                onClick = { onCreate(setup.letters()) },
                enabled = count > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (setup.single) "Créer le document des $count courriers"
                    else "Créer les $count courriers"
                )
            }
        }
    }
}

// ------------------------------------------------------------------ morceaux

@Composable
private fun Header(text: String) {
    Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Explain(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Chosen(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PickRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .semantics { contentDescription = title },
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(accent, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, modifier = Modifier.weight(1f))
        }
    }
}
