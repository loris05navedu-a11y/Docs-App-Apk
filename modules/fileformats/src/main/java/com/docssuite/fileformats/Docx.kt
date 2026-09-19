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

    private fun styles() = XML_DECL + """
        <w:styles xmlns:w="$NS_W">
        <w:docDefaults><w:rPrDefault><w:rPr>
        <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/>
        </w:rPr></w:rPrDefault></w:docDefaults>
        <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
        </w:styles>
    """.trimIndent()

    private fun documentXml(document: TextDocument): String {
        val body = StringBuilder()
        val paragraphs = document.paragraphs.ifEmpty { listOf(TextParagraph()) }
        paragraphs.forEach { paragraph ->
            body.append("<w:p>")
            body.append("<w:pPr><w:jc w:val=\"${jcValue(paragraph.align)}\"/></w:pPr>")
            paragraph.runs.filter { it.text.isNotEmpty() }.forEach { body.append(runXml(it)) }
            body.append("</w:p>")
        }
        // Format A4 portrait avec des marges de 2,5 cm (unités : twips).
        body.append(
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                "<w:pgMar w:top=\"1418\" w:right=\"1418\" w:bottom=\"1418\" w:left=\"1418\"" +
                " w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>"
        )
        return XML_DECL + "<w:document xmlns:w=\"$NS_W\"><w:body>$body</w:body></w:document>"
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
        properties.append("</w:rPr>")

        // Un saut de ligne à l'intérieur d'un paragraphe s'écrit <w:br/>.
        val pieces = run.text.split("\n")
        val text = StringBuilder()
        pieces.forEachIndexed { index, piece ->
            if (index > 0) text.append("<w:br/>")
            if (piece.isNotEmpty()) {
                text.append("<w:t xml:space=\"preserve\">${xmlEscape(piece)}</w:t>")
            }
        }
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
        val mainTarget = rels.values.firstOrNull { it.endsWith("document.xml") }
            ?.removePrefix("/")?.removePrefix("word/")
        val xml = parts["word/${mainTarget ?: "document.xml"}"]
            ?: parts["word/document.xml"]
            ?: parts.entries.firstOrNull { it.key.endsWith("document.xml") }?.value
            ?: throw FormatException("Ce .docx ne contient pas word/document.xml")
        return TextDocument(title = title, paragraphs = parseBody(xml))
    }

    private fun parseBody(xml: ByteArray): List<TextParagraph> {
        val paragraphs = ArrayList<TextParagraph>()
        val parser = newPullParser(xml)

        var runs = ArrayList<TextRun>()
        var align = 0
        var style = TextRun("")
        var buffer = StringBuilder()
        var inRunProperties = false
        var inParagraphProperties = false
        var inTextNode = false
        var inDeleted = false

        fun flushRun() {
            if (buffer.isNotEmpty()) {
                runs.add(style.copy(text = buffer.toString()))
                buffer = StringBuilder()
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "p" -> {
                        runs = ArrayList(); align = 0
                    }
                    "pPr" -> inParagraphProperties = true
                    "jc" -> if (inParagraphProperties) align = alignFromJc(parser.attr("val"))
                    "rPr" -> {
                        inRunProperties = true
                        style = TextRun("")
                    }
                    // Le texte supprimé en suivi de modifications ne fait plus
                    // partie du document : on ne le réimporte pas.
                    "del" -> inDeleted = true
                    "rFonts" -> if (inRunProperties) {
                        style = style.copy(fontName = appFontLabel(parser.attr("ascii")))
                    }
                    "b" -> if (inRunProperties) style = style.copy(bold = onOff(parser.attr("val")))
                    "i" -> if (inRunProperties) style = style.copy(italic = onOff(parser.attr("val")))
                    "u" -> if (inRunProperties) {
                        style = style.copy(underline = parser.attr("val") != "none")
                    }
                    "strike" -> if (inRunProperties) style = style.copy(strike = onOff(parser.attr("val")))
                    "color" -> if (inRunProperties) {
                        style = style.copy(color = hexToArgb(parser.attr("val"), 0xFF1A1A1AL))
                    }
                    "highlight" -> if (inRunProperties) {
                        style = style.copy(highlight = namedHighlight(parser.attr("val")))
                    }
                    "shd" -> if (inRunProperties) {
                        val fill = parser.attr("fill")
                        if (fill != null && !fill.equals("auto", true)) {
                            style = style.copy(highlight = hexToArgb(fill, 0L))
                        }
                    }
                    "sz" -> if (inRunProperties) {
                        val halfPoints = parser.attr("val")?.toIntOrNull()
                        if (halfPoints != null) style = style.copy(size = (halfPoints / 2).coerceIn(6, 200))
                    }
                    "t" -> inTextNode = !inDeleted
                    "tab" -> if (!inDeleted) buffer.append('\t')
                    "br" -> if (!inDeleted) {
                        flushRun()
                        runs.add(style.copy(text = "\n"))
                    }
                }

                XmlPullParser.TEXT -> if (inTextNode) buffer.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.localName) {
                    "t" -> {
                        inTextNode = false
                        flushRun()
                    }
                    "rPr" -> inRunProperties = false
                    "pPr" -> inParagraphProperties = false
                    "del" -> inDeleted = false
                    "r" -> flushRun()
                    "p" -> paragraphs.add(TextParagraph(runs.toList(), align))
                }
            }
            event = parser.next()
        }
        return paragraphs
    }

    private fun onOff(value: String?): Boolean =
        value == null || value == "1" || value.equals("true", true) || value.equals("on", true)

    private fun alignFromJc(value: String?) = when (value) {
        "center" -> 1
        "right", "end" -> 2
        "both", "distribute" -> 3
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
        "darkYellow".lowercase() -> 0xFF808000L
        "lightGray".lowercase(), "lightgrey" -> 0xFFD3D3D3L
        else -> 0xFFFFFF00L
    }
}
