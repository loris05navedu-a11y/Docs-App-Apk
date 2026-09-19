package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser

/**
 * PresentationML (.pptx). Le format exige une chaîne complète
 * présentation → masque → disposition → thème avant d'accepter la moindre
 * diapositive ; on produit donc un masque et un thème minimaux mais valides.
 */
object Pptx {

    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"

    /** 1 pouce = 914400 EMU ; la diapositive fait 13,333 × 7,5 pouces (16:9). */
    private const val SLIDE_WIDTH = 12192000
    private const val SLIDE_HEIGHT = 6858000

    // ------------------------------------------------------------ écriture

    fun write(deck: Deck): ByteArray {
        val slides = deck.slides.ifEmpty { listOf(SlideModel(title = deck.title)) }
        val zip = ZipBuilder()
            .add("[Content_Types].xml", contentTypes(slides.size))
            .add("_rels/.rels", rootRels())
            .add("docProps/core.xml", coreProps(deck.title))
            .add("docProps/app.xml", appProps(slides.size))
            .add("ppt/presentation.xml", presentationXml(slides.size))
            .add("ppt/_rels/presentation.xml.rels", presentationRels(slides.size))
            .add("ppt/slideMasters/slideMaster1.xml", slideMaster())
            .add("ppt/slideMasters/_rels/slideMaster1.xml.rels", slideMasterRels())
            .add("ppt/slideLayouts/slideLayout1.xml", slideLayout())
            .add("ppt/slideLayouts/_rels/slideLayout1.xml.rels", slideLayoutRels())
            .add("ppt/theme/theme1.xml", theme())
        slides.forEachIndexed { index, slide ->
            zip.add("ppt/slides/slide${index + 1}.xml", slideXml(slide))
            zip.add("ppt/slides/_rels/slide${index + 1}.xml.rels", slideRels())
        }
        return zip.build()
    }

    private fun contentTypes(slideCount: Int): String {
        val slides = (1..slideCount).joinToString("") {
            "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
        }
        return XML_DECL + """
            <Types xmlns="$NS_CT">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
            <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
            <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
            <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
            $slides
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
            <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
            </Types>
        """.trimIndent()
    }

    private fun rootRels() = XML_DECL + """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="$NS_REL/officeDocument" Target="ppt/presentation.xml"/>
        <Relationship Id="rId2" Type="$NS_REL/metadata/core-properties" Target="docProps/core.xml"/>
        <Relationship Id="rId3" Type="$NS_REL/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
    """.trimIndent()

    private fun presentationXml(slideCount: Int): String {
        // Les identifiants de diapositive doivent rester dans [256, 2147483647].
        val ids = (1..slideCount).joinToString("") {
            "<p:sldId id=\"${255 + it}\" r:id=\"rId${it + 1}\"/>"
        }
        return XML_DECL + "<p:presentation xmlns:a=\"$NS_A\" xmlns:r=\"$NS_REL\" xmlns:p=\"$NS_P\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$ids</p:sldIdLst>" +
            "<p:sldSz cx=\"$SLIDE_WIDTH\" cy=\"$SLIDE_HEIGHT\"/>" +
            "<p:notesSz cx=\"$SLIDE_HEIGHT\" cy=\"$SLIDE_WIDTH\"/>" +
            "</p:presentation>"
    }

    private fun presentationRels(slideCount: Int): String {
        val slides = (1..slideCount).joinToString("") {
            "<Relationship Id=\"rId${it + 1}\" Type=\"$NS_REL/slide\" Target=\"slides/slide$it.xml\"/>"
        }
        return XML_DECL + """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="$NS_REL/slideMaster" Target="slideMasters/slideMaster1.xml"/>
            $slides
            <Relationship Id="rId${slideCount + 2}" Type="$NS_REL/theme" Target="theme/theme1.xml"/>
            </Relationships>
        """.trimIndent()
    }

    private fun slideRels() = XML_DECL + """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="$NS_REL/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun slideLayoutRels() = XML_DECL + """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="$NS_REL/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun slideMasterRels() = XML_DECL + """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="$NS_REL/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
        <Relationship Id="rId2" Type="$NS_REL/theme" Target="../theme/theme1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun emptyShapeTree() =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun colorMap() =
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\"" +
            " accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\"" +
            " accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"

    private fun slideMaster() = XML_DECL +
        "<p:sldMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_REL\" xmlns:p=\"$NS_P\">" +
        "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill>" +
        "<a:effectLst/></p:bgPr></p:bg><p:spTree>${emptyShapeTree()}</p:spTree></p:cSld>" +
        colorMap() +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
        "</p:sldMaster>"

    private fun slideLayout() = XML_DECL +
        "<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_REL\" xmlns:p=\"$NS_P\" type=\"blank\" preserve=\"1\">" +
        "<p:cSld name=\"Vide\"><p:spTree>${emptyShapeTree()}</p:spTree></p:cSld>" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun slideXml(slide: SlideModel): String {
        val shapes = StringBuilder()
        var id = 2
        if (slide.title.isNotBlank()) {
            shapes.append(
                textShape(
                    id = id++, name = "Titre", text = slide.title,
                    x = 685800, y = 800100, cx = SLIDE_WIDTH - 2 * 685800, cy = 1600200,
                    size = slide.titleSize, bold = true,
                    color = slide.textColor, align = slide.align, font = slide.fontName
                )
            )
        }
        if (slide.content.isNotBlank()) {
            shapes.append(
                textShape(
                    id = id, name = "Contenu", text = slide.content,
                    x = 685800, y = 2590800, cx = SLIDE_WIDTH - 2 * 685800, cy = 3200400,
                    size = slide.contentSize, bold = false,
                    color = slide.textColor, align = slide.align, font = slide.fontName
                )
            )
        }
        return XML_DECL + "<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_REL\" xmlns:p=\"$NS_P\">" +
            "<p:cSld><p:bg><p:bgPr><a:solidFill>" +
            "<a:srgbClr val=\"${slide.background.toHexRgb()}\"/></a:solidFill>" +
            "<a:effectLst/></p:bgPr></p:bg>" +
            "<p:spTree>${emptyShapeTree()}$shapes</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private fun textShape(
        id: Int,
        name: String,
        text: String,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        size: Int,
        bold: Boolean,
        color: Long,
        align: Int,
        font: String
    ): String {
        val algn = when (align) {
            0 -> "l"
            2 -> "r"
            else -> "ctr"
        }
        val typeface = xmlEscape(officeFontName(font))
        // DrawingML compte les tailles en centièmes de point.
        val runProperties = "<a:rPr lang=\"fr-FR\" sz=\"${size * 100}\"" +
            (if (bold) " b=\"1\"" else "") + " dirty=\"0\">" +
            "<a:solidFill><a:srgbClr val=\"${color.toHexRgb()}\"/></a:solidFill>" +
            "<a:latin typeface=\"$typeface\"/></a:rPr>"

        val paragraphs = text.split("\n").joinToString("") { line ->
            if (line.isEmpty()) {
                "<a:p><a:pPr algn=\"$algn\"/><a:endParaRPr lang=\"fr-FR\" sz=\"${size * 100}\"/></a:p>"
            } else {
                "<a:p><a:pPr algn=\"$algn\"/><a:r>$runProperties" +
                    "<a:t>${xmlEscape(line)}</a:t></a:r></a:p>"
            }
        }

        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/>" +
            "<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" anchor=\"ctr\"><a:normAutofit/></a:bodyPr>" +
            "<a:lstStyle/>$paragraphs</p:txBody></p:sp>"
    }

    /** Thème minimal : PowerPoint refuse un masque sans thème complet. */
    private fun theme(): String {
        val colors = listOf(
            "dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6",
            "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5",
            "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47",
            "hlink" to "0563C1", "folHlink" to "954F72"
        ).joinToString("") { (name, value) ->
            if (name == "dk1" || name == "lt1") {
                "<a:$name><a:sysClr val=\"${if (name == "dk1") "windowText" else "window"}\"" +
                    " lastClr=\"$value\"/></a:$name>"
            } else {
                "<a:$name><a:srgbClr val=\"$value\"/></a:$name>"
            }
        }
        val fontScheme = "<a:fontScheme name=\"Office\">" +
            "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/>" +
            "<a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/>" +
            "<a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"
        val solid = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        val line = "<a:ln w=\"6350\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">$solid" +
            "<a:prstDash val=\"solid\"/></a:ln>"
        val formatScheme = "<a:fmtScheme name=\"Office\">" +
            "<a:fillStyleLst>$solid$solid$solid</a:fillStyleLst>" +
            "<a:lnStyleLst>$line$line$line</a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst>$solid$solid$solid</a:bgFillStyleLst></a:fmtScheme>"
        return XML_DECL + "<a:theme xmlns:a=\"$NS_A\" name=\"Office\"><a:themeElements>" +
            "<a:clrScheme name=\"Office\">$colors</a:clrScheme>$fontScheme$formatScheme" +
            "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }

    private fun coreProps(title: String) = XML_DECL + """
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
         xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>${xmlEscape(title)}</dc:title><dc:creator>DocsApp Suite</dc:creator>
        </cp:coreProperties>
    """.trimIndent()

    private fun appProps(slideCount: Int) = XML_DECL + """
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
        <Application>DocsApp Suite</Application><Slides>$slideCount</Slides></Properties>
    """.trimIndent()

    // ------------------------------------------------------------ lecture

    fun read(bytes: ByteArray, title: String = "Présentation"): Deck {
        val parts = unzip(bytes)
        val order = slideOrder(parts)
        val slides = order.mapNotNull { path -> parts[path]?.let { parseSlide(it) } }
        if (slides.isEmpty()) throw FormatException("Ce .pptx ne contient aucune diapositive")
        return Deck(title, slides)
    }

    /**
     * L'ordre d'affichage est celui de `sldIdLst`, pas l'ordre alphabétique des
     * fichiers : `slide10.xml` se classerait avant `slide2.xml`.
     */
    private fun slideOrder(parts: Map<String, ByteArray>): List<String> {
        val relations = parseRelationships(parts["ppt/_rels/presentation.xml.rels"])
        val ids = ArrayList<String>()
        parts["ppt/presentation.xml"]?.let { xml ->
            runCatching {
                val parser = newPullParser(xml)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.localName == "sldId") {
                        parser.relationshipId()?.let { ids.add(it) }
                    }
                    event = parser.next()
                }
            }
        }
        val ordered = ids.mapNotNull { relations[it] }
            .map { "ppt/${it.removePrefix("/").removePrefix("ppt/")}" }
            .filter { parts.containsKey(it) }
        if (ordered.isNotEmpty()) return ordered
        return parts.keys
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { path ->
                path.substringAfterLast("slide").substringBefore(".xml").toIntOrNull() ?: 0
            }
    }

    private fun parseSlide(xml: ByteArray): SlideModel {
        val blocks = ArrayList<Pair<String, Int>>() // texte, taille
        var background: Long? = null
        var textColor: Long? = null

        val parser = newPullParser(xml)
        var inBackground = false
        var inTextBody = false
        var inTextNode = false
        var currentSize = 0
        var maxSize = 0
        val block = StringBuilder()
        val line = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "bg", "bgPr" -> inBackground = true
                    "srgbClr" -> {
                        val value = hexToArgb(parser.attr("val"), 0L)
                        if (inBackground && background == null) background = value
                        else if (inTextBody && textColor == null) textColor = value
                    }
                    "txBody" -> {
                        inTextBody = true; block.setLength(0); maxSize = 0
                    }
                    "rPr", "defRPr", "endParaRPr" -> {
                        currentSize = parser.attr("sz")?.toIntOrNull()?.div(100) ?: 0
                        if (currentSize > maxSize) maxSize = currentSize
                    }
                    "t" -> inTextNode = inTextBody
                    "br" -> if (inTextBody) line.append('\n')
                }
                XmlPullParser.TEXT -> if (inTextNode) line.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "t" -> inTextNode = false
                    "p" -> if (inTextBody) {
                        if (block.isNotEmpty()) block.append('\n')
                        block.append(line)
                        line.setLength(0)
                    }
                    "txBody" -> {
                        val text = block.toString().trim()
                        if (text.isNotEmpty()) blocks.add(text to maxSize)
                        inTextBody = false
                    }
                    "bg", "bgPr" -> inBackground = false
                }
            }
            event = parser.next()
        }

        // Le bloc au plus gros corps de texte fait le titre ; le reste, le corps.
        val titleBlock = blocks.maxByOrNull { it.second }
        val rest = blocks.filter { it !== titleBlock }
        return SlideModel(
            title = titleBlock?.first.orEmpty(),
            content = rest.joinToString("\n") { it.first },
            background = background ?: 0xFF1E293BL,
            textColor = textColor ?: 0xFFFFFFFFL,
            titleSize = (titleBlock?.second ?: 32).coerceIn(10, 96),
            contentSize = (rest.firstOrNull()?.second ?: 20).coerceIn(8, 72)
        )
    }
}
