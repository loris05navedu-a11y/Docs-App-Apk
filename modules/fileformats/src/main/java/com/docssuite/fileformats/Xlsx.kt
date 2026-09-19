package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser

/**
 * SpreadsheetML (.xlsx) : classeur complet avec chaînes partagées, table de
 * styles (gras, italique, couleur de texte, remplissage, alignement) et
 * formules traduites en anglais comme l'exige le format.
 */
object Xlsx {

    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"

    /** Valeur calculée d'une formule, mise en cache dans le fichier. */
    fun interface Evaluator {
        fun valueOf(sheet: Sheet, ref: String): String?
    }

    // ------------------------------------------------------------ écriture

    fun write(workbook: Workbook, evaluator: Evaluator? = null): ByteArray {
        val sheets = workbook.sheets.ifEmpty { listOf(Sheet()) }
        val strings = SharedStrings()
        val styles = StyleTable()
        val sheetXml = sheets.map { sheetXml(it, strings, styles, evaluator) }

        val zip = ZipBuilder()
            .add("[Content_Types].xml", contentTypes(sheets.size))
            .add("_rels/.rels", rootRels())
            .add("docProps/core.xml", coreProps(workbook.title))
            .add("docProps/app.xml", appProps())
            .add("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            .add("xl/workbook.xml", workbookXml(sheets))
            .add("xl/styles.xml", styles.toXml())
            .add("xl/sharedStrings.xml", strings.toXml())
        sheetXml.forEachIndexed { index, xml -> zip.add("xl/worksheets/sheet${index + 1}.xml", xml) }
        return zip.build()
    }

    private fun contentTypes(sheetCount: Int): String {
        val overrides = (1..sheetCount).joinToString("") {
            "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        return XML_DECL + """
            <Types xmlns="$NS_CT">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            $overrides
            <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
            <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
            </Types>
        """.trimIndent()
    }

    private fun rootRels() = XML_DECL + """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="$NS_REL/officeDocument" Target="xl/workbook.xml"/>
        <Relationship Id="rId2" Type="$NS_REL/metadata/core-properties" Target="docProps/core.xml"/>
        <Relationship Id="rId3" Type="$NS_REL/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookRels(sheetCount: Int): String {
        val sheets = (1..sheetCount).joinToString("") {
            "<Relationship Id=\"rId$it\" Type=\"$NS_REL/worksheet\" Target=\"worksheets/sheet$it.xml\"/>"
        }
        return XML_DECL + """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            $sheets
            <Relationship Id="rId${sheetCount + 1}" Type="$NS_REL/styles" Target="styles.xml"/>
            <Relationship Id="rId${sheetCount + 2}" Type="$NS_REL/sharedStrings" Target="sharedStrings.xml"/>
            </Relationships>
        """.trimIndent()
    }

    private fun workbookXml(sheets: List<Sheet>): String {
        val entries = sheets.mapIndexed { index, sheet ->
            val name = sheet.name.ifBlank { "Feuille${index + 1}" }.take(31)
            "<sheet name=\"${xmlEscape(name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>"
        }.joinToString("")
        return XML_DECL +
            "<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\"><sheets>$entries</sheets></workbook>"
    }

    private fun sheetXml(
        sheet: Sheet,
        strings: SharedStrings,
        styles: StyleTable,
        evaluator: Evaluator?
    ): String {
        val byRow = sortedMapOf<Int, MutableList<Pair<Int, String>>>()
        val keys = LinkedHashSet<String>().apply {
            addAll(sheet.cells.keys)
            addAll(sheet.styles.keys.filter { !(sheet.styles[it]?.isDefault ?: true) })
        }
        keys.forEach { ref ->
            val position = CellRef.parse(ref) ?: return@forEach
            byRow.getOrPut(position.first) { ArrayList() }.add(position.second to ref)
        }

        val body = StringBuilder()
        byRow.forEach { (row, cells) ->
            body.append("<row r=\"${row + 1}\">")
            cells.sortedBy { it.first }.forEach { (column, ref) ->
                body.append(cellXml(sheet, ref, column, row, strings, styles, evaluator))
            }
            body.append("</row>")
        }

        val lastColumn = CellRef.columnLabel((sheet.columns - 1).coerceAtLeast(0))
        val dimension = "<dimension ref=\"A1:$lastColumn${sheet.rows.coerceAtLeast(1)}\"/>"
        return XML_DECL + "<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">$dimension" +
            "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>" +
            "<sheetFormatPr defaultRowHeight=\"15\"/>" +
            "<sheetData>$body</sheetData></worksheet>"
    }

    private fun cellXml(
        sheet: Sheet,
        ref: String,
        column: Int,
        row: Int,
        strings: SharedStrings,
        styles: StyleTable,
        evaluator: Evaluator?
    ): String {
        val raw = sheet.cells[ref].orEmpty()
        val style = sheet.styles[ref]
        val styleAttr = if (style == null || style.isDefault) "" else " s=\"${styles.indexOf(style)}\""
        val reference = CellRef.key(row, column)

        if (raw.startsWith("=")) {
            val formula = xmlEscape(formulaToEnglish(raw.removePrefix("=")))
            val cached = evaluator?.valueOf(sheet, ref)
            val number = cached?.replace(',', '.')?.toDoubleOrNull()
            return when {
                number != null -> "<c r=\"$reference\"$styleAttr><f>$formula</f><v>${trim(number)}</v></c>"
                cached != null -> "<c r=\"$reference\"$styleAttr t=\"str\"><f>$formula</f><v>${xmlEscape(cached)}</v></c>"
                else -> "<c r=\"$reference\"$styleAttr><f>$formula</f></c>"
            }
        }

        if (raw.isEmpty()) return "<c r=\"$reference\"$styleAttr/>"

        val number = raw.replace(',', '.').toDoubleOrNull()
        // Attention : "0123" ou "1 2" ne doivent pas devenir des nombres, et
        // "+33..." non plus — toDoubleOrNull s'en charge, mais pas des zéros
        // de tête, qu'Excel effacerait silencieusement.
        val looksNumeric = number != null && !(raw.length > 1 && raw.startsWith("0") && !raw.startsWith("0."))
        return if (looksNumeric) {
            "<c r=\"$reference\"$styleAttr><v>${trim(number!!)}</v></c>"
        } else {
            "<c r=\"$reference\"$styleAttr t=\"s\"><v>${strings.indexOf(raw)}</v></c>"
        }
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private class SharedStrings {
        private val index = LinkedHashMap<String, Int>()
        fun indexOf(text: String): Int = index.getOrPut(text) { index.size }
        fun toXml(): String {
            val items = index.keys.joinToString("") {
                "<si><t xml:space=\"preserve\">${xmlEscape(it)}</t></si>"
            }
            return XML_DECL + "<sst xmlns=\"$NS_MAIN\" count=\"${index.size}\" " +
                "uniqueCount=\"${index.size}\">$items</sst>"
        }
    }

    private class StyleTable {
        private val entries = LinkedHashMap<CellStyle, Int>()

        fun indexOf(style: CellStyle): Int = entries.getOrPut(style) { entries.size + 1 }

        fun toXml(): String {
            val ordered = entries.entries.sortedBy { it.value }.map { it.key }

            val fonts = StringBuilder("<font><sz val=\"11\"/><color rgb=\"FF1A1A1A\"/><name val=\"Calibri\"/></font>")
            ordered.forEach { style ->
                fonts.append("<font>")
                if (style.bold) fonts.append("<b/>")
                if (style.italic) fonts.append("<i/>")
                fonts.append("<sz val=\"11\"/><color rgb=\"FF${style.color.toHexRgb()}\"/>")
                fonts.append("<name val=\"Calibri\"/></font>")
            }

            // Excel impose que les remplissages 0 et 1 soient « none » et
            // « gray125 » ; décaler ces deux-là corrompt le classeur.
            val fills = StringBuilder(
                "<fill><patternFill patternType=\"none\"/></fill>" +
                    "<fill><patternFill patternType=\"gray125\"/></fill>"
            )
            val fillIndex = HashMap<Long, Int>()
            ordered.forEach { style ->
                if (style.background != 0L && style.background !in fillIndex) {
                    fillIndex[style.background] = fillIndex.size + 2
                    fills.append(
                        "<fill><patternFill patternType=\"solid\">" +
                            "<fgColor rgb=\"FF${style.background.toHexRgb()}\"/>" +
                            "<bgColor indexed=\"64\"/></patternFill></fill>"
                    )
                }
            }

            val cellXfs = StringBuilder(
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            )
            ordered.forEachIndexed { position, style ->
                val fill = fillIndex[style.background] ?: 0
                val alignment = horizontal(style.align)
                cellXfs.append("<xf numFmtId=\"0\" fontId=\"${position + 1}\" fillId=\"$fill\"")
                cellXfs.append(" borderId=\"0\" xfId=\"0\" applyFont=\"1\"")
                if (fill != 0) cellXfs.append(" applyFill=\"1\"")
                if (alignment == null) {
                    cellXfs.append("/>")
                } else {
                    cellXfs.append(" applyAlignment=\"1\">")
                    cellXfs.append("<alignment horizontal=\"$alignment\"/></xf>")
                }
            }

            return XML_DECL + "<styleSheet xmlns=\"$NS_MAIN\">" +
                "<fonts count=\"${ordered.size + 1}\">$fonts</fonts>" +
                "<fills count=\"${fillIndex.size + 2}\">$fills</fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"${ordered.size + 1}\">$cellXfs</cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "</styleSheet>"
        }

        private fun horizontal(align: Int) = when (align) {
            1 -> "left"
            2 -> "center"
            3 -> "right"
            else -> null
        }
    }

    private fun coreProps(title: String) = XML_DECL + """
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
         xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>${xmlEscape(title)}</dc:title><dc:creator>DocsApp Suite</dc:creator>
        </cp:coreProperties>
    """.trimIndent()

    private fun appProps() = XML_DECL + """
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
        <Application>DocsApp Suite</Application></Properties>
    """.trimIndent()

    // ------------------------------------------------------------ lecture

    fun read(bytes: ByteArray, title: String = "Classeur"): Workbook {
        val parts = unzip(bytes)
        val shared = parts["xl/sharedStrings.xml"]?.let { readSharedStrings(it) } ?: emptyList()
        val styles = parts["xl/styles.xml"]?.let { readStyles(it) } ?: emptyList()
        val relations = parseRelationships(parts["xl/_rels/workbook.xml.rels"])
        val names = parts["xl/workbook.xml"]?.let { readSheetNames(it) } ?: emptyList()

        val sheets = ArrayList<Sheet>()
        names.forEachIndexed { index, (name, relId) ->
            val target = relations[relId]?.removePrefix("/")?.removePrefix("xl/")
                ?: "worksheets/sheet${index + 1}.xml"
            val xml = parts["xl/$target"] ?: parts["xl/worksheets/sheet${index + 1}.xml"] ?: return@forEachIndexed
            sheets.add(readSheet(xml, name.ifBlank { "Feuille${index + 1}" }, shared, styles))
        }
        if (sheets.isEmpty()) {
            // Certains producteurs omettent les relations : on se rabat sur les
            // feuilles trouvées dans l'archive.
            parts.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
                .sorted()
                .forEachIndexed { index, path ->
                    sheets.add(readSheet(parts[path]!!, "Feuille${index + 1}", shared, styles))
                }
        }
        if (sheets.isEmpty()) throw FormatException("Ce .xlsx ne contient aucune feuille")
        return Workbook(title, sheets)
    }

    private fun readSheetNames(xml: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val parser = newPullParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.localName == "sheet") {
                out.add(parser.attr("name").orEmpty() to parser.attr("id").orEmpty())
            }
            event = parser.next()
        }
        return out
    }

    private fun readSharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        val parser = newPullParser(xml)
        val current = StringBuilder()
        var inItem = false
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "si" -> {
                        inItem = true; current.setLength(0)
                    }
                    "t" -> inText = inItem
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "t" -> inText = false
                    "si" -> {
                        out.add(current.toString()); inItem = false
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** Résout `cellXfs` → police / remplissage / alignement en un [CellStyle]. */
    private fun readStyles(xml: ByteArray): List<CellStyle> {
        val fonts = ArrayList<Triple<Boolean, Boolean, Long>>()
        val fills = ArrayList<Long>()
        val result = ArrayList<CellStyle>()

        val parser = newPullParser(xml)
        var section = ""
        var bold = false
        var italic = false
        var color = 0xFF1A1A1AL
        var fillColor = 0L
        var pattern = "none"
        var pendingFont = 0
        var pendingFill = 0
        var pendingAlign = 0
        var inCellXfs = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "fonts", "fills", "cellXfs", "cellStyleXfs" -> {
                        section = parser.localName
                        inCellXfs = section == "cellXfs"
                    }
                    "font" -> if (section == "fonts") {
                        bold = false; italic = false; color = 0xFF1A1A1AL
                    }
                    "b" -> if (section == "fonts") bold = parser.attr("val") != "0"
                    "i" -> if (section == "fonts") italic = parser.attr("val") != "0"
                    "color" -> if (section == "fonts") {
                        color = argbFromRgbAttribute(parser.attr("rgb"), 0xFF1A1A1AL)
                    }
                    "patternFill" -> if (section == "fills") {
                        pattern = parser.attr("patternType").orEmpty()
                        fillColor = 0L
                    }
                    "fgColor" -> if (section == "fills" && pattern == "solid") {
                        fillColor = argbFromRgbAttribute(parser.attr("rgb"), 0L)
                    }
                    "xf" -> if (inCellXfs) {
                        pendingFont = parser.attr("fontId")?.toIntOrNull() ?: 0
                        pendingFill = parser.attr("fillId")?.toIntOrNull() ?: 0
                        pendingAlign = 0
                        // Un <xf/> auto-fermant n'aura pas de END_TAG distinct
                        // détectable autrement : on l'enregistre ici et on le
                        // corrigera si un <alignment> suit.
                        result.add(buildStyle(fonts, fills, pendingFont, pendingFill, 0))
                    }
                    "alignment" -> if (inCellXfs && result.isNotEmpty()) {
                        pendingAlign = alignFromHorizontal(parser.attr("horizontal"))
                        result[result.size - 1] =
                            buildStyle(fonts, fills, pendingFont, pendingFill, pendingAlign)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "font" -> if (section == "fonts") fonts.add(Triple(bold, italic, color))
                    "fill" -> if (section == "fills") fills.add(if (pattern == "solid") fillColor else 0L)
                    "fonts", "fills", "cellXfs", "cellStyleXfs" -> {
                        if (parser.localName == "cellXfs") inCellXfs = false
                        section = ""
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun buildStyle(
        fonts: List<Triple<Boolean, Boolean, Long>>,
        fills: List<Long>,
        fontId: Int,
        fillId: Int,
        align: Int
    ): CellStyle {
        val font = fonts.getOrNull(fontId)
        return CellStyle(
            bold = font?.first ?: false,
            italic = font?.second ?: false,
            color = font?.third ?: 0xFF1A1A1AL,
            background = fills.getOrNull(fillId) ?: 0L,
            align = align
        )
    }

    private fun alignFromHorizontal(value: String?) = when (value) {
        "left" -> 1
        "center", "centerContinuous" -> 2
        "right" -> 3
        else -> 0
    }

    private fun argbFromRgbAttribute(rgb: String?, fallback: Long): Long {
        val clean = rgb?.trim() ?: return fallback
        return when (clean.length) {
            8 -> runCatching { clean.toLong(16) }.getOrDefault(fallback)
            6 -> runCatching { 0xFF000000L or clean.toLong(16) }.getOrDefault(fallback)
            else -> fallback
        }
    }

    private fun readSheet(
        xml: ByteArray,
        name: String,
        shared: List<String>,
        styles: List<CellStyle>
    ): Sheet {
        val cells = LinkedHashMap<String, String>()
        val formats = LinkedHashMap<String, CellStyle>()
        var maxRow = 0
        var maxColumn = 0

        val parser = newPullParser(xml)
        var ref = ""
        var type = ""
        var styleId = -1
        var formula: String? = null
        var value: String? = null
        var inValue = false
        var inFormula = false
        var inInlineText = false
        val buffer = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "c" -> {
                        ref = parser.attr("r").orEmpty()
                        type = parser.attr("t").orEmpty()
                        styleId = parser.attr("s")?.toIntOrNull() ?: -1
                        formula = null; value = null
                    }
                    "f" -> {
                        inFormula = true; buffer.setLength(0)
                    }
                    "v" -> {
                        inValue = true; buffer.setLength(0)
                    }
                    "t" -> if (type == "inlineStr") {
                        inInlineText = true; buffer.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (inValue || inFormula || inInlineText) buffer.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "f" -> {
                        formula = buffer.toString(); inFormula = false
                    }
                    "v" -> {
                        value = buffer.toString(); inValue = false
                    }
                    "t" -> if (inInlineText) {
                        value = buffer.toString(); inInlineText = false
                    }
                    "c" -> {
                        val position = CellRef.parse(ref)
                        if (position != null) {
                            maxRow = maxOf(maxRow, position.first + 1)
                            maxColumn = maxOf(maxColumn, position.second + 1)
                            val text = when {
                                formula != null -> "=" + formulaToFrench(formula!!)
                                type == "s" -> shared.getOrNull(value?.toIntOrNull() ?: -1).orEmpty()
                                type == "b" -> if (value == "1") "VRAI" else "FAUX"
                                else -> value.orEmpty()
                            }
                            if (text.isNotEmpty()) cells[ref] = text
                            styles.getOrNull(styleId)
                                ?.takeIf { !it.isDefault }
                                ?.let { formats[ref] = it }
                        }
                        ref = ""; type = ""; styleId = -1
                    }
                }
            }
            event = parser.next()
        }

        return Sheet(
            name = name,
            cells = cells,
            styles = formats,
            columns = maxOf(maxColumn, 12),
            rows = maxOf(maxRow, 40)
        )
    }
}
