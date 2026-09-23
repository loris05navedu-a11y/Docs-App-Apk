package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser

/**
 * Lecture du texte d'un document OpenDocument (.odt) : paragraphes, titres,
 * listes, mise en forme et tableaux. Les styles vivent à deux endroits —
 * `styles.xml` pour ceux que l'auteur a nommés, `content.xml` pour ceux que
 * LibreOffice crée à la volée — et se construisent les uns sur les autres.
 */
internal object OdfTextReader {

    private data class TextProps(
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
        fun merge(over: TextProps) = TextProps(
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
            size = size ?: 12,
            fontName = font.orEmpty(),
            color = color ?: 0xFF1A1A1AL,
            highlight = highlight ?: 0L,
            baseline = baseline ?: 0
        )
    }

    private class Style(
        val parent: String?,
        val text: TextProps,
        val align: Int?,
        val marginLeftCm: Double?,
        val cellFill: Long?,
        val listStyle: String?,
        val displayName: String?
    )

    /** Pour chaque liste nommée : niveau → puce (null) ou numéro (format, préfixe, suffixe). */
    private class ListLevel(val bullet: Boolean, val format: String, val prefix: String, val suffix: String)

    private class Styles {
        val byName = HashMap<String, Style>()
        val lists = HashMap<String, HashMap<Int, ListLevel>>()
        var defaults = TextProps()

        fun textProps(name: String?): TextProps =
            chain(name).fold(defaults) { acc, style -> acc.merge(style.text) }

        fun align(name: String?): Int? = chain(name).mapNotNull { it.align }.lastOrNull()
        fun marginLeft(name: String?): Double? = chain(name).mapNotNull { it.marginLeftCm }.lastOrNull()
        fun cellFill(name: String?): Long? = chain(name).mapNotNull { it.cellFill }.lastOrNull()
        fun listStyle(name: String?): String? = chain(name).mapNotNull { it.listStyle }.lastOrNull()

        /** « Heading 2 » (nom affiché) ou « Heading_20_2 » (nom interne) → 2. */
        fun headingLevel(name: String?): Int {
            for (style in chainNames(name).reversed()) {
                val label = (byName[style]?.displayName ?: style).replace("_20_", " ").lowercase()
                if (label == "title" || label == "titre") return 1
                Regex("^(heading|titre)\\s*(\\d)$").find(label)?.let { return it.groupValues[2].toInt() }
            }
            return 0
        }

        private fun chainNames(name: String?): List<String> {
            val out = ArrayList<String>()
            var current = name
            while (current != null && current !in out && out.size < 16) {
                out.add(current)
                current = byName[current]?.parent
            }
            return out.reversed()
        }

        private fun chain(name: String?): List<Style> = chainNames(name).mapNotNull { byName[it] }
    }

    fun read(parts: Map<String, ByteArray>, title: String): TextDocument {
        val content = parts["content.xml"] ?: throw FormatException("Archive OpenDocument sans content.xml")
        val styles = Styles()
        parts["styles.xml"]?.let { runCatching { readStyles(it, styles) } }
        readStyles(content, styles)
        return TextDocument(title, Body(styles).read(content))
    }

    private fun readStyles(xml: ByteArray, into: Styles) {
        val parser = newPullParser(xml)
        var name: String? = null
        var parent: String? = null
        var displayName: String? = null
        var listStyle: String? = null
        var text = TextProps()
        var align: Int? = null
        var margin: Double? = null
        var fill: Long? = null
        var inDefault = false
        var listName: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.localName) {
                    "style" -> if (parser.name.startsWith("style:") || parser.name == "style") {
                        name = parser.attr("name")
                        parent = parser.attr("parent-style-name")
                        displayName = parser.attr("display-name")
                        listStyle = parser.attr("list-style-name")
                        text = TextProps(); align = null; margin = null; fill = null
                    }
                    "default-style" -> if (parser.attr("family") == "paragraph") {
                        inDefault = true; text = TextProps()
                    }
                    "text-properties" -> if (name != null || inDefault) text = text.merge(readTextProperties(parser))
                    "paragraph-properties" -> if (name != null) {
                        align = alignOf(parser.attr("text-align")) ?: align
                        margin = lengthInCm(parser.attr("margin-left")) ?: margin
                    }
                    "table-cell-properties" -> if (name != null) {
                        fill = colorOf(parser.attr("background-color")) ?: fill
                    }
                    "list-style" -> listName = parser.attr("name")
                    "list-level-style-bullet", "list-level-style-number", "list-level-style-image" -> listName?.let { list ->
                        val level = parser.attr("level")?.toIntOrNull() ?: 1
                        into.lists.getOrPut(list) { HashMap() }[level] = ListLevel(
                            bullet = !parser.localName.endsWith("number"),
                            format = parser.attr("num-format").orEmpty(),
                            prefix = parser.attr("num-prefix").orEmpty(),
                            suffix = parser.attr("num-suffix").orEmpty()
                        )
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.localName) {
                    "style" -> if (name != null) {
                        into.byName[name!!] = Style(parent, text, align, margin, fill, listStyle, displayName)
                        name = null
                    }
                    "default-style" -> if (inDefault) {
                        into.defaults = into.defaults.merge(text)
                        inDefault = false
                    }
                    "list-style" -> listName = null
                }
            }
            event = parser.next()
        }
    }

    private fun readTextProperties(parser: XmlPullParser): TextProps {
        val weight = parser.attr("font-weight")
        val position = parser.attr("text-position")
        return TextProps(
            bold = weight?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 },
            italic = parser.attr("font-style")?.let { it == "italic" || it == "oblique" },
            underline = parser.attr("text-underline-style")?.let { it != "none" },
            strike = parser.attr("text-line-through-style")?.let { it != "none" },
            size = parser.attr("font-size")?.takeIf { it.endsWith("pt") }
                ?.removeSuffix("pt")?.toDoubleOrNull()?.toInt()?.coerceIn(4, 200),
            font = parser.attr("font-name")?.let { appFontLabel(it) },
            color = colorOf(parser.attr("color")),
            highlight = parser.attr("background-color")?.let { colorOf(it) ?: 0L },
            baseline = position?.let {
                val first = it.trim().substringBefore(' ')
                when {
                    first == "super" -> 1
                    first == "sub" -> -1
                    first.removeSuffix("%").toDoubleOrNull()?.let { v -> v > 0 } == true -> 1
                    first.removeSuffix("%").toDoubleOrNull()?.let { v -> v < 0 } == true -> -1
                    else -> 0
                }
            }
        )
    }

    private fun colorOf(value: String?): Long? {
        if (value == null || !value.startsWith("#")) return null
        return hexToArgb(value, 0L).takeIf { it != 0L }
    }

    private fun alignOf(value: String?): Int? = when (value) {
        "center" -> 1
        "end", "right" -> 2
        "justify" -> 3
        "start", "left" -> 0
        else -> null
    }

    private fun lengthInCm(value: String?): Double? {
        if (value == null) return null
        val number = value.dropLastWhile { it.isLetter() }.toDoubleOrNull() ?: return null
        return when {
            value.endsWith("cm") -> number
            value.endsWith("mm") -> number / 10
            value.endsWith("in") -> number * 2.54
            value.endsWith("pt") -> number * 2.54 / 72
            else -> null
        }
    }

    /** Parcours de `office:text`. */
    private class Body(val styles: Styles) {

        private class Para(val style: String?, val heading: Int) {
            val runs = ArrayList<TextRun>()
            val text = StringBuilder()
            var props = TextProps()
        }

        private class ListFrame(val style: String?, val level: Int) {
            var counter = 0
        }

        private val builder = BlockBuilder()
        private val paragraphs = ArrayList<Para>()
        private val spans = ArrayList<TextProps>()
        private val lists = ArrayList<ListFrame>()
        private var itemStart = false

        fun read(xml: ByteArray): List<DocBlock> {
            val parser = newPullParser(xml)
            var inBody = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.localName
                        if (name == "text" && parser.name.startsWith("office")) inBody = true
                        else if (inBody) start(parser)
                    }
                    XmlPullParser.TEXT -> paragraphs.lastOrNull()?.text?.append(parser.text)
                    XmlPullParser.END_TAG -> if (inBody) end(parser.localName)
                }
                event = parser.next()
            }
            while (paragraphs.isNotEmpty()) finish()
            return builder.build()
        }

        private fun start(parser: XmlPullParser) {
            when (parser.localName) {
                "p", "h" -> {
                    val style = parser.attr("style-name")
                    val level = if (parser.localName == "h") {
                        parser.attr("outline-level")?.toIntOrNull() ?: 1
                    } else styles.headingLevel(style)
                    flushText()
                    val para = Para(style, level)
                    para.props = styles.textProps(style).let {
                        if (level > 0) TextProps(bold = true, size = headingSize(level)).merge(it) else it
                    }
                    paragraphs.add(para)
                }
                "span", "a" -> {
                    flushText()
                    // Seules les propriétés que le style de la portion fixe
                    // lui-même remplacent celles du paragraphe.
                    spans.add(ownProps(parser.attr("style-name")) ?: TextProps())
                }
                "s" -> paragraphs.lastOrNull()?.text?.append(" ".repeat((parser.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 200)))
                "tab" -> paragraphs.lastOrNull()?.text?.append('\t')
                "line-break" -> paragraphs.lastOrNull()?.text?.append('\n')
                "list" -> {
                    val parent = lists.lastOrNull()
                    lists.add(ListFrame(parser.attr("style-name") ?: parent?.style, (parent?.level ?: 0) + 1))
                }
                "list-item", "list-header" -> itemStart = parser.localName == "list-item"
                "table" -> {
                    flushText()
                    builder.startTable()
                }
                "table-header-rows" -> headerRows = true
                "table-row" -> builder.startRow(header = headerRows)
                "table-cell" -> builder.startCell(
                    colSpan = parser.attr("number-columns-spanned")?.toIntOrNull() ?: 1,
                    rowSpan = parser.attr("number-rows-spanned")?.toIntOrNull() ?: 1,
                    fill = styles.cellFill(parser.attr("style-name")) ?: 0L
                )
                // Cellule masquée par une fusion : le constructeur la place lui-même.
                "covered-table-cell" -> parser.skipSubtree()
                // Ni les commentaires, ni les notes de bas de page, ni les
                // modifications suivies, ni les modèles d'index ne sont du texte courant.
                "annotation", "note", "tracked-changes", "sequence-decls", "variable-decls",
                "user-field-decls", "forms", "desc", "title", "index-title-template",
                "table-of-content-source", "alphabetical-index-source", "illustration-index-source",
                "table-index-source", "object-index-source", "user-index-source", "bibliography-source",
                "table-columns", "table-column" -> parser.skipSubtree()
            }
        }

        private var headerRows = false

        private fun end(name: String) {
            when (name) {
                "p", "h" -> finish()
                "span", "a" -> {
                    flushText()
                    spans.removeLastOrNull()
                }
                "list" -> lists.removeLastOrNull()
                "table-header-rows" -> headerRows = false
                "table-cell" -> {
                    flushText()
                    builder.endCell()
                }
                "table-row" -> builder.endRow()
                "table" -> builder.endTable()
            }
        }

        /** Propriétés propres au style d'une portion (sans l'héritage du défaut). */
        private fun ownProps(name: String?): TextProps? {
            if (name == null) return null
            return styles.textProps(name).let { full ->
                // textProps part des valeurs par défaut du document ; pour une
                // portion, seules celles qui diffèrent du défaut comptent.
                val defaults = styles.textProps(null)
                TextProps(
                    bold = full.bold.takeIf { it != defaults.bold },
                    italic = full.italic.takeIf { it != defaults.italic },
                    underline = full.underline.takeIf { it != defaults.underline },
                    strike = full.strike.takeIf { it != defaults.strike },
                    size = full.size.takeIf { it != defaults.size },
                    font = full.font.takeIf { it != defaults.font },
                    color = full.color.takeIf { it != defaults.color },
                    highlight = full.highlight.takeIf { it != defaults.highlight },
                    baseline = full.baseline.takeIf { it != defaults.baseline }
                )
            }
        }

        private fun flushText() {
            val para = paragraphs.lastOrNull() ?: return
            if (para.text.isEmpty()) return
            val props = spans.fold(para.props) { acc, span -> acc.merge(span) }
            para.runs.add(props.toRun(para.text.toString()))
            para.text.setLength(0)
        }

        private fun finish() {
            flushText()
            val para = paragraphs.removeLastOrNull() ?: return
            val runs = ArrayList<TextRun>()
            val list = lists.lastOrNull()
            var indent = ((styles.marginLeft(para.style) ?: 0.0) / 1.27 + 0.4).toInt()
            if (list != null) {
                indent = list.level - 1
                val listStyle = list.style ?: styles.listStyle(para.style)
                val level = listStyle?.let { styles.lists[it] }?.let { it[list.level] ?: it[1] }
                if (itemStart) {
                    list.counter++
                    val marker = when {
                        level == null || level.bullet -> when ((list.level - 1) % 3) {
                            0 -> "• "
                            1 -> "◦ "
                            else -> "▪ "
                        }
                        level.format.isEmpty() -> ""
                        else -> level.prefix + formatNumber(list.counter, level.format) + level.suffix + " "
                    }
                    if (marker.isNotEmpty()) {
                        runs.add((para.runs.firstOrNull() ?: para.props.toRun("")).copy(text = marker))
                    }
                    itemStart = false
                }
            }
            runs.addAll(Docx.mergeRuns(para.runs))
            builder.paragraph(
                TextParagraph(
                    runs = runs,
                    align = styles.align(para.style) ?: 0,
                    heading = para.heading.coerceIn(0, 3),
                    indent = indent.coerceIn(0, 8)
                )
            )
        }

        private fun headingSize(level: Int) = when (level) {
            1 -> 20
            2 -> 16
            else -> 13
        }

        private fun formatNumber(value: Int, format: String): String = when (format) {
            "a" -> ('a' + ((value - 1) % 26)).toString()
            "A" -> ('A' + ((value - 1) % 26)).toString()
            "i" -> roman(value).lowercase()
            "I" -> roman(value)
            else -> value.toString()
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
    }
}
