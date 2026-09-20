package com.docssuite.converter.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.docssuite.converter.engine.ConversionException
import com.docssuite.converter.model.SourceFile
import com.docssuite.converter.model.kindForExtension
import java.io.File

/**
 * Gestion des fichiers du convertisseur : copie de la source dans un dossier de
 * travail, rangement des résultats, ouverture et partage.
 *
 * Les fichiers ne quittent jamais l'appareil. Ils sont écrits dans le stockage
 * privé de l'application, et exposés aux autres applications uniquement à la
 * demande, par un `FileProvider` qui ne donne accès qu'au fichier concerné.
 */
object OutputStore {

    /** Marge gardée libre pour ne pas saturer le stockage de l'appareil. */
    private const val FREE_SPACE_MARGIN = 32L * 1024 * 1024

    fun outputDirectory(context: Context): File =
        File(context.filesDir, "conversions").apply { mkdirs() }

    private fun sourceDirectory(context: Context): File =
        File(context.cacheDir, "conversion-sources").apply { mkdirs() }

    /**
     * Recopie le fichier choisi dans le cache. Les décodeurs d'Android
     * réclament un descripteur de fichier ouvrable en accès direct, ce qu'une
     * `content://` ne garantit pas ; la copie est donc nécessaire, et elle est
     * faite en flux pour ne pas charger une vidéo entière en mémoire.
     */
    fun cacheSource(context: Context, uri: Uri): SourceFile {
        val resolver = context.contentResolver
        var name = "fichier"
        var declaredSize = -1L
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0)
                if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
            }
        }
        if (name == "fichier") {
            uri.lastPathSegment?.substringAfterLast('/')?.let { if (it.isNotBlank()) name = it }
        }

        val available = sourceDirectory(context).usableSpace
        if (declaredSize > 0 && declaredSize + FREE_SPACE_MARGIN > available) {
            throw ConversionException(
                "Espace insuffisant sur l'appareil pour traiter ce fichier"
            )
        }

        val extension = name.substringAfterLast('.', "").lowercase()
        val destination = File(sourceDirectory(context), "source-${System.nanoTime()}.${extension.ifBlank { "bin" }}")
        val copied = resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: throw ConversionException("Ce fichier n'a pas pu être ouvert")

        if (copied == 0L) throw ConversionException("Ce fichier est vide")

        return SourceFile(
            uri = uri,
            displayName = name,
            extension = extension,
            sizeBytes = destination.length(),
            kind = kindForExtension(extension),
            cached = destination,
            location = readableLocation(uri)
        )
    }

    /** Nom libre dans le dossier de sortie, pour ne jamais écraser un résultat précédent. */
    fun uniqueOutput(context: Context, baseName: String, extension: String): File {
        val directory = outputDirectory(context)
        val safeBase = baseName
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
            .trim('.', ' ')
            .take(60)
            .ifBlank { "fichier" }
        var candidate = File(directory, "$safeBase.$extension")
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$safeBase ($index).$extension")
            index++
        }
        return candidate
    }

    fun ensureSpaceFor(context: Context, estimatedBytes: Long) {
        val available = outputDirectory(context).usableSpace
        if (estimatedBytes + FREE_SPACE_MARGIN > available) {
            throw ConversionException("Espace insuffisant sur l'appareil pour enregistrer le résultat")
        }
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun open(context: Context, file: File, mime: String, onError: (String) -> Unit) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(context, file), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }.onFailure {
            onError(
                if (it is ActivityNotFoundException) "Aucune application ne peut ouvrir ce format"
                else "Ce fichier n'a pas pu être ouvert"
            )
        }
    }

    fun share(context: Context, file: File, mime: String, onError: (String) -> Unit) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Partager ${file.name}"))
        }.onFailure {
            onError(
                if (it is ActivityNotFoundException) "Aucune application de partage disponible"
                else "Le partage a échoué"
            )
        }
    }

    /** Écrit le résultat à l'emplacement choisi par l'utilisateur dans le sélecteur système. */
    fun copyTo(context: Context, file: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { output ->
            file.inputStream().buffered().use { it.copyTo(output) }
        } ?: throw ConversionException("Cet emplacement n'est pas accessible en écriture")
    }

    fun deleteOutputs(context: Context) {
        outputDirectory(context).listFiles()?.forEach { it.delete() }
    }

    fun clearSourceCache(context: Context) {
        sourceDirectory(context).listFiles()?.forEach { it.delete() }
    }

    /** Dossier d'origine, affiché tel quel à l'utilisateur quand il est lisible. */
    private fun readableLocation(uri: Uri): String? {
        val path = uri.path ?: return null
        val decoded = path.substringAfter("/document/").substringAfter(':')
        val folder = decoded.substringBeforeLast('/', "")
        return folder.takeIf { it.isNotBlank() && !it.contains("://") }
    }
}
