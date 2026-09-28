package com.docssuite.pdftools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.core.rememberFileOpener
import com.docssuite.core.rememberFileSaver
import com.docssuite.core.shareBytes
import com.docssuite.pdftools.CompressionLevel
import com.docssuite.pdftools.PageNumberFormat
import com.docssuite.pdftools.PageNumberPosition
import com.docssuite.pdftools.PageNumbering
import com.docssuite.pdftools.PdfCompress
import com.docssuite.pdftools.PdfFile
import com.docssuite.pdftools.PdfLock
import com.docssuite.pdftools.PdfStamp
import com.docssuite.pdftools.PdfWrongPasswordException
import com.docssuite.pdftools.Watermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Les trois outils qui transforment un PDF entier, sans toucher à ses pages. */
enum class PdfAction(val title: String, val intro: String) {
    PROTECT(
        "Protéger un PDF",
        "Mettre un mot de passe pour l'ouvrir, ou retirer celui d'un PDF dont tu connais le mot de passe."
    ),
    STAMP(
        "Filigrane et numéros",
        "Écrire « CONFIDENTIEL » en travers des pages, numéroter les pages, ou les deux."
    ),
    COMPRESS(
        "Compresser un PDF",
        "Alléger un PDF plein de photos ou de pages scannées, pour l'envoyer par e-mail."
    )
}

private val accent = Color(0xFFDC2626)
private val ok = Color(0xFF16A34A)

/** Le document chargé et ce qu'on sait de lui. */
private class Loaded(val name: String, val bytes: ByteArray, val status: PdfLock.Status, val pages: Int?)

/** Le PDF produit, prêt à enregistrer, partager ou ouvrir. */
private class Output(val name: String, val bytes: ByteArray, val detail: String)

/**
 * [initialFile] ouvre directement un PDF (depuis le lecteur, par exemple).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfActionScreen(
    action: PdfAction,
    onBack: () -> Unit,
    onOpenPdf: ((name: String, bytes: ByteArray) -> Unit)? = null,
    initialFile: Pair<String, ByteArray>? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    /** Le mot de passe vérifié du PDF chargé, s'il en fallait un. */
    var password by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<Output?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun load(name: String, bytes: ByteArray) {
        scope.launch {
            busy = "Lecture…"
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val status = PdfLock.status(bytes)
                    val pages = if (status == PdfLock.Status.NeedsPassword) null else PdfFile.parse(bytes).pages.size
                    Loaded(name, bytes, status, pages)
                }
            }
            busy = null
            result.onSuccess {
                loaded = it
                password = null
                output = null
            }.onFailure { say(it.message ?: "Ce PDF n'a pas pu être lu") }
        }
    }

    fun run(label: String, work: () -> Output) {
        scope.launch {
            busy = label
            val result = withContext(Dispatchers.Default) { runCatching(work) }
            busy = null
            result.onSuccess { output = it }.onFailure { say(it.message ?: "L'opération a échoué") }
        }
    }

    val opener = rememberFileOpener(onError = ::say) { load(it.name, it.bytes) }
    val saver = rememberFileSaver(onError = ::say, onSaved = { say("« $it » enregistré") }) { output?.bytes ?: ByteArray(0) }

    LaunchedEffect(initialFile) { initialFile?.let { load(it.first, it.second) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(action.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(action.intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                val file = loaded
                if (file == null) {
                    Button(onClick = { opener.open(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choisir un PDF")
                    }
                } else {
                    FileCard(file, unlocked = password != null) { opener.open(arrayOf("application/pdf")) }

                    val needsPassword = file.status == PdfLock.Status.NeedsPassword && password == null
                    if (needsPassword) {
                        PasswordGate(file.bytes) { password = it }
                    } else {
                        val base = file.name.substringBeforeLast('.').ifBlank { "Document" }
                        when (action) {
                            PdfAction.PROTECT -> ProtectOptions(file, password) { label, work -> run(label) { work().let { Output(it.first.replace("{}", base), it.second, it.third) } } }
                            PdfAction.STAMP -> StampOptions { watermark, numbering ->
                                run("Tamponnage…") {
                                    val bytes = PdfStamp.apply(file.bytes, watermark, numbering, password)
                                    val parts = listOfNotNull(watermark?.let { "filigrane « ${it.text} »" }, numbering?.let { "pages numérotées" })
                                    Output("$base (tamponné).pdf", bytes, parts.joinToString(" et ").replaceFirstChar { it.uppercase() })
                                }
                            }
                            PdfAction.COMPRESS -> CompressOptions { level ->
                                run("Compression…") {
                                    val result = PdfCompress.compress(file.bytes, level, password)
                                    val detail = if (result.after >= result.before) {
                                        "Déjà aussi léger que possible : rien à gagner sur ce PDF."
                                    } else {
                                        "${size(result.before)} → ${size(result.after)} (−${(result.saved * 100).toInt()} %)" +
                                            if (result.imagesReduced > 0) " · ${result.imagesReduced} image${if (result.imagesReduced > 1) "s" else ""} allégée${if (result.imagesReduced > 1) "s" else ""}" else ""
                                    }
                                    Output("$base (compressé).pdf", result.bytes, detail)
                                }
                            }
                        }
                    }
                }

                output?.let { out ->
                    ResultCard(
                        out,
                        canOpen = onOpenPdf != null,
                        onSave = { saver.save(out.name, "application/pdf") },
                        onShare = { shareBytes(context, out.name, "application/pdf", out.bytes, ::say) },
                        onOpen = { onOpenPdf?.invoke(out.name, out.bytes) }
                    )
                }
            }
            busy?.let { label ->
                Surface(color = Color.Black.copy(alpha = 0.35f), modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Spacer(Modifier.width(14.dp))
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================== morceaux

@Composable
private fun FileCard(file: Loaded, unlocked: Boolean, onChange: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (val s = file.status) {
                    PdfLock.Status.Open -> "Sans protection"
                    is PdfLock.Status.Restricted -> "Restreint (${s.description}) : copie ou impression limitées"
                    PdfLock.Status.NeedsPassword -> if (unlocked) "Protégé · mot de passe accepté" else "Protégé par mot de passe"
                }
                Text(
                    listOfNotNull(size(file.bytes.size), file.pages?.let { if (it == 1) "1 page" else "$it pages" }, status).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "État : $status" }
                )
            }
            OutlinedButton(onClick = onChange) { Text("Changer") }
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (visible) "Masquer" else "Afficher")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Un PDF protégé : on vérifie le mot de passe avant de proposer quoi que ce soit. */
@Composable
private fun PasswordGate(bytes: ByteArray, onAccepted: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = accent)
            Spacer(Modifier.width(8.dp))
            Text("Ce PDF demande un mot de passe pour s'ouvrir.", fontWeight = FontWeight.SemiBold)
        }
        PasswordField(value, { value = it; wrong = false }, "Mot de passe du PDF")
        if (wrong) Text("Mot de passe incorrect.", color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                val attempt = value
                scope.launch {
                    val accepted = withContext(Dispatchers.Default) {
                        try {
                            PdfFile.parse(bytes, attempt); true
                        } catch (_: PdfWrongPasswordException) {
                            false
                        }
                    }
                    if (accepted) onAccepted(attempt) else wrong = true
                }
            },
            enabled = value.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Déverrouiller")
        }
    }
}

private enum class LockChoice(val label: String) { REMOVE("Retirer le mot de passe"), CHANGE("Changer le mot de passe"), LIFT("Retirer les restrictions"), ADD("Mettre un mot de passe") }

/**
 * [run] reçoit le libellé d'attente et le travail : (nom avec « {} » pour le
 * nom d'origine, octets, détail).
 */
@Composable
private fun ProtectOptions(file: Loaded, password: String?, run: (String, () -> Triple<String, ByteArray, String>) -> Unit) {
    val choices = when {
        password != null -> listOf(LockChoice.REMOVE, LockChoice.CHANGE)
        file.status is PdfLock.Status.Restricted -> listOf(LockChoice.LIFT, LockChoice.ADD)
        else -> listOf(LockChoice.ADD)
    }
    var choice by remember(file, password) { mutableStateOf(choices.first()) }
    var first by remember(file) { mutableStateOf("") }
    var second by remember(file) { mutableStateOf("") }
    var restrict by remember(file) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (choices.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { c -> FilterChip(selected = choice == c, onClick = { choice = c }, label = { Text(c.label) }) }
            }
        }
        when (choice) {
            LockChoice.REMOVE, LockChoice.LIFT -> {
                Text(
                    if (choice == LockChoice.REMOVE) "Le PDF produit s'ouvrira sans mot de passe, partout. Le fichier d'origine ne change pas."
                    else "Le PDF produit pourra être copié, imprimé et modifié librement.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        run("Déverrouillage…") {
                            Triple("{} (déverrouillé).pdf", PdfLock.unlock(file.bytes, password), "Plus aucun mot de passe ni restriction")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (choice == LockChoice.REMOVE) "Produire le PDF sans mot de passe" else "Produire le PDF sans restriction") }
            }
            LockChoice.ADD, LockChoice.CHANGE -> {
                PasswordField(first, { first = it }, if (choice == LockChoice.CHANGE) "Nouveau mot de passe" else "Mot de passe")
                PasswordField(second, { second = it }, "Le même, pour confirmer")
                val mismatch = second.isNotEmpty() && first != second
                if (mismatch) Text("Les deux mots de passe ne sont pas identiques.", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { restrict = !restrict }) {
                    Checkbox(checked = restrict, onCheckedChange = { restrict = it })
                    Text("Interdire aussi la copie du texte et la modification", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Garde ce mot de passe : sans lui, le PDF ne s'ouvre plus, et personne ne peut le retrouver. Chiffrement AES 256 bits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val chosen = first
                        run("Chiffrement…") {
                            Triple(
                                "{} (protégé).pdf",
                                PdfLock.protect(file.bytes, chosen, restrict, currentPassword = password),
                                "Protégé par mot de passe (AES 256 bits)" + if (restrict) ", copie interdite" else ""
                            )
                        }
                    },
                    enabled = first.isNotEmpty() && first == second,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Protéger le PDF")
                }
            }
        }
    }
}

private val presets = listOf("CONFIDENTIEL", "COPIE", "BROUILLON", "NE PAS DIFFUSER", "ANNULÉ")

private enum class StampColor(val label: String, val value: Int) {
    RED("Rouge", 0xFFDC2626.toInt()), GRAY("Gris", 0xFF64748B.toInt()), BLUE("Bleu", 0xFF2563EB.toInt())
}

@Composable
private fun StampOptions(apply: (Watermark?, PageNumbering?) -> Unit) {
    var withWatermark by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(presets.first()) }
    var color by remember { mutableStateOf(StampColor.RED) }
    var strong by remember { mutableStateOf(false) }
    var withNumbers by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf(PageNumberFormat.PAGE_OF) }
    var position by remember { mutableStateOf(PageNumberPosition.BOTTOM_CENTER) }
    var skipFirst by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SwitchRow("Filigrane", withWatermark) { withWatermark = it }
        if (withWatermark) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p -> FilterChip(selected = text == p, onClick = { text = p }, label = { Text(p) }) }
            }
            OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, label = { Text("Texte du filigrane") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StampColor.values().forEach { c ->
                    FilterChip(
                        selected = color == c,
                        onClick = { color = c },
                        label = { Text(c.label) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(Color(c.value), CircleShape)) }
                    )
                }
                FilterChip(selected = strong, onClick = { strong = !strong }, label = { Text("Plus marqué") })
            }
        }
        SwitchRow("Numéros de page", withNumbers) { withNumbers = it }
        if (withNumbers) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageNumberFormat.values().forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.sample) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageNumberPosition.values().forEach { p -> FilterChip(selected = position == p, onClick = { position = p }, label = { Text(p.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { skipFirst = !skipFirst }) {
                Checkbox(checked = skipFirst, onCheckedChange = { skipFirst = it })
                Text("Pas de numéro sur la première page (couverture)", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = {
                apply(
                    if (withWatermark && text.isNotBlank()) Watermark(text.trim(), if (strong) 0.32f else 0.18f, color.value) else null,
                    if (withNumbers) PageNumbering(format, position, skipFirst) else null
                )
            },
            enabled = (withWatermark && text.isNotBlank()) || withNumbers,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Appliquer au PDF") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun CompressOptions(compress: (CompressionLevel) -> Unit) {
    var level by remember { mutableStateOf(CompressionLevel.BALANCED) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompressionLevel.values().forEach { l ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (level == l) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable { level = l }.semantics { contentDescription = "Niveau ${l.label}" }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = level == l, onClick = { level = l })
                    Column {
                        Text(l.label, fontWeight = FontWeight.SemiBold)
                        Text(l.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(
            "Le texte, les signets et les liens restent intacts ; seules les images sont allégées.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = { compress(level) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Compresser") }
    }
}

@Composable
private fun ResultCard(out: Output, canOpen: Boolean, onSave: () -> Unit, onShare: () -> Unit, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = ok.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ok)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(out.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(out.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = "Résultat : ${out.detail}" })
                }
            }
            FilledTonalButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Partager") }
                if (canOpen) OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Ouvrir") }
            }
        }
    }
}

/** « 2,4 Mo », « 380 Ko ». */
internal fun size(bytes: Int): String = when {
    bytes >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} Ko"
    else -> "$bytes octets"
}
