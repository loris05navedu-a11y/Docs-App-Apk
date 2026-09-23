package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser

/**
 * OpenDocument (.odt, .ods, .odp), le format natif de LibreOffice et
 * OpenOffice. Même principe qu'OOXML — une archive de XML — mais avec ses
 * propres espaces de noms et une entrée `mimetype` non compressée en tête.
 */
object Odf {

    /** Bride une plage répétée porteuse de contenu, cas rare mais possible. */
    private const val MAX_REPEAT = 256

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
        val styles = StringBuilder()
        val body = StringBuilder()
        var counter = 0
        val lineHeight = if (document.lineSpacing != 100) " fo:line-height=\"${document.lineSpacing}%\"" else ""

        fun paragraphXml(paragraph: TextParagraph): String {
            val index = counter++
            val paragraphStyle = "P$index"
            val indent = if (paragraph.indent > 0) " fo:margin-left=\"${"%.2f".format(java.util.Locale.US, paragraph.indent * 1.27)}cm\"" else ""
            styles.append(
                "<style:style style:name=\"$paragraphStyle\" style:family=\"paragraph\">" +
                    "<style:paragraph-properties fo:text-align=\"${textAlign(paragraph.align)}\"$indent$lineHeight/>" +
                    "</style:style>"
            )
            val spans = paragraph.runs.mapIndexed { position, run ->
                val name = "T${index}_$position"
                styles.append(
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
                        when (run.baseline) {
                            1 -> " style:text-position=\"super 58%\""
                            -1 -> " style:text-position=\"sub 58%\""
                            else -> ""
                        } +
                        "/></style:style>"
                )
                "<text:span text:style-name=\"$name\">${odfText(run.text)}</text:span>"
            }.joinToString("")
            return if (paragraph.heading in 1..3) {
                "<text:h text:style-name=\"$paragraphStyle\" text:outline-level=\"${paragraph.heading}\">$spans</text:h>"
            } else {
                "<text:p text:style-name=\"$paragraphStyle\">$spans</text:p>"
            }
        }

        var tableIndex = 0
        document.blocks.ifEmpty { listOf(TextParagraph()) }.forEach { block ->
            when (block) {
                is TextParagraph -> body.append(paragraphXml(block))
                is TextTable -> {
                    val table = block.normalized()
                    val name = "Tableau${++tableIndex}"
                    body.append("<table:table table:name=\"$name\" table:style-name=\"$name\">")
                    styles.append(
                        "<style:style style:name=\"$name\" style:family=\"table\">" +
                            "<style:table-properties style:width=\"16cm\" table:align=\"margins\"/></style:style>"
                    )
                    body.append("<table:table-column table:number-columns-repeated=\"${table.columnCount}\"/>")
                    table.rows.forEachIndexed { rowIndex, row ->
                        if (rowIndex == 0 && table.headerRow) body.append("<table:table-header-rows>")
                        body.append("<table:table-row>")
                        var column = 0
                        row.cells.forEach { cell ->
                            if (cell.mergedAbove) {
                                body.append("<table:covered-table-cell/>")
                                repeat(cell.colSpan - 1) { body.append("<table:covered-table-cell/>") }
                            } else {
                                val cellStyle = "C${tableIndex}_${rowIndex}_$column"
                                styles.append(
                                    "<style:style style:name=\"$cellStyle\" style:family=\"table-cell\">" +
                                        "<style:table-cell-properties fo:padding=\"0.1cm\" fo:border=\"0.5pt solid #a0a7b4\"" +
                                        (if (cell.fill != 0L) " fo:background-color=\"#${cell.fill.toHexRgb()}\"" else "") +
                                        "/></style:style>"
                                )
                                val rowSpan = 1 + table.rows.drop(rowIndex + 1)
                                    .takeWhile { cellStartingAt(it, column)?.mergedAbove == true }.size
                                body.append("<table:table-cell table:style-name=\"$cellStyle\" office:value-type=\"string\"")
                                if (cell.colSpan > 1) body.append(" table:number-columns-spanned=\"${cell.colSpan}\"")
                                if (rowSpan > 1) body.append(" table:number-rows-spanned=\"$rowSpan\"")
                                body.append(">")
                                cell.paragraphs.ifEmpty { listOf(TextParagraph()) }
                                    .forEach { body.append(paragraphXml(it)) }
                                body.append("</table:table-cell>")
                                repeat(cell.colSpan - 1) { body.append("<table:covered-table-cell/>") }
                            }
                            column += cell.colSpan
                        }
                        body.append("</table:table-row>")
                        if (rowIndex == 0 && table.headerRow) body.append("</table:table-header-rows>")
                    }
                    body.append("</table:table>")
                }
            }
        }

        val content = XML_DECL +
            "<office:document-content ${namespaces()} office:version=\"1.3\">" +
            "<office:automatic-styles>$styles</office:automatic-styles>" +
            "<office:body><office:text>$body</office:text></office:body>" +
            "</office:document-content>"
        return pack(MIME_TEXT, content)
    }

    private fun cellStartingAt(row: TableRow, column: Int): TableCell? {
        var position = 0
        for (cell in row.cells) {
            if (position == column) return cell
            position += cell.colSpan
            if (position > column) return null
        }
        return null
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
            fun lines(text: String, style: String, bullets: Boolean) = text.split('\n').joinToString("") {
                val line = if (bullets && it.isNotBlank()) "• $it" else it
                "<text:p text:style-name=\"$style\">${odfText(line)}</text:p>"
            }
            val section = slide.layout == SlideLayout.SECTION
            if (slide.title.isNotBlank()) {
                val style = textStyle("tt$index", slide.titleSize, true)
                pages.append(
                    frame(
                        x = "1.5cm", y = if (section) "5cm" else "2cm", width = "22cm", height = "3cm",
                        paragraphs = lines(slide.title, style, false),
                        presentationClass = "title"
                    )
                )
            }
            if (slide.layout == SlideLayout.TWO_COLUMNS) {
                val style = textStyle("tc$index", slide.contentSize, false)
                pages.append(frame("1.5cm", "6cm", "10.6cm", "8cm", lines(slide.content, style, slide.bullets), "outline"))
                pages.append(frame("12.9cm", "6cm", "10.6cm", "8cm", lines(slide.secondContent, style, slide.bullets), "outline"))
            } else if (slide.content.isNotBlank()) {
                val style = textStyle("tc$index", slide.contentSize, false)
                pages.append(
                    frame(
                        x = "1.5cm", y = if (section) "8.5cm" else "6cm", width = "22cm", height = if (section) "3cm" else "8cm",
                        paragraphs = lines(slide.content, style, slide.bullets && !section),
                        presentationClass = if (section) "subtitle" else "outline"
                    )
                )
            }
            if (deck.footer.isNotBlank()) {
                val style = textStyle("tf$index", 12, false)
                pages.append(frame("1.5cm", "17.3cm", "15cm", "1cm", lines(deck.footer, style, false), "footer"))
            }
            if (deck.slideNumbers) {
                val style = textStyle("tn$index", 12, false)
                pages.append(frame("20cm", "17.3cm", "3.5cm", "1cm", lines("${index + 1}", style, false), "page-number"))
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

    private fun frame(x: String, y: String, width: String, height: String, paragraphs: String, presentationClass: String) =
        "<draw:frame presentation:class=\"$presentationClass\" svg:x=\"$x\" svg:y=\"$y\" svg:width=\"$width\" svg:height=\"$height\">" +
            "<draw:text-box>$paragraphs</draw:text-box></draw:frame>"

    private fun namespaces() =
        "xmlns:office=\"$NS_OFFICE\" xmlns:text=\"$NS_TEXT\" xmlns:table=\"$NS_TABLE\" " +
            "xmlns:draw=\"$NS_DRAW\" xmlns:style=\"$NS_STYLE\" xmlns:fo=\"$NS_FO\" " +
            "xmlns:svg=\"$NS_SVG\" xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\""

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

    fun readText(bytes: ByteArray, title: String = "Document"): TextDocument =
        OdfTextReader.read(unzip(bytes), title)

    fun readSheet(bytes: ByteArray, title: String = "Classeur"): Workbook {
        val content = contentOf(bytes)
        val sheets = ArrayList<Sheet>()
        val parser = newPullParser(content)

        var cells = LinkedHashMap<String, String>()
        var name = ""
        var row = 0
        var column = 0
        var maxColumn = 0
        var lastRow = 0
        var repeatCell = 1
        var repeatRow = 1
        var rowHasContent = false
        var rowCells = LinkedHashMap<Int, String>()
        var formula: String? = null
        var typedValue: String? = null
        val buffer = StringBuilder()
        var paragraphsInCell = 0
        var inCellText = false
        var inTable = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "table" -> if (parser.name.startsWith("table")) {
                        cells = LinkedHashMap()
                        name = parser.attr("name").orEmpty()
                        row = 0; maxColumn = 0; lastRow = 0
                        inTable = true
                    }
                    "table-row" -> if (inTable) {
                        column = 0
                        repeatRow = (parser.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        rowHasContent = false
                        rowCells = LinkedHashMap()
                    }
                    "table-cell", "covered-table-cell" -> {
                        repeatCell = (parser.attr("number-columns-repeated")?.toIntOrNull() ?: 1)
                            .coerceAtLeast(1)
                        formula = parser.attr("formula")
                        typedValue = cellValue(parser)
                        buffer.setLength(0)
                        paragraphsInCell = 0
                    }
                    "p" -> if (inTable) {
                        if (paragraphsInCell++ > 0) buffer.append('\n')
                        inCellText = true
                    }
                    "s" -> if (inCellText) buffer.append(" ".repeat((parser.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 200)))
                    "line-break" -> if (inCellText) buffer.append('\n')
                    "tab" -> if (inCellText) buffer.append('\t')
                    // Un commentaire contient lui aussi des paragraphes : sans
                    // cela, son texte se collait à la valeur de la cellule.
                    "annotation", "shapes", "named-expressions", "database-ranges" -> parser.skipSubtree()
                }
                XmlPullParser.TEXT -> if (inCellText) buffer.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "p" -> inCellText = false
                    "table-cell", "covered-table-cell" -> if (inTable) {
                        val text = formula?.let { "=" + formulaToFrench(fromOdfFormula(it)) }
                            ?: typedValue
                            ?: buffer.toString()
                        if (text.isNotEmpty()) {
                            repeat(repeatCell.coerceAtMost(MAX_REPEAT)) {
                                rowCells[column] = text
                                column++
                            }
                            rowHasContent = true
                        } else {
                            // Une feuille ODF se termine par des milliers de
                            // cellules vides répétées : on saute la plage sans
                            // la matérialiser ni élargir la feuille.
                            column += repeatCell
                        }
                        formula = null
                        typedValue = null
                        buffer.setLength(0)
                    }
                    "table-row" -> if (inTable) {
                        if (rowHasContent) {
                            // Des lignes identiques consécutives sont écrites une
                            // seule fois avec un compteur : il faut les recopier.
                            repeat(repeatRow.coerceAtMost(MAX_REPEAT)) {
                                rowCells.forEach { (c, value) -> cells[CellRef.key(row, c)] = value }
                                row++
                            }
                            maxColumn = maxOf(maxColumn, (rowCells.keys.maxOrNull() ?: -1) + 1)
                            lastRow = row
                        } else {
                            row += repeatRow
                        }
                    }
                    "table" -> if (inTable && parser.name.startsWith("table")) {
                        sheets.add(
                            Sheet(
                                name = name.ifBlank { "Feuille${sheets.size + 1}" },
                                cells = cells,
                                columns = maxOf(maxColumn, 12),
                                rows = maxOf(lastRow, 40)
                            )
                        )
                        inTable = false
                    }
                }
            }
            event = parser.next()
        }
        if (sheets.isEmpty()) throw FormatException("Ce fichier ODF ne contient aucune feuille")
        return Workbook(title, sheets)
    }

    /**
     * Valeur exacte d'une cellule typée. Le texte affiché dépend de la langue
     * du fichier (« 1,5 » ou « 1.5 », « 15/03/2024 » ou « 3/15/24 ») ; la
     * valeur brute, elle, est normalisée.
     */
    private fun cellValue(parser: XmlPullParser): String? = when (parser.attr("value-type")) {
        "float", "percentage", "currency" -> parser.attr("value")?.let { raw ->
            raw.toDoubleOrNull()?.let { trimNumber(it) } ?: raw
        }
        "boolean" -> parser.attr("boolean-value")?.let { if (it == "true") "VRAI" else "FAUX" }
        "date" -> parser.attr("date-value")?.let { iso ->
            val date = iso.substringBefore('T').split('-')
            if (date.size == 3) "${date[2]}/${date[1]}/${date[0]}" else iso
        }
        else -> null
    }

    private fun trimNumber(value: Double): String =
        if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
        else java.math.BigDecimal(value).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    // Formule ODF vers formule de l'app : « of:=SUM([.A1:.B2]) » donne
    // « SUM(A1:B2) », « [$Ventes.D5] » donne « Ventes!D5 », et une feuille au
    // nom entre apostrophes garde ses apostrophes.
    internal fun fromOdfFormula(formula: String): String {
        // Le préfixe d'espace de noms (`of:`, `msoxl:`) n'existe pas toujours.
        val body = Regex("^[A-Za-z]+:(?==)").replace(formula.trim(), "").removePrefix("=")
        val out = StringBuilder()
        var index = 0
        while (index < body.length) {
            val ch = body[index]
            when {
                ch == '"' -> {
                    val end = body.indexOf('"', index + 1).let { if (it < 0) body.length - 1 else it }
                    out.append(body, index, end + 1)
                    index = end + 1
                }
                ch == '[' -> {
                    val end = body.indexOf(']', index + 1).let { if (it < 0) body.length else it }
                    out.append(odfReference(body.substring(index + 1, end)))
                    index = end + 1
                }
                else -> {
                    out.append(ch); index++
                }
            }
        }
        return out.toString()
    }

    // Une référence entre crochets, sans eux : « .A1 », « $Feuille.A1:.B2 », « 'Nom'.A1 ».
    private fun odfReference(reference: String): String {
        val parts = reference.split(':').map { part ->
            val clean = part.removePrefix("$")
            val dot = clean.lastIndexOf('.')
            if (dot < 0) {
                clean.replace("$", "")
            } else {
                val sheet = clean.substring(0, dot).removePrefix("$")
                val cell = clean.substring(dot + 1).replace("$", "")
                if (sheet.isEmpty()) cell else "$sheet!$cell"
            }
        }
        // `Feuille!A1:Feuille!B2` se dit `Feuille!A1:B2`.
        if (parts.size == 2 && parts[0].contains('!') && parts[1].contains('!') &&
            parts[0].substringBefore('!') == parts[1].substringBefore('!')
        ) return "${parts[0]}:${parts[1].substringAfter('!')}"
        return parts.joinToString(":")
    }

    fun readDeck(bytes: ByteArray, title: String = "Présentation"): Deck {
        val parts = unzip(bytes)
        val content = parts["content.xml"] ?: throw FormatException("Archive OpenDocument sans content.xml")

        // Couleur de fond des styles de page, et style de chaque page maîtresse.
        val pageFills = HashMap<String, Long>()
        val masterStyles = HashMap<String, String>()
        listOfNotNull(parts["styles.xml"], content).forEach { xml ->
            runCatching {
                val parser = newPullParser(xml)
                var styleName: String? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) when (parser.localName) {
                        "style" -> styleName = parser.attr("name")
                        "drawing-page-properties" -> if (styleName != null && parser.attr("fill") == "solid") {
                            parser.attr("fill-color")?.let { pageFills[styleName!!] = hexToArgb(it, 0xFFFFFFFFL) }
                        }
                        "master-page" -> {
                            val master = parser.attr("name")
                            val style = parser.attr("style-name")
                            if (master != null && style != null) masterStyles[master] = style
                        }
                    }
                    event = parser.next()
                }
            }
        }

        val slides = ArrayList<SlideModel>()
        val parser = newPullParser(content)
        var background: Long? = null
        var titleText = ""
        val bodyBlocks = ArrayList<String>()
        val bodyClasses = ArrayList<String?>()
        var footer = ""
        var numbered = false
        val notes = StringBuilder()
        var frameClass: String? = null
        var inNotes = false
        val paragraph = StringBuilder()
        val frameText = ArrayList<String>()
        var inParagraph = false
        var tableRows: ArrayList<String>? = null
        var tableRow: ArrayList<String>? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "page" -> if (parser.name.startsWith("draw")) {
                        titleText = ""; bodyBlocks.clear(); bodyClasses.clear(); notes.setLength(0)
                        background = parser.attr("style-name")?.let { pageFills[it] }
                            ?: parser.attr("master-page-name")?.let { masterStyles[it] }?.let { pageFills[it] }
                    }
                    "notes" -> inNotes = true
                    "frame", "custom-shape", "rect", "ellipse" -> if (!inNotes) {
                        frameClass = parser.attr("class")
                        frameText.clear()
                    }
                    "table" -> if (!inNotes) tableRows = ArrayList()
                    "table-row" -> tableRow = ArrayList()
                    "p", "h" -> {
                        inParagraph = true; paragraph.setLength(0)
                    }
                    "s" -> if (inParagraph) paragraph.append(' ')
                    "tab" -> if (inParagraph) paragraph.append('\t')
                    "line-break" -> if (inParagraph) paragraph.append('\n')
                    // Champs d'en-tête et de pied de page : le texte du masque,
                    // pas celui de la diapositive.
                    "page-number", "date-time", "footer", "header", "annotation", "desc", "title" ->
                        if (parser.name.startsWith("text:") || parser.name.startsWith("office:") ||
                            parser.name.startsWith("presentation:") || parser.name.startsWith("svg:")
                        ) parser.skipSubtree()
                }
                XmlPullParser.TEXT -> if (inParagraph) paragraph.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "p", "h" -> if (inParagraph) {
                        inParagraph = false
                        when {
                            inNotes -> notes.append(paragraph).append('\n')
                            tableRow != null -> tableRow!!.add(paragraph.toString())
                            else -> frameText.add(paragraph.toString())
                        }
                    }
                    "table-row" -> tableRow?.let { row ->
                        tableRows?.add(row.joinToString(" | "))
                        tableRow = null
                    }
                    "table" -> tableRows?.let { rows ->
                        if (rows.isNotEmpty()) bodyBlocks.add(rows.joinToString("\n"))
                        tableRows = null
                    }
                    "frame", "custom-shape", "rect", "ellipse" -> if (!inNotes) {
                        val text = frameText.joinToString("\n").trim()
                        when {
                            frameClass == "footer" -> if (footer.isEmpty()) footer = text
                            frameClass == "page-number" -> numbered = true
                            frameClass == "date-time" || frameClass == "header" -> Unit
                            text.isEmpty() -> Unit
                            (frameClass == "title" || frameClass == null && titleText.isEmpty() && bodyBlocks.isEmpty()) &&
                                titleText.isEmpty() -> titleText = text
                            else -> {
                                bodyBlocks.add(text)
                                bodyClasses.add(frameClass)
                            }
                        }
                        frameText.clear()
                        frameClass = null
                    }
                    "notes" -> inNotes = false
                    "page" -> if (parser.name.startsWith("draw")) {
                        // Une diapositive sans texte (une image, un schéma) reste
                        // une diapositive : l'omettre décalait toute la présentation.
                        val bg = background ?: 0xFFFFFFFFL
                        // Des puces écrites en toutes lettres (« • ») redeviennent
                        // l'option « puces » ; deux blocs de plan côte à côte, deux colonnes.
                        val lines = bodyBlocks.flatMap { it.split('\n') }.filter { it.isNotBlank() }
                        val bullets = lines.isNotEmpty() && lines.all { it.startsWith("• ") }
                        fun clean(text: String) = if (bullets) text.lines().joinToString("\n") { it.removePrefix("• ") } else text
                        val twoColumns = bodyBlocks.size == 2 && bodyClasses.all { it == "outline" }
                        slides.add(
                            SlideModel(
                                title = titleText,
                                content = if (twoColumns) clean(bodyBlocks[0]) else clean(bodyBlocks.joinToString("\n")),
                                secondContent = if (twoColumns) clean(bodyBlocks[1]) else "",
                                layout = when {
                                    twoColumns -> SlideLayout.TWO_COLUMNS
                                    bodyClasses.any { it == "subtitle" } -> SlideLayout.SECTION
                                    else -> SlideLayout.TITLE_AND_CONTENT
                                },
                                bullets = bullets,
                                background = bg,
                                textColor = readableOn(bg),
                                notes = notes.toString().trim()
                            )
                        )
                    }
                }
            }
            event = parser.next()
        }
        if (slides.isEmpty()) throw FormatException("Ce fichier ODF ne contient aucune diapositive")
        return Deck(title, slides, footer = footer, slideNumbers = numbered)
    }

    private fun contentOf(bytes: ByteArray): ByteArray =
        unzip(bytes)["content.xml"] ?: throw FormatException("Archive OpenDocument sans content.xml")
}
