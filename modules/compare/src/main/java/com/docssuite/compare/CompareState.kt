package com.docssuite.compare

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.DocumentText
import com.docssuite.fileformats.CellRef
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import com.docssuite.fileformats.PdfText
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TextDocument
import com.docssuite.texteditor.loadTextDocument

/** L'une des deux versions comparées. */
enum class Slot { BEFORE, AFTER }

/** Une version chargée : son nom et ses paragraphes. */
data class Version(val name: String, val paragraphs: List<String>)

/**
 * La comparaison en cours : deux versions à choisir, le résultat dès qu'elles
 * sont là. Séparée de l'écran pour être vérifiable sans rendu.
 */
class CompareState(context: Context) {

    private val storage = DocumentStorage(context)

    var before by mutableStateOf<Version?>(null)
        private set
    var after by mutableStateOf<Version?>(null)
        private set
    var includeUnchanged by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
        private set

    val documents: List<DocMeta> get() = storage.list()

    /** Calculée quand une version change, pas à chaque passage d'affichage. */
    var comparison by mutableStateOf<Comparison?>(null)
        private set

    fun chooseSaved(slot: Slot, id: String) {
        val meta = storage.meta(id)
        val paragraphs = when {
            meta == null -> null
            meta.type == DocType.TEXT -> loadTextDocument(storage, id)?.paragraphs?.map { it.plainText }
            else -> storage.load(id)?.let { DocumentText.extract(meta.type, it).lines() }
        }
        if (meta == null || paragraphs == null) {
            problem = "Ce document n'a pas pu être relu."
            return
        }
        put(slot, Version(meta.name, paragraphs))
    }

    fun chooseFile(slot: Slot, fileName: String?, bytes: ByteArray) {
        val name = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Fichier"
        val paragraphs = runCatching { paragraphsOf(fileName, bytes) }.getOrNull()
        if (paragraphs == null) {
            problem = "Ce fichier n'a pas pu être ouvert."
            return
        }
        if (paragraphs.none { it.isNotBlank() }) {
            problem = "Aucun texte à comparer dans ce fichier."
            return
        }
        put(slot, Version(name, paragraphs))
    }

    /** Word, OpenDocument, PDF, texte, tableurs, présentations. */
    private fun paragraphsOf(fileName: String?, bytes: ByteArray): List<String> =
        when (val imported = FileFormats.import(fileName, bytes)) {
            is Imported.AsText -> imported.document.paragraphs.map { it.plainText }
            is Imported.AsSheet -> imported.workbook.sheets.flatMap { rows(it) }
            is Imported.AsDeck -> imported.deck.slides.flatMap { listOf(it.title, it.content) }
            is Imported.AsPdf -> PdfText.extract(imported.bytes).lines()
        }

    /** Une rangée par ligne, cellules séparées par des tabulations. */
    private fun rows(sheet: Sheet): List<String> =
        sheet.cells.entries
            .mapNotNull { (ref, value) -> CellRef.parse(ref)?.let { it to value } }
            .filter { it.second.isNotBlank() }
            .groupBy({ it.first.first }, { it.first.second to it.second })
            .toSortedMap()
            .values
            .map { cells -> cells.sortedBy { it.first }.joinToString("\t") { it.second } }

    private fun put(slot: Slot, version: Version) {
        problem = null
        when (slot) {
            Slot.BEFORE -> before = version
            Slot.AFTER -> after = version
        }
        recompute()
    }

    fun clear(slot: Slot) {
        when (slot) {
            Slot.BEFORE -> before = null
            Slot.AFTER -> after = null
        }
        recompute()
    }

    fun swap() {
        val old = before
        before = after
        after = old
        recompute()
    }

    private fun recompute() {
        val a = before
        val b = after
        comparison = if (a == null || b == null) null else CompareDocuments.of(a.paragraphs, b.paragraphs)
    }

    fun report(): TextDocument? {
        val result = comparison ?: return null
        return CompareDocuments.report(
            result,
            before?.name.orEmpty(),
            after?.name.orEmpty(),
            includeUnchanged
        )
    }

    fun dismissProblem() {
        problem = null
    }
}
