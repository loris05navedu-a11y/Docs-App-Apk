package com.docssuite.templates

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Les coordonnées de l'utilisateur, proposées dans chaque nouveau modèle. */
class TemplateProfile(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("docssuite_templates", Context.MODE_PRIVATE)

    fun get(key: String): String = prefs.getString("profile:$key", "").orEmpty()

    fun save(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString("profile:$k", v.trim()) } }.apply()
    }
}

private fun iconOf(id: String): ImageVector = when (id) {
    "cv" -> Icons.Filled.Badge
    "motivation" -> Icons.Filled.Mail
    "resiliation" -> Icons.Filled.Cancel
    "attestation" -> Icons.Filled.Verified
    "compte-rendu" -> Icons.Filled.Groups
    "facture" -> Icons.Filled.ReceiptLong
    "devis" -> Icons.Filled.RequestQuote
    else -> Icons.Filled.AccountBalanceWallet
}

private fun colorOf(template: Template): Color = when (template.group) {
    TemplateGroup.WORK -> Color(0xFF2563EB)
    TemplateGroup.LETTERS -> Color(0xFF7C3AED)
    TemplateGroup.MEETINGS -> Color(0xFF0D9488)
    TemplateGroup.MONEY -> Color(0xFF16A34A)
}

/**
 * Modèles de documents : on choisit, on remplit quelques champs (ou pas),
 * et le document s'ouvre dans l'éditeur ou le tableur, prêt à finir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(onBack: () -> Unit, onCreate: (Built) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.let(Templates::byId)
    BackHandler(enabled = selected != null) { selectedId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.title ?: "Modèles de documents", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) selectedId = null else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selected == null) {
                TemplateList { selectedId = it.id }
            } else {
                TemplateForm(selected, onCreate)
            }
        }
    }
}

@Composable
private fun TemplateList(onPick: (Template) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Choisis un modèle, remplis ce que tu sais : le reste est marqué [entre crochets] pour le compléter ensuite.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TemplateGroup.values().forEach { group ->
            item(key = group.name) {
                Text(group.label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            items(Templates.all.filter { it.group == group }, key = { it.id }) { template ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(template) }
                        .semantics { contentDescription = "Modèle ${template.title}" }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).background(colorOf(template), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(iconOf(template.id), contentDescription = null, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(template.title, fontWeight = FontWeight.SemiBold)
                            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (template.sheet) "Tableur" else "Document",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorOf(template)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateForm(template: Template, onCreate: (Built) -> Unit) {
    val context = LocalContext.current
    val profile = remember { TemplateProfile(context) }
    val values = remember(template.id) {
        mutableStateMapOf<String, String>().apply {
            template.fields.filter { it.profile }.forEach { put(it.key, profile.get(it.key)) }
        }
    }
    var remember by rememberSaveable { mutableStateOf(true) }
    val hasProfile = template.fields.any { it.profile }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(template.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hasProfile) {
            item { Text("Mes coordonnées", fontWeight = FontWeight.Bold) }
        }
        items(template.fields, key = { it.key }) { field ->
            val firstOther = template.fields.firstOrNull { !it.profile }
            Column {
                if (hasProfile && field == firstOther) {
                    Text(
                        if (template.sheet) "Le document" else "Le courrier",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                    )
                }
                OutlinedTextField(
                    value = values[field.key].orEmpty(),
                    onValueChange = { values[field.key] = it },
                    label = { Text(field.label) },
                    placeholder = if (field.hint.isNotEmpty() || field.default.isNotEmpty()) {
                        { Text(field.hint.ifEmpty { field.default }) }
                    } else null,
                    singleLine = !field.multiline,
                    minLines = if (field.multiline) 3 else 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (hasProfile) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { remember = !remember }) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Retenir mes coordonnées pour les prochains modèles", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Button(
                onClick = {
                    if (remember && hasProfile) profile.save(template.fields.filter { it.profile }.associate { it.key to values[it.key].orEmpty() })
                    onCreate(template.build(values.toMap()))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (template.sheet) "Créer et ouvrir dans le tableur" else "Créer et ouvrir le document")
            }
        }
    }
}
