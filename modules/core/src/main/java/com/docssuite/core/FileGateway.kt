package com.docssuite.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.docssuite.fileformats.FileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Accès aux fichiers de l'appareil via le Storage Access Framework : pas de
 * permission à demander, l'utilisateur choisit lui-même le fichier ou
 * l'emplacement dans le sélecteur du système.
 */

/** Fichier choisi par l'utilisateur, déjà chargé en mémoire. */
data class PickedFile(val name: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Au-delà, on refuse plutôt que de faire tomber l'app sur un OutOfMemory. */
private const val MAX_IMPORT_BYTES = 48L * 1024 * 1024

/**
 * Le type MIME n'est connu qu'au moment de l'enregistrement, alors que
 * `CreateDocument` le fige à la construction : d'où ce contrat maison.
 */
private class CreateNamedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

class FileOpener internal constructor(private val launch: (Array<String>) -> Unit) {
    /** Ouvre le sélecteur, limité aux types donnés ; par défaut, tous. */
    fun open(mimeTypes: Array<String> = arrayOf("*/*")) = launch(mimeTypes)
}

class FileSaver internal constructor(private val launch: (String, String) -> Unit) {
    fun save(fileName: String, mime: String) = launch(fileName, mime)
}

/**
 * Prépare un sélecteur de fichier. [onPicked] reçoit le contenu déjà lu, sur le
 * fil principal ; [onError] reçoit un message prêt à afficher.
 */
@Composable
fun rememberFileOpener(
    onError: (String) -> Unit,
    onPicked: (PickedFile) -> Unit
): FileOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readUri(context, uri) } }
            result
                .onSuccess(onPicked)
                .onFailure { onError(it.message ?: "Lecture du fichier impossible") }
        }
    }
    return remember(launcher) {
        FileOpener { types ->
            runCatching { launcher.launch(types) }
                .onFailure { onError("Aucune application de fichiers n'est disponible") }
        }
    }
}

/**
 * Prépare un enregistreur. [content] est appelé hors du fil principal au moment
 * où l'utilisateur a validé l'emplacement, pour ne produire le fichier que
 * s'il est réellement enregistré.
 */
@Composable
fun rememberFileSaver(
    onError: (String) -> Unit,
    onSaved: (String) -> Unit,
    content: () -> ByteArray
): FileSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingName = remember { arrayOf("document") }
    val launcher = rememberLauncherForActivityResult(CreateNamedDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = content()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Emplacement inaccessible")
                }
            }
            result
                .onSuccess { onSaved(pendingName[0]) }
                .onFailure { onError(it.message ?: "Enregistrement impossible") }
        }
    }
    return remember(launcher) {
        FileSaver { fileName, mime ->
            pendingName[0] = fileName
            runCatching { launcher.launch(fileName to mime) }
                .onFailure { onError("Aucune application de fichiers n'est disponible") }
        }
    }
}

private fun readUri(context: Context, uri: Uri): PickedFile {
    val resolver = context.contentResolver
    val name = displayName(context, uri) ?: "fichier"
    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
    } ?: -1L
    if (size > MAX_IMPORT_BYTES) {
        throw IllegalStateException("Fichier trop volumineux (${size / (1024 * 1024)} Mo)")
    }

    val bytes = resolver.openInputStream(uri)?.use { input ->
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > MAX_IMPORT_BYTES) throw IllegalStateException("Fichier trop volumineux")
            buffer.write(chunk, 0, read)
        }
        buffer.toByteArray()
    } ?: throw IllegalStateException("Fichier illisible")

    return PickedFile(name, bytes)
}

fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
        ?: uri.lastPathSegment?.substringAfterLast('/')

/** Nom de fichier sûr : on retire ce que les systèmes de fichiers refusent. */
fun safeFileName(name: String, format: FileFormat): String {
    val base = name.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
        .trim('.', ' ')
        .take(80)
        .ifBlank { "document" }
    return "$base.${format.extension}"
}

/**
 * Partage un fichier produit par l'app. Il passe par le cache et un
 * `FileProvider`, seule façon d'exposer un fichier à une autre application sans
 * lui donner accès au stockage.
 */
fun shareBytes(
    context: Context,
    fileName: String,
    mime: String,
    bytes: ByteArray,
    onError: (String) -> Unit
) {
    runCatching {
        val directory = File(context.cacheDir, "partage").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, fileName)
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, fileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Partager $fileName"))
    }.onFailure {
        onError(
            if (it is ActivityNotFoundException) "Aucune application de partage disponible"
            else it.message ?: "Partage impossible"
        )
    }
}
