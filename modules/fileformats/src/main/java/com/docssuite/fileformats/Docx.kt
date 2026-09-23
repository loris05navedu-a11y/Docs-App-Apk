package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser

/**
 * WordprocessingML (.docx). Le fichier produit est un OPC complet
 * (`[Content_Types].xml` + relations + `word/document.xml`), donc ouvrable tel
 * quel par Word, LibreOffice, Google Docs ou Pages.
 */
object Docx {

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"

    /** Largeur utile d'une page A4 aux marges de 2,5 cm, en vingtièmes de point. */
    private const val TEXT_WIDTH_TWIPS = 9070

    /** Un cran de retrait : 1,27 cm, la valeur du bouton de Word. */
    private const val INDENT_TWIPS = 720

    // ------------------------------------------------------------ écriture

    fun write(document: TextDocument): ByteArray = ZipBuilder()
        .add("[Content_Types].xml", contentTypes())
        .add("_rels/.rels", rootRels())
        .add("docProps/core.xml", coreProps(document.title))
        .add("docProps/app.xml", appProps())
        .add("word/_rels/document.xml.rels", documentRels())
        .add("word/styles.xml", styles())
        .add("word/document.xml", documentXml(document))
        .build()

    private fun contentTypes() = XML_DECL + """
        <Types xmlns="$NS_CT">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
        <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
        </Types>
    """.trimIndent()

    private fun rootRels() = XML_DECL + """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="$NS_REL/officeDocument" Target="word/document.xml"/>
        <Relationship Id="rId2" Type="$NS_REL/metadata/core-properties" Target="docProps/core.xml"/>
        <Relationship Id="rId3" Type="$NS_REL/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
    """.trimIndent()

    private fun documentRels() = XML_DECL + """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="$NS_REL/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    /**
     * Les titres portent le nom interne « heading N » : c'est lui que Word
     * reconnaît pour le volet de navigation et la table des matières, quelle
     * que soit la langue de l'interface.
     */
    private fun styles(): String {
        val headings = (1..3).joinToString("") { level ->
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">" +
                "<w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/>" +
                "<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/></w:rPr></w:style>"
        }
        return XML_DECL + """
            <w:styles xmlns:w="$NS_W">
            <w:docDefaults><w:rPrDefault><w:rPr>
            <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/>
            </w:rPr></w:rPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
            $headings
            <w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/>
            <w:tblPr><w:tblBorders>
            <w:top w:val="single" w:sz="4" w:space="0" w:color="A0A7B4"/>
            <w:left w:val="single" w:sz="4" w:space="0" w:color="A0A7B4"/>
            <w:bottom w:val="single" w:sz="4" w:space="0" w:color="A0A7B4"/>
            <w:right w:val="single" w:sz="4" w:space="0" w:color="A0A7B4"/>
            <w:insideH w:val="single" w:sz="4" w:space="0" w:color="A0A7B4"/>
            <w:insideV w:val="single" w:sz="4" w:space="0" w:color="A0A7B4"/>
            </w:tblBorders><w:tblCellMar><w:left w:w="108" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>
            </w:style>
            </w:styles>
        """.trimIndent()
    }

    private fun documentXml(document: TextDocument): String {
        val body = StringBuilder()
        val blocks = document.blocks.ifEmpty { listOf(TextParagraph()) }
        blocks.forEach { block ->
            when (block) {
                is TextParagraph -> body.append(paragraphXml(block, document.lineSpacing))
                is TextTable -> body.append(tableXml(block.normalized(), document.lineSpacing))
            }
        }
        // Word exige un paragraphe après un tableau en fin de corps.
        if (blocks.last() is TextTable) body.append("<w:p/>")
        // Format A4 portrait avec des marges de 2,5 cm (unités : twips).
        body.append(
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                "<w:pgMar w:top=\"1418\" w:right=\"1418\" w:bottom=\"1418\" w:left=\"1418\"" +
                " w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>"
        )
        return XML_DECL + "<w:document xmlns:w=\"$NS_W\"><w:body>$body</w:body></w:document>"
    }

    private fun paragraphXml(paragraph: TextParagraph, lineSpacing: Int): String {
        val sb = StringBuilder("<w:p><w:pPr>")
        if (paragraph.heading in 1..3) sb.append("<w:pStyle w:val=\"Heading${paragraph.heading}\"/>")
        if (lineSpacing != 100) {
            sb.append("<w:spacing w:line=\"${240 * lineSpacing / 100}\" w:lineRule=\"auto\"/>")
        }
        if (paragraph.indent > 0) sb.append("<w:ind w:left=\"${paragraph.indent * INDENT_TWIPS}\"/>")
        sb.append("<w:jc w:val=\"${jcValue(paragraph.align)}\"/></w:pPr>")
        paragraph.runs.filter { it.text.isNotEmpty() }.forEach { sb.append(runXml(it)) }
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun tableXml(table: TextTable, lineSpacing: Int): String {
        val columns = table.columnCount.coerceAtLeast(1)
        val columnWidth = TEXT_WIDTH_TWIPS / columns
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>")
        sb.append("<w:tblW w:w=\"${columnWidth * columns}\" w:type=\"dxa\"/>")
        sb.append("<w:tblLayout w:type=\"fixed\"/><w:tblLook w:val=\"04A0\"/></w:tblPr><w:tblGrid>")
        repeat(columns) { sb.append("<w:gridCol w:w=\"$columnWidth\"/>") }
        sb.append("</w:tblGrid>")

        table.rows.forEachIndexed { rowIndex, row ->
            sb.append("<w:tr>")
            if (rowIndex == 0 && table.headerRow) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            var column = 0
            row.cells.forEach { cell ->
                // La fusion verticale : l'origine dit « restart », les suivantes
                // « continue ». L'origine se reconnaît à la cellule marquée sous elle.
                val below = table.rows.getOrNull(rowIndex + 1)?.let { cellAt(it, column) }
                val startsMerge = !cell.mergedAbove && below?.mergedAbove == true
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${columnWidth * cell.colSpan}\" w:type=\"dxa\"/>")
                if (cell.colSpan > 1) sb.append("<w:gridSpan w:val=\"${cell.colSpan}\"/>")
                if (cell.mergedAbove) sb.append("<w:vMerge/>")
                else if (startsMerge) sb.append("<w:vMerge w:val=\"restart\"/>")
                if (cell.fill != 0L) {
                    sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${cell.fill.toHexRgb()}\"/>")
                }
                sb.append("</w:tcPr>")
                val paragraphs = if (cell.mergedAbove) listOf(TextParagraph()) else cell.paragraphs
                paragraphs.ifEmpty { listOf(TextParagraph()) }
                    .forEach { sb.append(paragraphXml(it, lineSpacing)) }
                sb.append("</w:tc>")
                column += cell.colSpan
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    /** La cellule qui occupe la colonne logique [column] d'une rangée. */
    private fun cellAt(row: TableRow, column: Int): TableCell? {
        var position = 0
        for (cell in row.cells) {
            if (position == column) return cell
            position += cell.colSpan
            if (position > column) return null
        }
        return null
    }

    private fun runXml(run: TextRun): String {
        val properties = StringBuilder("<w:rPr>")
        val font = officeFontName(run.fontName)
        properties.append("<w:rFonts w:ascii=\"${xmlEscape(font)}\" w:hAnsi=\"${xmlEscape(font)}\"/>")
        if (run.bold) properties.append("<w:b/>")
        if (run.italic) properties.append("<w:i/>")
        if (run.underline) properties.append("<w:u w:val=\"single\"/>")
        if (run.strike) properties.append("<w:strike/>")
        properties.append("<w:color w:val=\"${run.color.toHexRgb()}\"/>")
        // `w:highlight` n'accepte qu'une quinzaine de couleurs nommées ; `w:shd`
        // porte n'importe quelle valeur RVB et rend la même chose à l'écran.
        if (run.highlight != 0L) {
            properties.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${run.highlight.toHexRgb()}\"/>")
        }
        // Word compte les tailles en demi-points.
        properties.append("<w:sz w:val=\"${run.size * 2}\"/><w:szCs w:val=\"${run.size * 2}\"/>")
        when (run.baseline) {
            1 -> properties.append("<w:vertAlign w:val=\"superscript\"/>")
            -1 -> properties.append("<w:vertAlign w:val=\"subscript\"/>")
        }
        properties.append("</w:rPr>")

        // Un saut de ligne à l'intérieur d'un paragraphe s'écrit <w:br/>, une
        // tabulation <w:tab/> : dans <w:t>, Word les lirait comme des espaces.
        val text = StringBuilder()
        val piece = StringBuilder()
        fun flush() {
            if (piece.isNotEmpty()) {
                text.append("<w:t xml:space=\"preserve\">${xmlEscape(piece.toString())}</w:t>")
                piece.setLength(0)
            }
        }
        run.text.forEach { ch ->
            when (ch) {
                '\n' -> { flush(); text.append("<w:br/>") }
                '\t' -> { flush(); text.append("<w:tab/>") }
                else -> piece.append(ch)
            }
        }
        flush()
        return "<w:r>$properties$text</w:r>"
    }

    private fun jcValue(align: Int) = when (align) {
        1 -> "center"
        2 -> "right"
        3 -> "both"
        else -> "left"
    }

    private fun coreProps(title: String) = XML_DECL + """
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
         xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        <dc:title>${xmlEscape(title)}</dc:title>
        <dc:creator>DocsApp Suite</dc:creator>
        <cp:lastModifiedBy>DocsApp Suite</cp:lastModifiedBy>
        </cp:coreProperties>
    """.trimIndent()

    private fun appProps() = XML_DECL + """
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
        <Application>DocsApp Suite</Application>
        </Properties>
    """.trimIndent()

    // ------------------------------------------------------------ lecture

    fun read(bytes: ByteArray, title: String = "Document"): TextDocument {
        val parts = unzip(bytes)
        val rels = parseRelationships(parts["_rels/.rels"])
        val mainPath = rels.values.firstOrNull { it.endsWith("document.xml") || it.endsWith("document2.xml") }
            ?.removePrefix("/")
        val xml = mainPath?.let { parts[it] }
            ?: parts["word/document.xml"]
            ?: parts.entries.firstOrNull { it.key.endsWith("document.xml") }?.value
            ?: throw FormatException("Ce .docx ne contient pas word/document.xml")
        val folder = mainPath?.substringBeforeLast('/', "word") ?: "word"
        val styles = runCatching { WordStyles.parse(parts["$folder/styles.xml"]) }
            .getOrDefault(WordStyles.EMPTY)
        val numbering = runCatching { WordNumbering.parse(parts["$folder/numbering.xml"]) }
            .getOrDefault(WordNumbering.EMPTY)
        return TextDocument(title = title, blocks = BodyReader(styles, numbering).read(xml))
    }

    /** Propriétés de caractère éventuellement partielles : `null` = hérité. */
    internal data class RunProps(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val size: Int? = null,
        val font: String? = null,
        val color: Long? = null,
        val highlight: Long? = null,
        val baseline: Int? = null
    ) {
        /** [over] l'emporte sur ce qu'il précise. */
        fun merge(over: RunProps) = RunProps(
            bold = over.bold ?: bold,
            italic = over.italic ?: italic,
            underline = over.underline ?: underline,
            strike = over.strike ?: strike,
            size = over.size ?: size,
            font = over.font ?: font,
            color = over.color ?: color,
            highlight = over.highlight ?: highlight,
            baseline = over.baseline ?: baseline
        )

        fun toRun(text: String) = TextRun(
            text = text,
            bold = bold ?: false,
            italic = italic ?: false,
            underline = underline ?: false,
            strike = strike ?: false,
            size = size ?: DEFAULT_SIZE,
            fontName = font.orEmpty(),
            color = color ?: 0xFF1A1A1AL,
            highlight = highlight ?: 0L,
            baseline = baseline ?: 0
        )
    }

    internal data class ParaProps(
        val align: Int? = null,
        val indentTwips: Int? = null,
        val numId: String? = null,
        val level: Int? = null,
        val outline: Int? = null
    ) {
        fun merge(over: ParaProps) = ParaProps(
            align = over.align ?: align,
            indentTwips = over.indentTwips ?: indentTwips,
            numId = over.numId ?: numId,
            level = over.level ?: level,
            outline = over.outline ?: outline
        )
    }

    /** Word utilise 10 pt quand ni le document ni le style ne disent rien. */
    private const val DEFAULT_SIZE = 10

    /**
     * Lit un `<w:rPr>` dont le parseur est sur la balise ouvrante, et renvoie
     * aussi le style de caractère qu'il désigne (`rStyle`).
     */
    internal fun readRunProps(parser: XmlPullParser): Pair<RunProps, String?> {
        var props = RunProps()
        var style: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    // L'ancienne mise en forme d'une modification suivie : la
                    // lire écraserait la mise en forme actuelle.
                    "rPrChange" -> parser.skipSubtree()
                    "rStyle" -> {
                        style = parser.attr("val"); depth++
                    }
                    else -> {
                        props = props.merge(singleRunProp(parser)); depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return props to style
            }
        }
        return props to style
    }

    /**
     * Lit un `<w:pPr>`. Le `<w:rPr>` qu'il contient décrit la marque de fin de
     * paragraphe, pas le texte : on le passe.
     */
    internal fun readParaProps(parser: XmlPullParser): Pair<ParaProps, String?> {
        var props = ParaProps()
        var style: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.localName) {
                        "pStyle" -> style = parser.attr("val")
                        "jc" -> props = props.copy(align = alignFromJc(parser.attr("val")))
                        "ind" -> (parser.attr("left") ?: parser.attr("start"))?.toIntOrNull()
                            ?.let { props = props.copy(indentTwips = it) }
                        "numId" -> props = props.copy(numId = parser.attr("val"))
                        "ilvl" -> parser.attr("val")?.toIntOrNull()?.let { props = props.copy(level = it) }
                        "outlineLvl" -> parser.attr("val")?.toIntOrNull()?.let { props = props.copy(outline = it) }
                        "rPr", "pPrChange", "sectPr", "tabs", "framePr" -> {
                            parser.skipSubtree(); depth--
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return props to style
            }
        }
        return props to style
    }

    /**
     * Parcours du corps. Chaque paragraphe a son propre état, empilé : une
     * zone de texte place des paragraphes entiers à l'intérieur d'un autre,
     * et un état partagé mêlait leurs textes.
     */
    private class BodyReader(val styles: WordStyles, val numbering: WordNumbering) {

        private class Para {
            val runs = ArrayList<TextRun>()
            var props = ParaProps()
            var style: String? = null
        }

        private val builder = BlockBuilder()
        private val paragraphs = ArrayList<Para>()
        private val counters = HashMap<String, IntArray>()

        fun read(xml: ByteArray): List<DocBlock> {
            val parser = newPullParser(xml)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    handleStart(parser)
                } else if (event == XmlPullParser.END_TAG) {
                    handleEnd(parser.localName)
                }
                event = parser.next()
            }
            while (paragraphs.isNotEmpty()) finishParagraph()
            return builder.build()
        }

        private fun handleStart(parser: XmlPullParser) {
            when (parser.localName) {
                "p" -> paragraphs.add(Para())
                "pPr" -> {
                    val (props, style) = readParaProps(parser)
                    paragraphs.lastOrNull()?.let { it.props = props; it.style = style }
                }
                "r" -> readRun(parser)
                "tbl" -> builder.startTable()
                "tr" -> builder.startRow()
                "tc" -> builder.startCell()
                "gridSpan" -> parser.attr("val")?.toIntOrNull()?.let { builder.cellSpan(it) }
                "vMerge" -> {
                    val value = parser.attr("val")
                    if (value == null || value == "continue") builder.cellMergedAbove()
                }
                "tcPr" -> readCellProps(parser)
                "trPr" -> readRowProps(parser)
                // Ce qui ne doit pas finir dans le texte : l'ancienne version
                // d'une modification suivie, le rendu de repli d'une forme
                // (doublon de la version principale), les propriétés d'un
                // tableau ou d'une section.
                "del", "moveFrom", "Fallback", "tblPr", "tblGrid", "sectPr",
                "tblPrEx", "customXmlPr", "sdtPr", "sdtEndPr" -> parser.skipSubtree()
            }
        }

        private fun handleEnd(name: String) {
            when (name) {
                "p" -> finishParagraph()
                "tc" -> builder.endCell()
                "tr" -> builder.endRow()
                "tbl" -> builder.endTable()
            }
        }

        /** Une ligne d'en-tête se répète en haut de chaque page dans Word. */
        private fun readRowProps(parser: XmlPullParser) {
            val depth = parser.depth
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> if (parser.localName == "tblHeader" &&
                        Docx.onOff(parser.attr("val"))
                    ) builder.markHeaderRow()
                    XmlPullParser.END_TAG -> if (parser.depth == depth) return
                    XmlPullParser.END_DOCUMENT -> return
                }
            }
        }

        /** Seule la couleur de fond concerne l'affichage ; le reste est ignoré. */
        private fun readCellProps(parser: XmlPullParser) {
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        when (parser.localName) {
                            "gridSpan" -> parser.attr("val")?.toIntOrNull()?.let { builder.cellSpan(it) }
                            "vMerge" -> {
                                val value = parser.attr("val")
                                if (value == null || value == "continue") builder.cellMergedAbove()
                            }
                            "shd" -> parser.attr("fill")
                                ?.takeIf { !it.equals("auto", true) }
                                ?.let { builder.cellFill(hexToArgb(it, 0L)) }
                            "tcPrChange" -> {
                                parser.skipSubtree(); depth--
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return
                }
            }
        }

        /** Un run entier : ses propriétés, puis son texte. */
        private fun readRun(parser: XmlPullParser) {
            val para = paragraphs.lastOrNull() ?: Para().also { paragraphs.add(it) }
            var direct = RunProps()
            var characterStyle: String? = null
            val text = StringBuilder()
            val runDepth = parser.depth
            var inText = false
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> when (parser.localName) {
                        "rPr" -> readRunProps(parser).let { (props, style) ->
                            direct = props; characterStyle = style
                        }
                        "t" -> inText = true
                        "tab", "ptab" -> text.append('\t')
                        "br", "cr" -> text.append('\n')
                        "noBreakHyphen" -> text.append('-')
                        "sym" -> text.append(symbolChar(parser.attr("char")))
                        // Une zone de texte : ses paragraphes sont lus pour
                        // eux-mêmes, après le texte qui la précède.
                        "txbxContent" -> {
                            flushRunText(para, direct, characterStyle, text)
                            readNested(parser)
                        }
                        // Le rendu de repli d'une forme double sa version
                        // principale ; le reste n'est pas du texte affiché.
                        "Fallback", "delText", "instrText", "del", "docPr", "pic" -> parser.skipSubtree()
                    }
                    XmlPullParser.TEXT -> if (inText) text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        if (parser.depth == runDepth) break
                        if (parser.localName == "t") inText = false
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            flushRunText(para, direct, characterStyle, text)
        }

        private fun flushRunText(para: Para, direct: RunProps, characterStyle: String?, text: StringBuilder) {
            if (text.isEmpty()) return
            val base = styles.runPropsFor(para.style)
                .merge(characterStyle?.let { styles.characterProps(it) } ?: RunProps())
                .merge(direct)
            // Un titre sans taille ni graisse précisées garde sa hiérarchie visible.
            val heading = headingLevel(para)
            val props = if (heading > 0) {
                RunProps(bold = true, size = headingSize(heading)).merge(base)
            } else base
            para.runs.add(props.toRun(text.toString()))
            text.setLength(0)
        }

        /**
         * Contenu d'une zone de texte : des paragraphes, voire des tableaux,
         * complets. Le parseur est sur `<w:txbxContent>` et s'arrête sur sa fin.
         */
        private fun readNested(parser: XmlPullParser) {
            val depth = parser.depth
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> handleStart(parser)
                    XmlPullParser.END_TAG -> {
                        if (parser.depth == depth) return
                        handleEnd(parser.localName)
                    }
                    XmlPullParser.END_DOCUMENT -> return
                }
            }
        }

        private fun headingLevel(para: Para): Int {
            val outline = para.props.outline ?: styles.paraPropsFor(para.style).outline
            val byName = styles.headingLevel(para.style)
            return when {
                byName > 0 -> byName
                outline != null && outline in 0..8 -> outline + 1
                else -> 0
            }
        }

        private fun headingSize(level: Int) = when (level) {
            1 -> 20
            2 -> 16
            else -> 13
        }

        private fun finishParagraph() {
            val para = paragraphs.removeLastOrNull() ?: return
            val props = styles.paraPropsFor(para.style).merge(para.props)
            val runs = ArrayList<TextRun>()
            val prefix = props.numId?.let { numId ->
                numbering.prefix(numId, props.level ?: 0, counters)
            }
            if (!prefix.isNullOrEmpty()) {
                val style = para.runs.firstOrNull()?.copy(text = "", underline = false, highlight = 0L)
                    ?: styles.runPropsFor(para.style).toRun("")
                runs.add(style.copy(text = prefix))
            }
            runs.addAll(mergeRuns(para.runs))
            val listLevel = if (props.numId != null && prefix != null) props.level ?: 0 else 0
            val indent = when {
                props.numId != null && prefix != null -> listLevel
                else -> ((props.indentTwips ?: 0) + INDENT_TWIPS / 2) / INDENT_TWIPS
            }
            builder.paragraph(
                TextParagraph(
                    runs = runs,
                    align = props.align ?: 0,
                    heading = headingLevel(para).coerceAtMost(3),
                    indent = indent.coerceIn(0, 8)
                )
            )
        }
    }

    /** Une propriété isolée d'un rPr (le parseur est sur sa balise ouvrante). */
    private fun singleRunProp(parser: XmlPullParser): RunProps {
        val value = parser.attr("val")
        return when (parser.localName) {
            "b" -> RunProps(bold = onOff(value))
            "i" -> RunProps(italic = onOff(value))
            "u" -> RunProps(underline = value != "none")
            "strike", "dstrike" -> RunProps(strike = onOff(value))
            "sz" -> value?.toIntOrNull()?.let { RunProps(size = (it / 2).coerceIn(4, 200)) } ?: RunProps()
            "rFonts" -> (parser.attr("ascii") ?: parser.attr("hAnsi"))
                ?.let { RunProps(font = appFontLabel(it)) } ?: RunProps()
            "color" -> if (value == null || value.equals("auto", true)) RunProps()
            else RunProps(color = hexToArgb(value, 0xFF1A1A1AL))
            "highlight" -> RunProps(highlight = namedHighlight(value))
            "shd" -> parser.attr("fill")
                ?.takeIf { !it.equals("auto", true) }
                ?.let { RunProps(highlight = hexToArgb(it, 0L)) } ?: RunProps()
            "vertAlign" -> RunProps(
                baseline = when (value) {
                    "superscript" -> 1
                    "subscript" -> -1
                    else -> 0
                }
            )
            else -> RunProps()
        }
    }

    /** Fusionne les fragments voisins de même style, fréquents chez Word. */
    internal fun mergeRuns(runs: List<TextRun>): List<TextRun> {
        val out = ArrayList<TextRun>()
        runs.forEach { run ->
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == run.copy(text = "")) {
                out[out.size - 1] = last.copy(text = last.text + run.text)
            } else if (run.text.isNotEmpty()) {
                out.add(run)
            }
        }
        return out
    }

    /** Les puces des polices Symbol et Wingdings logent dans la zone privée. */
    private fun symbolChar(code: String?): String {
        val value = code?.toIntOrNull(16) ?: return ""
        return when (value and 0xFF) {
            0xB7, 0xA7, 0x6C, 0x9F -> "•"
            0xE0, 0xE8, 0xD8 -> "→"
            0xFC -> "✓"
            else -> if (value in 0x20..0xFFFF && value !in 0xF000..0xF0FF) value.toChar().toString() else ""
        }
    }

    internal fun onOff(value: String?): Boolean =
        value == null || value == "1" || value.equals("true", true) || value.equals("on", true)

    private fun alignFromJc(value: String?) = when (value) {
        "center" -> 1
        "right", "end" -> 2
        "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> 3
        else -> 0
    }

    private fun namedHighlight(name: String?) = when (name?.lowercase()) {
        null, "none" -> 0L
        "yellow" -> 0xFFFFFF00L
        "green" -> 0xFF00FF00L
        "cyan" -> 0xFF00FFFFL
        "magenta" -> 0xFFFF00FFL
        "red" -> 0xFFFF0000L
        "blue" -> 0xFF0000FFL
        "darkyellow" -> 0xFF808000L
        "lightgray", "lightgrey" -> 0xFFD3D3D3L
        "darkgray" -> 0xFFA9A9A9L
        else -> 0xFFFFFF00L
    }
}

/**
 * Les styles d'un .docx. Un paragraphe « Titre 1 » ne dit rien de sa police :
 * tout vient de son style, lui-même bâti sur d'autres. Sans cette résolution,
 * les titres d'un document Word arrivaient comme du texte ordinaire.
 */
internal class WordStyles(
    private val defaults: Docx.RunProps,
    private val runProps: Map<String, Docx.RunProps>,
    private val paraProps: Map<String, Docx.ParaProps>,
    private val basedOn: Map<String, String>,
    private val names: Map<String, String>,
    private val defaultParagraph: String?
) {
    fun runPropsFor(styleId: String?): Docx.RunProps =
        chain(styleId ?: defaultParagraph).fold(defaults) { acc, id -> acc.merge(runProps[id] ?: Docx.RunProps()) }

    fun characterProps(styleId: String): Docx.RunProps =
        chain(styleId).fold(Docx.RunProps()) { acc, id -> acc.merge(runProps[id] ?: Docx.RunProps()) }

    fun paraPropsFor(styleId: String?): Docx.ParaProps =
        chain(styleId ?: defaultParagraph).fold(Docx.ParaProps()) { acc, id -> acc.merge(paraProps[id] ?: Docx.ParaProps()) }

    /** « heading 2 » (nom interne, indépendant de la langue) → 2 ; « Title » → 1. */
    fun headingLevel(styleId: String?): Int {
        for (id in chain(styleId).reversed()) {
            val name = (names[id] ?: id).lowercase()
            if (name == "title" || name == "titre") return 1
            Regex("^(heading|titre)\\s*(\\d)$").find(name)?.let { return it.groupValues[2].toInt() }
        }
        return 0
    }

    /** Du style le plus général au plus précis, sans boucler sur un cycle. */
    private fun chain(styleId: String?): List<String> {
        val out = ArrayList<String>()
        var current = styleId
        while (current != null && current !in out && out.size < 16) {
            out.add(current)
            current = basedOn[current]
        }
        return out.reversed()
    }

    companion object {
        val EMPTY = WordStyles(Docx.RunProps(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null)

        fun parse(xml: ByteArray?): WordStyles {
            if (xml == null) return EMPTY
            var defaults = Docx.RunProps()
            val runProps = HashMap<String, Docx.RunProps>()
            val paraProps = HashMap<String, Docx.ParaProps>()
            val basedOn = HashMap<String, String>()
            val names = HashMap<String, String>()
            var defaultParagraph: String? = null

            val parser = newPullParser(xml)
            var current: String? = null
            var inDefaults = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.localName) {
                        "rPrDefault" -> inDefaults = true
                        "style" -> {
                            current = parser.attr("styleId")
                            if (parser.attr("type") == "paragraph" && Docx.onOff(parser.attr("default")) &&
                                parser.attr("default") != null
                            ) defaultParagraph = current
                        }
                        "name" -> current?.let { id -> parser.attr("val")?.let { names[id] = it } }
                        "basedOn" -> current?.let { id -> parser.attr("val")?.let { basedOn[id] = it } }
                        "rPr" -> {
                            val props = Docx.readRunProps(parser).first
                            if (inDefaults) defaults = defaults.merge(props)
                            else current?.let { runProps[it] = props }
                        }
                        "pPr" -> {
                            val (props, _) = Docx.readParaProps(parser)
                            current?.let { paraProps[it] = props }
                        }
                        // Les variantes conditionnelles d'un style de tableau
                        // ne s'appliquent pas au texte courant.
                        "tblStylePr", "latentStyles" -> parser.skipSubtree()
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.localName) {
                        "rPrDefault" -> inDefaults = false
                        "style" -> current = null
                    }
                }
                event = parser.next()
            }
            return WordStyles(defaults, runProps, paraProps, basedOn, names, defaultParagraph)
        }
    }
}

/**
 * Les listes d'un .docx : chaque paragraphe numéroté renvoie à une définition
 * qui dit s'il porte une puce ou un numéro, et sous quelle forme.
 */
internal class WordNumbering(
    private val numToAbstract: Map<String, String>,
    private val levels: Map<String, Map<Int, Level>>
) {
    data class Level(val format: String, val text: String, val start: Int)

    fun prefix(numId: String, level: Int, counters: MutableMap<String, IntArray>): String? {
        if (numId == "0") return null
        val abstractId = numToAbstract[numId] ?: return null
        val definitions = levels[abstractId] ?: return null
        val definition = definitions[level] ?: definitions[0] ?: return null
        if (definition.format == "none") return ""
        if (definition.format == "bullet") {
            return when (level % 3) {
                0 -> "• "
                1 -> "◦ "
                else -> "▪ "
            }
        }
        // Les compteurs sont partagés par tous les numId d'une même définition,
        // comme dans Word pour une liste reprise après une interruption.
        val values = counters.getOrPut(abstractId) { IntArray(9) }
        val index = level.coerceIn(0, 8)
        if (values[index] == 0) values[index] = definition.start - 1
        values[index]++
        for (deeper in index + 1 until 9) values[deeper] = 0

        var text = definition.text.ifEmpty { "%${index + 1}." }
        for (n in 1..9) {
            if (!text.contains("%$n")) continue
            val levelDefinition = definitions[n - 1] ?: definition
            val value = values[n - 1].takeIf { it > 0 } ?: levelDefinition.start
            text = text.replace("%$n", formatNumber(value, levelDefinition.format))
        }
        return "$text "
    }

    private fun formatNumber(value: Int, format: String): String = when (format) {
        "lowerLetter" -> letters(value).lowercase()
        "upperLetter" -> letters(value)
        "lowerRoman" -> roman(value).lowercase()
        "upperRoman" -> roman(value)
        else -> value.toString()
    }

    private fun letters(value: Int): String {
        var n = value.coerceAtLeast(1)
        val sb = StringBuilder()
        while (n > 0) {
            val rest = (n - 1) % 26
            sb.insert(0, 'A' + rest)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun roman(value: Int): String {
        var n = value.coerceIn(1, 3999)
        val table = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
        )
        val sb = StringBuilder()
        for ((amount, symbol) in table) while (n >= amount) {
            sb.append(symbol); n -= amount
        }
        return sb.toString()
    }

    companion object {
        val EMPTY = WordNumbering(emptyMap(), emptyMap())

        fun parse(xml: ByteArray?): WordNumbering {
            if (xml == null) return EMPTY
            val numToAbstract = HashMap<String, String>()
            val levels = HashMap<String, HashMap<Int, Level>>()
            val parser = newPullParser(xml)
            var abstractId: String? = null
            var numId: String? = null
            var level = -1
            var format = "decimal"
            var text = ""
            var start = 1
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.localName) {
                        "abstractNum" -> abstractId = parser.attr("abstractNumId")
                        "num" -> numId = parser.attr("numId")
                        "abstractNumId" -> {
                            val target = parser.attr("val")
                            if (numId != null && target != null) numToAbstract[numId!!] = target
                        }
                        "lvl" -> if (abstractId != null) {
                            level = parser.attr("ilvl")?.toIntOrNull() ?: 0
                            format = "decimal"; text = ""; start = 1
                        }
                        "numFmt" -> if (level >= 0) format = parser.attr("val") ?: "decimal"
                        "lvlText" -> if (level >= 0) text = parser.attr("val").orEmpty()
                        "start" -> if (level >= 0) start = parser.attr("val")?.toIntOrNull() ?: 1
                        "lvlOverride" -> parser.skipSubtree()
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.localName) {
                        "lvl" -> if (abstractId != null && level >= 0) {
                            levels.getOrPut(abstractId!!) { HashMap() }[level] = Level(format, text, start)
                            level = -1
                        }
                        "abstractNum" -> abstractId = null
                        "num" -> numId = null
                    }
                }
                event = parser.next()
            }
            return WordNumbering(numToAbstract, levels)
        }
    }
}
