package com.docssuite.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * Relie le protocole de transfert (qui ne connaît que [FileSource]/[FileSink])
 * au Storage Access Framework : les octets ne passent jamais par la mémoire,
 * quel que soit le type ou la taille du fichier.
 */

/**
 * Fichiers choisis par l'utilisateur (n'importe quel type), prêts à proposer.
 * Seuls le nom, la taille et le type sont lus à l'avance ; le contenu n'est
 * ouvert qu'au moment de l'envoi.
 */
fun urisToOfferedFiles(context: Context, uris: List<Uri>): List<OfferedFile> {
    val resolver = context.contentResolver
    return uris.map { uri ->
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        if (size < 0) {
            // Repli pour un fournisseur qui omet la taille dans le curseur :
            // pour un flux adossé à un vrai fichier, `available()` au tout
            // début du flux donne la taille exacte. Un 0 reste ambigu (flux
            // sans longueur connue plutôt que fichier réellement vide) et
            // n'est donc pas retenu : mieux vaut échouer que tronquer.
            size = runCatching { resolver.openInputStream(uri)?.use { it.available().toLong() } }
                .getOrNull()?.takeIf { it > 0 } ?: -1L
        }
        if (size < 0) {
            throw IOException("Taille de « $name » inconnue : ce fournisseur de fichiers ne la communique pas")
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val source = FileSource { resolver.openInputStream(uri) ?: throw IOException("« $name » est illisible") }
        OfferedFile(TransferFile(name, size, mime), source)
    }
}

/**
 * Crée, dans le dossier choisi par l'utilisateur pour la réception, un
 * document par fichier du manifeste, prêt à recevoir ses octets. Un nom déjà
 * présent est désambiguïsé plutôt qu'écrasé.
 */
fun sinksInTree(context: Context, treeUri: Uri, files: List<TransferFile>): Map<String, FileSink> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Dossier de réception inaccessible")
    val resolver = context.contentResolver
    // Un seul passage sur le dossier existant : éviter un findFile() par nom
    // candidat, qui refait lui-même le tour complet du dossier à chaque appel.
    val existingNames = root.listFiles().mapNotNull { it.name }.toMutableSet()
    return files.associate { file ->
        val mime = file.mimeType.ifBlank { "application/octet-stream" }
        val uniqueName = uniqueChildName(existingNames, file.name.ifBlank { "fichier" })
        existingNames.add(uniqueName)
        val doc = root.createFile(mime, uniqueName) ?: throw IOException("Impossible de créer « ${file.name} »")
        file.name to FileSink { resolver.openOutputStream(doc.uri) ?: throw IOException("« ${file.name} » est illisible en écriture") }
    }
}

/** Le premier nom libre : celui demandé, sinon suffixé de « (2) », « (3) »… */
internal fun uniqueChildName(existingNames: Set<String>, name: String): String {
    if (name !in existingNames) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    var attempt = 2
    while ("$base ($attempt)$extension" in existingNames) attempt++
    return "$base ($attempt)$extension"
}
