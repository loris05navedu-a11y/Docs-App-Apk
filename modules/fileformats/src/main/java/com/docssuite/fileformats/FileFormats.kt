package com.docssuite.fileformats

/** Famille de document, qui détermine l'éditeur à ouvrir. */
enum class DocKind { TEXT, SHEET, DECK, PDF, MEDIA }

/**
 * Un format de fichier géré par l'app.
 *
 * [exportable] distingue les formats qu'on sait écrire de ceux qu'on sait
 * seulement lire : les binaires Office 97-2003 sont importés puis
 * réenregistrés dans leur équivalent moderne.
 */
enum class FileFormat(
    val extension: String,
    val mime: String,
    val label: String,
    val kind: DocKind,
    val exportable: Boolean = true
) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (.docx)", DocKind.TEXT),
    DOC("doc", "application/msword", "Word 97-2003 (.doc)", DocKind.TEXT, exportable = false),
    ODT("odt", Odf.MIME_TEXT, "OpenDocument texte (.odt)", DocKind.TEXT),
    RTF("rtf", "application/rtf", "Texte enrichi (.rtf)", DocKind.TEXT),
    HTML("html", "text/html", "Page web (.html)", DocKind.TEXT),
    MD("md", "text/markdown", "Markdown (.md)", DocKind.TEXT),
    TXT("txt", "text/plain", "Texte brut (.txt)", DocKind.TEXT),

    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (.xlsx)", DocKind.SHEET),
    XLS("xls", "application/vnd.ms-excel", "Excel 97-2003 (.xls)", DocKind.SHEET, exportable = false),
    ODS("ods", Odf.MIME_SHEET, "OpenDocument classeur (.ods)", DocKind.SHEET),
    CSV("csv", "text/csv", "CSV (.csv)", DocKind.SHEET),

    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint (.pptx)", DocKind.DECK),
    PPT("ppt", "application/vnd.ms-powerpoint", "PowerPoint 97-2003 (.ppt)", DocKind.DECK, exportable = false),
    ODP("odp", Odf.MIME_DECK, "OpenDocument présentation (.odp)", DocKind.DECK),

    PDF("pdf", "application/pdf", "PDF (.pdf)", DocKind.PDF);

    companion object {
        fun byExtension(extension: String?): FileFormat? {
            val clean = extension?.lowercase()?.removePrefix(".") ?: return null
            return values().firstOrNull { it.extension == clean }
                ?: when (clean) {
                    "htm" -> HTML
                    "markdown" -> MD
                    "tsv", "text" -> if (clean == "tsv") CSV else TXT
                    "docm", "dotx", "dotm" -> DOCX
                    "xlsm", "xltx", "xltm" -> XLSX
                    "pptm", "potx", "ppsx" -> PPTX
                    "ott" -> ODT
                    "ots" -> ODS
                    "otp" -> ODP
                    else -> null
                }
        }

        /**
         * Formats d'enregistrement proposés pour une famille de document. HTML
         * et Markdown savent rendre un tableau ou des diapositives, ils ne se
         * déduisent donc pas de [kind].
         */
        fun exportTargets(kind: DocKind): List<FileFormat> = when (kind) {
            DocKind.TEXT -> listOf(DOCX, PDF, ODT, RTF, HTML, MD, TXT)
            DocKind.SHEET -> listOf(XLSX, PDF, ODS, CSV, HTML, MD)
            DocKind.DECK -> listOf(PPTX, PDF, ODP, MD)
            DocKind.PDF, DocKind.MEDIA -> listOf(PDF)
        }
    }
}

/** Résultat d'un import, déjà converti dans le modèle pivot. */
sealed interface Imported {
    val suggestedName: String

    data class AsText(val document: TextDocument, override val suggestedName: String) : Imported
    data class AsSheet(val workbook: Workbook, override val suggestedName: String) : Imported
    data class AsDeck(val deck: Deck, override val suggestedName: String) : Imported

    /** Un PDF s'ouvre dans le lecteur, qui affiche les pages telles quelles. */
    data class AsPdf(val bytes: ByteArray, override val suggestedName: String) : Imported {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}

object FileFormats {

    /**
     * Devine le format d'après le contenu, puis l'extension. La signature prime
     * car les fichiers reçus en pièce jointe arrivent souvent mal nommés.
     */
    fun detect(fileName: String?, bytes: ByteArray): FileFormat? {
        val extension = fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        val byName = FileFormat.byExtension(extension)

        if (PdfText.looksLikePdf(bytes)) return FileFormat.PDF
        if (isZip(bytes)) return detectZip(bytes) ?: byName
        if (Cfb.looksLikeCfb(bytes)) return detectCfb(bytes) ?: byName
        if (byName != null) return byName

        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 512)), Charsets.ISO_8859_1)
        return when {
            head.startsWith("{\\rtf") -> FileFormat.RTF
            head.contains("<html", ignoreCase = true) -> FileFormat.HTML
            else -> null
        }
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        (bytes[2].toInt() == 3 || bytes[2].toInt() == 5 || bytes[2].toInt() == 7)

    private fun detectZip(bytes: ByteArray): FileFormat? {
        // On ne décompresse que `mimetype` : la liste des noms suffit au reste.
        val names = runCatching { zipEntryNames(bytes) }.getOrNull() ?: return null
        if ("mimetype" in names) {
            val mime = runCatching {
                unzip(bytes, keep = { it == "mimetype" })["mimetype"]
                    ?.toString(Charsets.UTF_8)?.trim()
            }.getOrNull()
            when (mime) {
                Odf.MIME_TEXT, "application/vnd.oasis.opendocument.text-template" -> return FileFormat.ODT
                Odf.MIME_SHEET, "application/vnd.oasis.opendocument.spreadsheet-template" -> return FileFormat.ODS
                Odf.MIME_DECK, "application/vnd.oasis.opendocument.presentation-template" -> return FileFormat.ODP
            }
        }
        return when {
            names.any { it.startsWith("word/") } -> FileFormat.DOCX
            names.any { it.startsWith("xl/") } -> FileFormat.XLSX
            names.any { it.startsWith("ppt/") } -> FileFormat.PPTX
            else -> null
        }
    }

    private fun detectCfb(bytes: ByteArray): FileFormat? {
        val names = runCatching { Cfb.open(bytes).streamNames() }.getOrNull() ?: return null
        return when {
            names.any { it == "WordDocument" } -> FileFormat.DOC
            names.any { it == "Workbook" || it == "Book" } -> FileFormat.XLS
            names.any { it.startsWith("PowerPoint") } -> FileFormat.PPT
            else -> null
        }
    }

    fun import(fileName: String?, bytes: ByteArray): Imported {
        val format = detect(fileName, bytes)
            ?: throw FormatException("Format de fichier non reconnu")
        val name = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Import"
        // Un fichier texte venu de Windows est souvent en cp1252 ou en UTF-16.
        val text = { decodeText(bytes) }

        return when (format) {
            FileFormat.DOCX -> Imported.AsText(Docx.read(bytes, name), name)
            FileFormat.DOC -> Imported.AsText(DocLegacy.read(bytes, name), name)
            FileFormat.ODT -> Imported.AsText(Odf.readText(bytes, name), name)
            FileFormat.RTF -> Imported.AsText(Rtf.read(text(), name), name)
            FileFormat.HTML -> Imported.AsText(Html.read(bytes, name), name)
            FileFormat.MD -> Imported.AsText(Markdown.read(text(), name), name)
            FileFormat.TXT -> Imported.AsText(
                TextDocument(
                    name,
                    text().removePrefix("\uFEFF").split('\n').map { TextParagraph(listOf(TextRun(it.trimEnd('\r')))) }
                ),
                name
            )

            FileFormat.XLSX -> Imported.AsSheet(Xlsx.read(bytes, name), name)
            FileFormat.XLS -> Imported.AsSheet(XlsLegacy.read(bytes, name), name)
            FileFormat.ODS -> Imported.AsSheet(Odf.readSheet(bytes, name), name)
            FileFormat.CSV -> Imported.AsSheet(Workbook(name, listOf(Csv.read(text(), name))), name)

            FileFormat.PPTX -> Imported.AsDeck(Pptx.read(bytes, name), name)
            FileFormat.PPT -> Imported.AsDeck(PptLegacy.read(bytes, name), name)
            FileFormat.ODP -> Imported.AsDeck(Odf.readDeck(bytes, name), name)

            FileFormat.PDF -> Imported.AsPdf(bytes, name)
        }
    }

    fun exportText(format: FileFormat, document: TextDocument): ByteArray = when (format) {
        FileFormat.DOCX -> Docx.write(document)
        FileFormat.ODT -> Odf.writeText(document)
        FileFormat.RTF -> Rtf.write(document).toByteArray(Charsets.UTF_8)
        FileFormat.HTML -> Html.write(document).toByteArray(Charsets.UTF_8)
        FileFormat.MD -> Markdown.write(document).toByteArray(Charsets.UTF_8)
        FileFormat.TXT -> document.plainText.toByteArray(Charsets.UTF_8)
        FileFormat.PDF -> PdfExport.fromTextDocument(document)
        else -> throw FormatException("${format.label} n'est pas un format de document")
    }

    fun exportSheet(
        format: FileFormat,
        workbook: Workbook,
        display: (Sheet, String) -> String
    ): ByteArray {
        val first = workbook.sheets.firstOrNull() ?: Sheet()
        return when (format) {
            FileFormat.XLSX -> Xlsx.write(workbook) { sheet, ref -> display(sheet, ref) }
            FileFormat.ODS -> Odf.writeSheet(workbook, display)
            FileFormat.CSV -> Csv.write(first) { display(first, it) }.toByteArray(Charsets.UTF_8)
            FileFormat.HTML -> Html.write(first) { display(first, it) }.toByteArray(Charsets.UTF_8)
            FileFormat.MD -> Markdown.write(first) { display(first, it) }.toByteArray(Charsets.UTF_8)
            FileFormat.PDF -> PdfExport.fromWorkbook(workbook, display)
            else -> throw FormatException("${format.label} n'est pas un format de classeur")
        }
    }

    fun exportDeck(format: FileFormat, deck: Deck): ByteArray = when (format) {
        FileFormat.PPTX -> Pptx.write(deck)
        FileFormat.ODP -> Odf.writeDeck(deck)
        FileFormat.MD -> Markdown.write(deck).toByteArray(Charsets.UTF_8)
        FileFormat.PDF -> PdfExport.fromDeck(deck)
        else -> throw FormatException("${format.label} n'est pas un format de présentation")
    }
}
