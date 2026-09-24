package com.docssuite.reader

import com.docssuite.core.DocType
import com.docssuite.core.DocumentText
import com.docssuite.fileformats.CellRef
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import com.docssuite.fileformats.PdfText
import com.docssuite.fileformats.Sheet

/** Le texte à lire d'un document de l'app ou d'un fichier ouvert. */
object ReadableText {

    fun fromDocument(type: DocType, payload: String): String = clean(DocumentText.extract(type, payload))

    /** Word, OpenDocument, PDF, texte, tableurs, présentations : tout ce que l'app sait importer. */
    fun fromFile(fileName: String?, bytes: ByteArray): String {
        val text = when (val imported = FileFormats.import(fileName, bytes)) {
            is Imported.AsText -> imported.document.plainText
            is Imported.AsSheet -> imported.workbook.sheets.joinToString("\n\n") { sheet -> sheetText(sheet) }
            is Imported.AsDeck -> imported.deck.slides.joinToString("\n\n") { slide ->
                listOf(slide.title, slide.content).filter { it.isNotBlank() }.joinToString("\n")
            }
            is Imported.AsPdf -> PdfText.extract(imported.bytes)
        }
        return clean(text)
    }

    /** Une rangée par ligne, cellules séparées par des virgules : la voix marque une courte pause. */
    private fun sheetText(sheet: Sheet): String {
        val rows = sheet.cells.entries
            .mapNotNull { (ref, value) -> CellRef.parse(ref)?.let { it to value } }
            .filter { it.second.isNotBlank() && !it.second.startsWith("=") }
            .groupBy({ it.first.first }, { it.first.second to it.second })
            .toSortedMap()
        return (listOf(sheet.name) + rows.values.map { cells -> cells.sortedBy { it.first }.joinToString(", ") { it.second } })
            .joinToString("\n")
    }

    /** Lignes vides en trop, espaces multiples et césures de fin de ligne des PDF. */
    fun clean(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(' ', ' ')
        .replace(Regex("(\\p{L})-\n(\\p{Ll})"), "$1$2")
        .replace(Regex("[ \t]+"), " ")
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
