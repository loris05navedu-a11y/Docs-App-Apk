package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser

/**
 * OpenDocument (.odt, .ods, .odp), le format natif de LibreOffice et
 * OpenOffice. Même principe qu'OOXML — une archive de XML — mais avec ses
 * propres espaces de noms et une entrée `mimetype` non compressée en tête.
 */
object Odf {

    private const val NS_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val NS_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val NS_TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val NS_DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val NS_STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val NS_FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private const val NS_SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"

    const val MIME_TEXT = "application/vnd.oasis.opendocument.text"
    const val MIME_SHEET = "application/vnd.oasis.opendocument.spreadsheet"
    const val MIME_DECK = "application/vnd.oasis.opendocument.presentation"

    // ------------------------------------------------------------ écriture

    fun writeText(document: TextDocument): ByteArray {
        val textStyles = StringBuilder()
        val paragraphStyles = StringBuilder()
        val body = StringBuilder()

        document.paragraphs.forEachIndexed { index, paragraph ->
            val paragraphStyle = "P$index"
            paragraphStyles.append(
                "<style:style style:name=\"$paragraphStyle\" style:family=\"paragraph\">" +
                    "<style:paragraph-properties fo:text-align=\"${textAlign(paragraph.align)}\"/>" +
                    "</style:style>"
            )
            val spans = paragraph.runs.mapIndexed { position, run ->
                val name = "T${index}_$position"
                textStyles.append(
                    "<style:style style:name=\"$name\" style:family=\"text\">" +
                        "<style:text-properties" +
                        " style:font-name=\"${xmlEscape(officeFontName(run.fontName))}\"" +
                        " fo:font-size=\"${run.size}pt\"" +
                        " fo:color=\"#${run.color.toHexRgb()}\"" +
                        (if (run.bold) " fo:font-weight=\"bold\"" else "") +
                        (if (run.italic) " fo:font-style=\"italic\"" else "") +
                        (if (run.underline) " style:text-underline-style=\"solid\"" else "") +
                        (if (run.strike) " style:text-line-through-style=\"solid\"" else "") +
                        (if (run.highlight != 0L) {
                            " fo:background-color=\"#${run.highlight.toHexRgb()}\""
                        } else "") +
                        "/></style:style>"
                )
                "<text:span text:style-name=\"$name\">${odfText(run.text)}</text:span>"
            }.joinToString("")
            body.append("<text:p text:style-name=\"$paragraphStyle\">$spans</text:p>")
        }

        val content = XML_DECL +
            "<office:document-content ${namespaces()} office:version=\"1.3\">" +
            "<office:automatic-styles>$paragraphStyles$textStyles</office:automatic-styles>" +
            "<office:body><office:text>$body</office:text></office:body>" +
            "</office:document-content>"
        return pack(MIME_TEXT, content)
    }

    fun writeSheet(workbook: Workbook, display: (Sheet, String) -> String): ByteArray {
        val styles = StringBuilder()
        val tables = StringBuilder()
        val seen = LinkedHashMap<CellStyle, String>()

        workbook.sheets.forEach { sheet ->
            tables.append("<table:table table:name=\"${xmlEscape(sheet.name)}\">")
            tables.append(
                "<table:table-column table:number-columns-repeated=\"${sheet.columns}\"/>"
            )
            for (row in 0 until sheet.rows) {
                tables.append("<table:table-row>")
                for (column in 0 until sheet.columns) {
                    val ref = CellRef.key(row, column)
                    val raw = sheet.cells[ref].orEmpty()
                    val shown = display(sheet, ref)
                    val style = sheet.styles[ref]?.takeIf { !it.isDefault }
                    val styleName = style?.let {
                        seen.getOrPut(it) {
                            val name = "ce${seen.size}"
                            styles.append(cellStyleXml(name, it))
                            name
                        }
                    }
                    val attributes = StringBuilder()
                    if (styleName != null) attributes.append(" table:style-name=\"$styleName\"")
                    if (raw.startsWith("=")) {
                        attributes.append(
                            " table:formula=\"of:=${xmlEscape(odfFormula(raw.removePrefix("=")))}\""
                        )
                    }
                    val number = shown.replace(',', '.').toDoubleOrNull()
                    if (number != null && shown.isNotEmpty()) {
                        attributes.append(" office:value-type=\"float\" office:value=\"$number\"")
                    } else if (shown.isNotEmpty()) {
                        attributes.append(" office:value-type=\"string\"")
                    }
                    tables.append("<table:table-cell$attributes>")
                    if (shown.isNotEmpty()) tables.append("<text:p>${odfText(shown)}</text:p>")
                    tables.append("</table:table-cell>")
                }
                tables.append("</table:table-row>")
            }
            tables.append("</table:table>")
        }

        val content = XML_DECL +
            "<office:document-content ${namespaces()} office:version=\"1.3\">" +
            "<office:automatic-styles>$styles</office:automatic-styles>" +
            "<office:body><office:spreadsheet>$tables</office:spreadsheet></office:body>" +
            "</office:document-content>"
        return pack(MIME_SHEET, content)
    }

    private fun cellStyleXml(name: String, style: CellStyle): String {
        val cellProperties = if (style.background != 0L) {
            "<style:table-cell-properties fo:background-color=\"#${style.background.toHexRgb()}\"/>"
        } else ""
        val alignment = when (style.align) {
            1 -> "start"
            2 -> "center"
            3 -> "end"
            else -> null
        }
        val paragraphProperties = alignment?.let {
            "<style:paragraph-properties fo:text-align=\"$it\"/>"
        }.orEmpty()
        return "<style:style style:name=\"$name\" style:family=\"table-cell\">" +
            cellProperties + paragraphProperties +
            "<style:text-properties fo:color=\"#${style.color.toHexRgb()}\"" +
            (if (style.bold) " fo:font-weight=\"bold\"" else "") +
            (if (style.italic) " fo:font-style=\"italic\"" else "") +
            "/></style:style>"
    }

    fun writeDeck(deck: Deck): ByteArray {
        val styles = StringBuilder()
        val pages = StringBuilder()

        deck.slides.forEachIndexed { index, slide ->
            styles.append(
                "<style:style style:name=\"dp$index\" style:family=\"drawing-page\">" +
                    "<style:drawing-page-properties draw:fill=\"solid\"" +
                    " draw:fill-color=\"#${slide.background.toHexRgb()}\"/></style:style>"
            )
            fun textStyle(name: String, size: Int, bold: Boolean): String {
                styles.append(
                    "<style:style style:name=\"$name\" style:family=\"paragraph\">" +
                        "<style:paragraph-properties fo:text-align=\"${textAlign(slide.align)}\"/>" +
                        "<style:text-properties fo:font-size=\"${size}pt\"" +
                        " fo:color=\"#${slide.textColor.toHexRgb()}\"" +
                        " style:font-name=\"${xmlEscape(officeFontName(slide.fontName))}\"" +
                        (if (bold) " fo:font-weight=\"bold\"" else "") +
                        "/></style:style>"
                )
                return name
            }

            pages.append(
                "<draw:page draw:name=\"Page${index + 1}\" draw:style-name=\"dp$index\">"
            )
            if (slide.title.isNotBlank()) {
                val style = textStyle("tt$index", slide.titleSize, true)
                pages.append(
                    frame(
                        x = "1.5cm", y = "2cm", width = "22cm", height = "3cm",
                        paragraphs = slide.title.split('\n')
                            .joinToString("") { "<text:p text:style-name=\"$style\">${odfText(it)}</text:p>" }
                    )
                )
            }
            if (slide.content.isNotBlank()) {
                val style = textStyle("tc$index", slide.contentSize, false)
                pages.append(
                    frame(
                        x = "1.5cm", y = "6cm", width = "22cm", height = "8cm",
                        paragraphs = slide.content.split('\n')
                            .joinToString("") { "<text:p text:style-name=\"$style\">${odfText(it)}</text:p>" }
                    )
                )
            }
            pages.append("</draw:page>")
        }

        val content = XML_DECL +
            "<office:document-content ${namespaces()} office:version=\"1.3\">" +
            "<office:automatic-styles>$styles</office:automatic-styles>" +
            "<office:body><office:presentation>$pages</office:presentation></office:body>" +
            "</office:document-content>"
        return pack(MIME_DECK, content)
    }

    private fun frame(x: String, y: String, width: String, height: String, paragraphs: String) =
        "<draw:frame svg:x=\"$x\" svg:y=\"$y\" svg:width=\"$width\" svg:height=\"$height\">" +
            "<draw:text-box>$paragraphs</draw:text-box></draw:frame>"

    private fun namespaces() =
        "xmlns:office=\"$NS_OFFICE\" xmlns:text=\"$NS_TEXT\" xmlns:table=\"$NS_TABLE\" " +
            "xmlns:draw=\"$NS_DRAW\" xmlns:style=\"$NS_STYLE\" xmlns:fo=\"$NS_FO\" " +
            "xmlns:svg=\"$NS_SVG\""

    /**
     * ODF replie les blancs comme HTML : au-delà du premier, chaque espace
     * doit passer par `<text:s/>`, sinon l'indentation disparaît à l'ouverture.
     */
    private fun odfText(text: String): String {
        val sb = StringBuilder()
        var index = 0
        while (index < text.length) {
            when (val ch = text[index]) {
                ' ' -> {
                    var run = 0
                    while (index < text.length && text[index] == ' ') {
                        run++; index++
                    }
                    sb.append(' ')
                    if (run > 1) sb.append("<text:s text:c=\"${run - 1}\"/>")
                }
                '\t' -> {
                    sb.append("<text:tab/>"); index++
                }
                '\n' -> {
                    sb.append("<text:line-break/>"); index++
                }
                else -> {
                    sb.append(xmlEscape(ch.toString())); index++
                }
            }
        }
        return sb.toString()
    }

    /** ODF préfixe ses références de cellules par un point : `SUM([.A1:.A5])`. */
    private fun odfFormula(formula: String): String =
        Regex("(?<![A-Za-z0-9_.\\[])(\\$?[A-Z]{1,3}\\$?[0-9]{1,7})(?![A-Za-z0-9_(])")
            .replace(formulaToEnglish(formula)) { "[.${it.value}]" }

    private fun textAlign(align: Int) = when (align) {
        1 -> "center"
        2 -> "end"
        3 -> "justify"
        else -> "start"
    }

    private fun pack(mime: String, content: String): ByteArray = ZipBuilder()
        .add("content.xml", content)
        .add("styles.xml", stylesXml())
        .add("META-INF/manifest.xml", manifest(mime))
        // `mimetype` doit être la première entrée et rester non compressée,
        // sinon LibreOffice ne reconnaît pas le type du document.
        .addStoredFirst("mimetype", mime)
        .build()

    private fun stylesXml() = XML_DECL +
        "<office:document-styles ${namespaces()} office:version=\"1.3\">" +
        "<office:styles/></office:document-styles>"

    private fun manifest(mime: String) = XML_DECL + """
        <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">
        <manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/>
        <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
        <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
        </manifest:manifest>
    """.trimIndent()

    // ------------------------------------------------------------ lecture

    fun readText(bytes: ByteArray, title: String = "Document"): TextDocument {
        val content = contentOf(bytes)
        val paragraphs = ArrayList<TextParagraph>()
        val parser = newPullParser(content)
        val buffer = StringBuilder()
        var inBody = false
        var depth = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "text" -> inBody = true
                    "p", "h" -> if (inBody) {
                        depth++; buffer.setLength(0)
                    }
                    "tab" -> if (depth > 0) buffer.append('\t')
                    "line-break" -> if (depth > 0) buffer.append('\n')
                    "s" -> if (depth > 0) {
                        repeat(parser.attr("c")?.toIntOrNull() ?: 1) { buffer.append(' ') }
                    }
                }
                XmlPullParser.TEXT -> if (depth > 0) buffer.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "p", "h" -> if (depth > 0) {
                        depth--
                        paragraphs.add(TextParagraph(listOf(TextRun(buffer.toString()))))
                        buffer.setLength(0)
                    }
                }
            }
            event = parser.next()
        }
        return TextDocument(title, paragraphs.ifEmpty { listOf(TextParagraph()) })
    }

    fun readSheet(bytes: ByteArray, title: String = "Classeur"): Workbook {
        val content = contentOf(bytes)
        val sheets = ArrayList<Sheet>()
        val parser = newPullParser(content)

        var cells = LinkedHashMap<String, String>()
        var name = ""
        var row = -1
        var column = 0
        var maxColumn = 0
        var repeatCell = 1
        var formula: String? = null
        val buffer = StringBuilder()
        var inCellText = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "table" -> {
                        cells = LinkedHashMap()
                        name = parser.attr("name").orEmpty()
                        row = -1; maxColumn = 0
                    }
                    "table-row" -> {
                        row++; column = 0
                    }
                    "table-cell", "covered-table-cell" -> {
                        repeatCell = parser.attr("number-columns-repeated")?.toIntOrNull() ?: 1
                        // Une feuille ODF se termine souvent par des milliers de
                        // cellules vides répétées : inutile de les matérialiser.
                        if (repeatCell > 1024) repeatCell = 1
                        formula = parser.attr("formula")
                        buffer.setLength(0)
                    }
                    "p" -> inCellText = row >= 0
                    "line-break" -> if (inCellText) buffer.append('\n')
                    "tab" -> if (inCellText) buffer.append('\t')
                }
                XmlPullParser.TEXT -> if (inCellText) buffer.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "p" -> inCellText = false
                    "table-cell", "covered-table-cell" -> {
                        val text = formula?.let { "=" + formulaToFrench(fromOdfFormula(it)) }
                            ?: buffer.toString()
                        repeat(repeatCell) {
                            if (text.isNotEmpty() && row >= 0) {
                                cells[CellRef.key(row, column)] = text
                            }
                            column++
                        }
                        maxColumn = maxOf(maxColumn, column)
                        formula = null
                        buffer.setLength(0)
                    }
                    "table" -> sheets.add(
                        Sheet(
                            name = name.ifBlank { "Feuille${sheets.size + 1}" },
                            cells = cells,
                            columns = maxOf(maxColumn, 12),
                            rows = maxOf(row + 1, 40)
                        )
                    )
                }
            }
            event = parser.next()
        }
        if (sheets.isEmpty()) throw FormatException("Ce fichier ODF ne contient aucune feuille")
        return Workbook(title, sheets)
    }

    private fun fromOdfFormula(formula: String): String = formula
        .removePrefix("of:").removePrefix("=")
        .replace("[.", "").replace("]", "")

    fun readDeck(bytes: ByteArray, title: String = "Présentation"): Deck {
        val content = contentOf(bytes)
        val slides = ArrayList<SlideModel>()
        val parser = newPullParser(content)

        var blocks = ArrayList<String>()
        val buffer = StringBuilder()
        var inPage = false
        var inParagraph = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "page" -> {
                        inPage = true; blocks = ArrayList()
                    }
                    "text-box" -> buffer.setLength(0)
                    "p" -> if (inPage) {
                        inParagraph = true
                    }
                    "line-break" -> if (inParagraph) buffer.append('\n')
                }
                XmlPullParser.TEXT -> if (inParagraph) buffer.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "p" -> if (inParagraph) {
                        inParagraph = false; buffer.append('\n')
                    }
                    "text-box" -> {
                        val text = buffer.toString().trim()
                        if (text.isNotEmpty()) blocks.add(text)
                        buffer.setLength(0)
                    }
                    "page" -> {
                        if (blocks.isNotEmpty()) {
                            slides.add(
                                SlideModel(
                                    title = blocks.first(),
                                    content = blocks.drop(1).joinToString("\n")
                                )
                            )
                        }
                        inPage = false
                    }
                }
            }
            event = parser.next()
        }
        if (slides.isEmpty()) throw FormatException("Ce fichier ODF ne contient aucune diapositive")
        return Deck(title, slides)
    }

    private fun contentOf(bytes: ByteArray): ByteArray =
        unzip(bytes)["content.xml"] ?: throw FormatException("Archive OpenDocument sans content.xml")
}
