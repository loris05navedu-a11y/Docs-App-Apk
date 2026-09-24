package com.docssuite.backup

import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Un document tel qu'il est enregistré : ses informations et son contenu. */
data class SavedDocument(val meta: DocMeta, val payload: String)

/** Le contenu d'une sauvegarde, vérifié. */
data class BackupContents(
    val createdAt: Long,
    val documents: List<SavedDocument>,
    /** Dossiers et favoris ; `null` dans une sauvegarde qui n'en avait pas. */
    val library: JSONObject?
)

class BackupFormatException(message: String) : Exception(message)

/**
 * Une sauvegarde est une archive ZIP ordinaire : `manifest.json` décrit son
 * contenu, chaque document est un fichier de `documents/`, le rangement est
 * dans `library.json`. Elle s'ouvre avec n'importe quel outil ZIP.
 */
object BackupArchive {

    const val FORMAT = "docsapp-suite-backup"
    const val VERSION = 1

    /** Garde-fous contre une archive piégée qui se décompresserait en un volume énorme. */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    fun write(documents: List<SavedDocument>, library: JSONObject, createdAt: Long): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val list = JSONArray()
            documents.forEachIndexed { index, doc ->
                val path = "documents/${index + 1}.json"
                list.put(
                    JSONObject()
                        .put("id", doc.meta.id)
                        .put("name", doc.meta.name)
                        .put("type", doc.meta.type.name)
                        .put("updatedAt", doc.meta.updatedAt)
                        .put("file", path)
                )
                zip.putNextEntry(ZipEntry(path))
                zip.write(doc.payload.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(library.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // Le manifeste en dernier : une archive tronquée n'en a pas, et est refusée.
            zip.putNextEntry(ZipEntry("manifest.json"))
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("createdAt", createdAt)
                .put("documents", list)
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Lit et vérifie une sauvegarde en entier avant que quoi que ce soit ne
     * soit restauré : un fichier abîmé est refusé, il n'écrase rien.
     */
    fun read(bytes: ByteArray): BackupContents {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entries.size >= MAX_ENTRIES) throw BackupFormatException("Sauvegarde trop volumineuse")
                    if (entry.isDirectory) continue
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL_BYTES) throw BackupFormatException("Sauvegarde trop volumineuse")
                        out.write(buffer, 0, n)
                    }
                    entries[entry.name] = out.toByteArray()
                }
            }
        } catch (error: BackupFormatException) {
            throw error
        } catch (error: Exception) {
            throw BackupFormatException("Ce fichier est abîmé ou n'est pas une archive ZIP")
        }

        val manifest = entries["manifest.json"]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            ?: throw BackupFormatException("Ce fichier n'est pas une sauvegarde DocsApp Suite")
        if (manifest.optString("format") != FORMAT) throw BackupFormatException("Ce fichier n'est pas une sauvegarde DocsApp Suite")
        if (manifest.optInt("version") > VERSION) {
            throw BackupFormatException("Cette sauvegarde vient d'une version plus récente de l'app : mets l'app à jour pour la restaurer")
        }

        val list = manifest.optJSONArray("documents") ?: JSONArray()
        val documents = (0 until list.length()).map { i ->
            val o = list.optJSONObject(i) ?: throw BackupFormatException("Sauvegarde abîmée (document ${i + 1})")
            val name = o.optString("name").ifBlank { "Document" }
            val type = runCatching { DocType.valueOf(o.getString("type")) }.getOrNull()
                ?: throw BackupFormatException("Sauvegarde abîmée : type inconnu pour « $name »")
            val payload = entries[o.optString("file")]?.let { String(it, Charsets.UTF_8) }
                ?: throw BackupFormatException("Sauvegarde incomplète : « $name » manque")
            // Le contenu doit être lisible par l'éditeur : un JSON valide.
            val valid = runCatching {
                if (payload.trimStart().startsWith("[")) JSONArray(payload) else JSONObject(payload)
            }.isSuccess
            if (!valid) throw BackupFormatException("Sauvegarde abîmée : « $name » est illisible")
            val id = o.optString("id").ifBlank { throw BackupFormatException("Sauvegarde abîmée : « $name » sans identifiant") }
            SavedDocument(DocMeta(id, name, type, o.optLong("updatedAt")), payload)
        }
        val library = entries["library.json"]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        return BackupContents(manifest.optLong("createdAt"), documents, library)
    }
}

/** Ce que ferait une restauration, calculé avant d'écrire quoi que ce soit. */
data class RestorePlan(
    /** Absents de l'app : ajoutés. */
    val added: List<SavedDocument>,
    /** Plus récents dans la sauvegarde : ils remplacent la version de l'app. */
    val updated: List<SavedDocument>,
    /** Identiques : rien à faire. */
    val unchanged: List<SavedDocument>,
    /** Modifiés dans l'app depuis la sauvegarde : l'app garde sa version. */
    val conflicts: List<SavedDocument>
) {
    companion object {
        fun of(backup: BackupContents, current: Map<String, SavedDocument>): RestorePlan {
            val added = ArrayList<SavedDocument>()
            val updated = ArrayList<SavedDocument>()
            val unchanged = ArrayList<SavedDocument>()
            val conflicts = ArrayList<SavedDocument>()
            backup.documents.forEach { saved ->
                val existing = current[saved.meta.id]
                when {
                    existing == null -> added += saved
                    existing.payload == saved.payload && existing.meta.name == saved.meta.name -> unchanged += saved
                    saved.meta.updatedAt > existing.meta.updatedAt -> updated += saved
                    else -> conflicts += saved
                }
            }
            return RestorePlan(added, updated, unchanged, conflicts)
        }
    }
}
