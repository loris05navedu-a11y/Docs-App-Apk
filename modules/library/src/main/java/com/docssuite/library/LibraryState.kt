package com.docssuite.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentLibrary
import com.docssuite.core.DocumentSearch
import com.docssuite.core.DocumentStorage
import com.docssuite.core.DocumentText
import com.docssuite.core.Folder
import com.docssuite.core.SearchHit

enum class KindFilter(val label: String, val type: DocType?) {
    ALL("Tous", null),
    TEXT("Documents", DocType.TEXT),
    SHEET("Tableurs", DocType.SHEET),
    DECK("Présentations", DocType.DECK)
}

enum class SortOrder(val label: String) { RECENT("Récents"), NAME("A → Z") }

/** Dossier choisi : tous, un dossier précis, ou les documents sans dossier. */
sealed interface FolderFilter {
    data object Any : FolderFilter
    data object None : FolderFilter
    data class In(val id: String) : FolderFilter
}

/**
 * Tous les documents de l'app, cherchables et rangeables. Le texte de
 * chaque document n'est extrait qu'à la première recherche, puis gardé
 * tant que l'écran est ouvert.
 */
class LibraryState(context: Context) {

    private val storage = DocumentStorage(context)
    private val library = DocumentLibrary(context)

    val documents = mutableStateListOf<DocMeta>()
    val folders = mutableStateListOf<Folder>()
    var favorites by mutableStateOf(emptySet<String>())
        private set
    private var placement by mutableStateOf(emptyMap<String, String>())

    var query by mutableStateOf("")
    var kind by mutableStateOf(KindFilter.ALL)
    var favoritesOnly by mutableStateOf(false)
    var folder by mutableStateOf<FolderFilter>(FolderFilter.Any)
    var sort by mutableStateOf(SortOrder.RECENT)

    private val texts = HashMap<String, String>()

    init {
        reload()
    }

    fun reload() {
        documents.clear()
        documents.addAll(storage.list())
        folders.clear()
        folders.addAll(library.folders())
        favorites = library.favorites()
        placement = documents.mapNotNull { doc -> library.folderOf(doc.id)?.let { doc.id to it } }.toMap()
        if (folder is FolderFilter.In && folders.none { it.id == (folder as FolderFilter.In).id }) folder = FolderFilter.Any
        // Sans aucun dossier, la puce « Sans dossier » n'est plus affichée :
        // son filtre ne doit pas rester actif, invisible.
        if (folder == FolderFilter.None && folders.isEmpty()) folder = FolderFilter.Any
    }

    fun folderOf(doc: DocMeta): Folder? = placement[doc.id]?.let { id -> folders.firstOrNull { it.id == id } }

    fun isFavorite(doc: DocMeta) = doc.id in favorites

    /** Les documents retenus par les filtres (sans la recherche), dans l'ordre choisi. */
    fun filtered(): List<DocMeta> = documents
        .filter { kind.type == null || it.type == kind.type }
        .filter { !favoritesOnly || it.id in favorites }
        .filter {
            when (val f = folder) {
                FolderFilter.Any -> true
                FolderFilter.None -> placement[it.id] == null
                is FolderFilter.In -> placement[it.id] == f.id
            }
        }
        .let { list ->
            when (sort) {
                SortOrder.RECENT -> list.sortedByDescending { it.updatedAt }
                SortOrder.NAME -> list.sortedBy { DocumentSearch.fold(it.name).text }
            }
        }

    /**
     * Recherche plein texte parmi [candidates] (la liste filtrée, prise sur
     * le fil principal). Peut tourner sur un autre fil.
     */
    fun results(query: String, candidates: List<DocMeta>): List<SearchHit> {
        if (query.isBlank()) return candidates.map { SearchHit(it, "", emptyList(), emptyList(), 0) }
        val withText = candidates.map { meta ->
            meta to synchronized(texts) {
                texts.getOrPut(meta.id) { storage.load(meta.id)?.let { DocumentText.extract(meta.type, it) } ?: "" }
            }
        }
        return DocumentSearch.search(query, withText)
    }

    fun toggleFavorite(doc: DocMeta) {
        library.setFavorite(doc.id, doc.id !in favorites)
        favorites = library.favorites()
    }

    fun rename(doc: DocMeta, name: String) {
        storage.rename(doc.id, name)
        reload()
    }

    fun delete(doc: DocMeta) {
        storage.delete(doc.id)
        library.forget(doc.id)
        synchronized(texts) { texts.remove(doc.id) }
        reload()
    }

    fun moveTo(doc: DocMeta, folderId: String?) {
        library.moveTo(doc.id, folderId)
        reload()
    }

    fun createFolder(name: String): Folder = library.createFolder(name).also { reload() }

    fun renameFolder(id: String, name: String) {
        library.renameFolder(id, name)
        reload()
    }

    fun deleteFolder(id: String) {
        library.deleteFolder(id)
        if (folder == FolderFilter.In(id)) folder = FolderFilter.Any
        reload()
    }

    fun countIn(folderId: String) = placement.values.count { it == folderId }
}
