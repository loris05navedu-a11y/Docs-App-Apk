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
        val styles = parts["xl/styles.xml"]?.let { runCatching { readStyles(it) }.getOrNull() } ?: ReadStyles.EMPTY
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
                out.add(parser.attr("name").orEmpty() to (parser.relationshipId() ?: parser.attr("id").orEmpty()))
            }
            event = parser.next()
        }
        return out
    }

    /** Un texte partagé peut être découpé en plusieurs portions mises en forme. */
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
                    // La prononciation phonétique (japonais) n'est pas le texte.
                    "rPh", "phoneticPr" -> parser.skipSubtree()
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

    /** Styles de cellule et, pour chacun, s'il affiche une date. */
    private class ReadStyles(val styles: List<CellStyle>, val dates: Set<Int>) {
        companion object {
            val EMPTY = ReadStyles(emptyList(), emptySet())
        }
    }

    /** Formats intégrés d'Excel qui affichent une date ou une heure. */
    private val builtinDateFormats = (14..22).toSet() + setOf(27, 30, 36, 45, 46, 47, 50, 57)

    /**
     * Un code de format affiche une date s'il contient un jour, une année, ou
     * des mois avec des heures — une fois retirés textes littéraux, couleurs
     * et caractères échappés.
     */
    private fun isDateFormat(code: String): Boolean {
        val bare = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\."), "").lowercase()
        if (bare.contains("general")) return false
        return bare.contains('d') || bare.contains('y') || bare.contains('j') ||
            (bare.contains('m') && bare.contains('h'))
    }

    /** Résout `cellXfs` → police / remplissage / alignement en un [CellStyle]. */
    private fun readStyles(xml: ByteArray): ReadStyles {
        val fonts = ArrayList<Triple<Boolean, Boolean, Long>>()
        val fills = ArrayList<Long>()
        val result = ArrayList<CellStyle>()
        val formats = HashMap<Int, String>()
        val dates = HashSet<Int>()

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
                    "fonts", "fills", "cellXfs", "cellStyleXfs", "numFmts", "dxfs" -> {
                        section = parser.localName
                        inCellXfs = section == "cellXfs"
                    }
                    "numFmt" -> if (section == "numFmts") {
                        val id = parser.attr("numFmtId")?.toIntOrNull()
                        val code = parser.attr("formatCode")
                        if (id != null && code != null) formats[id] = code
                    }
                    "font" -> if (section == "fonts") {
                        bold = false; italic = false; color = 0xFF1A1A1AL
                    }
                    "b" -> if (section == "fonts") bold = parser.attr("val") != "0" && parser.attr("val") != "false"
                    "i" -> if (section == "fonts") italic = parser.attr("val") != "0" && parser.attr("val") != "false"
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
                        val numFmt = parser.attr("numFmtId")?.toIntOrNull() ?: 0
                        if (numFmt in builtinDateFormats || formats[numFmt]?.let { isDateFormat(it) } == true) {
                            dates.add(result.size)
                        }
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
                    "fonts", "fills", "cellXfs", "cellStyleXfs", "numFmts", "dxfs" -> {
                        if (parser.localName == "cellXfs") inCellXfs = false
                        section = ""
                    }
                }
            }
            event = parser.next()
        }
        return ReadStyles(result, dates)
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

    /** Les fonctions récentes d'Excel sont écrites avec un préfixe de compatibilité. */
    internal fun cleanFormula(formula: String): String =
        formula.replace("_xlfn._xlws.", "").replace("_xlfn.", "").replace("_xlws.", "").replace("_xlpm.", "")

    /**
     * Décale les références relatives d'une formule, comme Excel quand il
     * recopie une formule partagée : `A1+B1` écrite en C1 devient `A2+B2`
     * en C2. Les parties marquées `$` ne bougent pas.
     */
    internal fun shiftFormula(formula: String, rows: Int, columns: Int): String {
        val reference = Regex("(?<![A-Za-z0-9_.\"])(\\$?)([A-Z]{1,3})(\\$?)(\\d{1,7})(?![A-Za-z0-9_(])")
        val out = StringBuilder()
        var quoted = false
        var segmentStart = 0
        fun flush(end: Int) {
            val segment = formula.substring(segmentStart, end)
            out.append(
                if (quoted) segment else reference.replace(segment) { m ->
                    val (colAbs, col, rowAbs, row) = m.destructured
                    val newCol = if (colAbs.isEmpty()) CellRef.columnIndex(col) + columns else CellRef.columnIndex(col)
                    val newRow = if (rowAbs.isEmpty()) row.toInt() + rows else row.toInt()
                    if (newCol < 0 || newRow < 1) "#REF!"
                    else "$colAbs${CellRef.columnLabel(newCol)}$rowAbs$newRow"
                }
            )
            segmentStart = end
        }
        formula.forEachIndexed { index, c ->
            if (c == '"') {
                flush(if (quoted) index + 1 else index)
                quoted = !quoted
            }
        }
        flush(formula.length)
        return out.toString()
    }

    /** Excel stocke 0,1+0,2 comme 0.30000000000000004 : on arrondit à sa précision de 15 chiffres. */
    private fun cleanNumber(raw: String): String {
        if (raw.length < 16) return raw
        val number = raw.toDoubleOrNull() ?: return raw
        return java.math.BigDecimal(number).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** Numéro de série Excel (jours depuis le 30/12/1899) → date lisible. */
    private fun serialToDate(raw: String): String? {
        val serial = raw.toDoubleOrNull() ?: return null
        if (serial < 1 || serial > 2958465) return null
        val days = kotlin.math.floor(serial).toLong()
        val date = java.time.LocalDate.of(1899, 12, 30).plusDays(days)
        val base = "%02d/%02d/%04d".format(date.dayOfMonth, date.monthValue, date.year)
        val fraction = serial - days
        if (fraction < 1e-9) return base
        val minutes = kotlin.math.round(fraction * 24 * 60).toInt()
        return "$base %02d:%02d".format(minutes / 60 % 24, minutes % 60)
    }

    /** Au-delà, une feuille n'est plus lisible sur un téléphone : on le dit plutôt que de figer l'app. */
    private const val MAX_ROWS = 100_000
    private const val MAX_COLUMNS = 702

    private fun readSheet(
        xml: ByteArray,
        name: String,
        shared: List<String>,
        styles: ReadStyles
    ): Sheet {
        val cells = LinkedHashMap<String, String>()
        val formats = LinkedHashMap<String, CellStyle>()
        var maxRow = 0
        var maxColumn = 0
        // Formules partagées : identifiant → (formule d'origine, sa ligne, sa colonne).
        val sharedFormulas = HashMap<String, Triple<String, Int, Int>>()

        val parser = newPullParser(xml)
        var row = 0
        var column = 0
        var seenRow = false
        var ref = ""
        var type = ""
        var styleId = -1
        var formula: String? = null
        var formulaType = ""
        var sharedIndex: String? = null
        var value: String? = null
        var inValue = false
        var inFormula = false
        var inInlineText = false
        val buffer = StringBuilder()
        val inline = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "row" -> {
                        // Sans numéro, une ligne suit la précédente.
                        row = parser.attr("r")?.toIntOrNull()?.minus(1) ?: if (seenRow) row + 1 else 0
                        seenRow = true
                        column = 0
                    }
                    "c" -> {
                        // L'adresse est facultative : sans elle, la cellule suit la précédente.
                        ref = parser.attr("r").orEmpty()
                        CellRef.parse(ref)?.let { column = it.second } ?: run { ref = CellRef.key(row, column) }
                        type = parser.attr("t").orEmpty()
                        styleId = parser.attr("s")?.toIntOrNull() ?: -1
                        formula = null; value = null; formulaType = ""; sharedIndex = null
                        inline.setLength(0)
                    }
                    "f" -> {
                        inFormula = true; buffer.setLength(0)
                        formulaType = parser.attr("t").orEmpty()
                        sharedIndex = parser.attr("si")
                    }
                    "v" -> {
                        inValue = true; buffer.setLength(0)
                    }
                    "t" -> if (type == "inlineStr") {
                        inInlineText = true; buffer.setLength(0)
                    }
                    "rPh", "extLst" -> parser.skipSubtree()
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
                        inline.append(buffer); inInlineText = false
                    }
                    "c" -> {
                        val position = CellRef.parse(ref)
                        if (position != null && position.first < MAX_ROWS && position.second < MAX_COLUMNS) {
                            val (r, c) = position
                            // Une formule partagée vide reprend celle de sa cellule
                            // d'origine, décalée de la distance qui les sépare.
                            var text = formula
                            if (formulaType == "shared" && sharedIndex != null) {
                                if (!text.isNullOrBlank()) {
                                    sharedFormulas[sharedIndex!!] = Triple(text!!, r, c)
                                } else {
                                    text = sharedFormulas[sharedIndex!!]?.let { (origin, originRow, originColumn) ->
                                        shiftFormula(origin, r - originRow, c - originColumn)
                                    }
                                }
                            }
                            val content = when {
                                !text.isNullOrBlank() -> "=" + formulaToFrench(cleanFormula(text!!))
                                type == "s" -> shared.getOrNull(value?.toIntOrNull() ?: -1).orEmpty()
                                type == "b" -> if (value == "1") "VRAI" else "FAUX"
                                type == "inlineStr" -> inline.toString()
                                type == "str" || type == "e" -> value.orEmpty()
                                value != null && styleId in styles.dates -> serialToDate(value!!) ?: cleanNumber(value!!)
                                else -> value?.let { cleanNumber(it) }.orEmpty()
                            }
                            if (content.isNotEmpty()) {
                                cells[ref.uppercase()] = content
                                maxRow = maxOf(maxRow, r + 1)
                                maxColumn = maxOf(maxColumn, c + 1)
                            }
                            styles.styles.getOrNull(styleId)
                                ?.takeIf { !it.isDefault }
                                ?.let { formats[ref.uppercase()] = it }
                        }
                        column++
                        ref = ""; type = ""; styleId = -1
                    }
                }
            }
            event = parser.next()
        }

        // Une mise en forme posée sur des colonnes entières (fréquent) ne doit
        // pas faire croire à une feuille de millions de lignes.
        val bounded = formats.filterKeys { key ->
            CellRef.parse(key)?.let { (r, c) -> r < maxRow + 1 && c < maxColumn + 1 } ?: false
        }
        return Sheet(
            name = name,
            cells = cells,
            styles = bounded,
            columns = maxOf(maxColumn, 12),
            rows = maxOf(maxRow, 40)
        )
    }
}
