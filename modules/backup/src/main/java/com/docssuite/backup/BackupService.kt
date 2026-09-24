package com.docssuite.backup

import android.content.Context
import com.docssuite.core.DocumentLibrary
import com.docssuite.core.DocumentStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RestoreResult(val added: Int, val updated: Int, val copies: Int, val unchanged: Int)

/** Sauvegarde et restauration de tous les documents de l'app et de leur rangement. */
class BackupService(context: Context) {

    private val storage = DocumentStorage(context)
    private val library = DocumentLibrary(context)
    private val prefs = context.applicationContext.getSharedPreferences("docssuite_backup", Context.MODE_PRIVATE)

    fun currentDocuments(): List<SavedDocument> =
        storage.list().mapNotNull { meta -> storage.load(meta.id)?.let { SavedDocument(meta, it) } }

    val folderCount: Int get() = library.folders().size

    fun createBackup(now: Long = System.currentTimeMillis()): ByteArray =
        BackupArchive.write(currentDocuments(), library.exportJson(), now)

    /** Seulement une fois le fichier réellement écrit : une sauvegarde annulée ne compte pas. */
    fun markSaved(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST, now).apply()
    }

    fun lastBackupAt(): Long? = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }

    fun plan(contents: BackupContents): RestorePlan =
        RestorePlan.of(contents, currentDocuments().associateBy { it.meta.id })

    /**
     * Applique [plan]. Rien n'est jamais supprimé : un document absent de la
     * sauvegarde reste, une version plus récente dans l'app est gardée. Avec
     * [keepConflictCopies], la version de la sauvegarde d'un document modifié
     * depuis est ajoutée à côté, sous un autre nom.
     */
    fun restore(contents: BackupContents, plan: RestorePlan, keepConflictCopies: Boolean): RestoreResult {
        (plan.added + plan.updated).forEach { storage.restore(it.meta, it.payload) }
        var copies = 0
        if (keepConflictCopies) {
            val label = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(contents.createdAt))
            plan.conflicts.forEach { saved ->
                storage.restore(saved.meta.copy(id = storage.newId(), name = "${saved.meta.name} (sauvegarde du $label)"), saved.payload)
                copies++
            }
        }
        contents.library?.let(library::mergeJson)
        return RestoreResult(plan.added.size, plan.updated.size, copies, plan.unchanged.size)
    }

    private companion object {
        const val KEY_LAST = "lastBackupAt"
    }
}
