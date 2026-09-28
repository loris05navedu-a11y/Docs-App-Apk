package com.docssuite.flashcards

import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import com.docssuite.fileformats.PdfText
import com.docssuite.fileformats.Sheet
import com.docssuite.texteditor.loadTextDocument
import org.json.JSONObject

/** Où trouver des fiches : un document de l'app, ou un fichier. */
internal object FlashcardSources {

    fun fromSaved(storage: DocumentStorage, id: String): CardImport.Result? {
        val meta = storage.meta(id) ?: return null
        return when (meta.type) {
            DocType.TEXT -> loadTextDocument(storage, id)?.let(CardImport::fromDocument)
            DocType.SHEET -> storage.load(id)?.let { best(sheets(it)) }
            DocType.DECK -> null
        }
    }

    /**
     * Word, OpenDocument, texte, tableurs, PDF — et présentations : chaque
     * diapositive devient une fiche, son titre au recto, son contenu au verso.
     */
    fun fromFile(fileName: String?, bytes: ByteArray): CardImport.Result =
        when (val imported = FileFormats.import(fileName, bytes)) {
            is Imported.AsText -> CardImport.fromDocument(imported.document)
            is Imported.AsSheet -> best(imported.workbook.sheets)
            is Imported.AsDeck -> {
                val slides = imported.deck.slides
                val cards = slides.mapNotNull { slide ->
                    val front = slide.title.trim()
                    val back = listOf(slide.content, slide.secondContent).filter { it.isNotBlank() }.joinToString("\n").trim()
                    if (front.isEmpty() || back.isEmpty()) null else front to back
                }
                CardImport.Result(cards, slides.size - cards.size)
            }
            is Imported.AsPdf -> CardImport.fromText(PdfText.extract(imported.bytes))
        }

    /** La feuille qui donne le plus de fiches : un classeur commence souvent par une page de garde. */
    private fun best(sheets: List<Sheet>): CardImport.Result =
        sheets.map(CardImport::fromSheet).maxByOrNull { it.cards.size } ?: CardImport.Result(emptyList(), 0)

    /** Les cases de chaque feuille d'un tableur enregistré ; la mise en forme ne sert à rien ici. */
    private fun sheets(payload: String): List<Sheet> = runCatching {
        val root = JSONObject(payload)
        val array = root.optJSONArray("sheets")
        val nodes = if (array == null) listOf(root) else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        nodes.map { node ->
            val cells = HashMap<String, String>()
            node.optJSONObject("cells")?.let { json -> json.keys().forEach { cells[it] = json.optString(it) } }
            Sheet(node.optString("name").ifBlank { "Feuille" }, cells)
        }
    }.getOrDefault(emptyList())
}
