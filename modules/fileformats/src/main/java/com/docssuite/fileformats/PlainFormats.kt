package com.docssuite.fileformats

/** Formats texte : CSV/TSV, TXT, RTF, HTML et Markdown. */

// ---------------------------------------------------------------- CSV

object Csv {

    /** Le point-virgule est le séparateur attendu par Excel en configuration française. */
    fun write(sheet: Sheet, separator: Char = ';', display: (String) -> String): String =
        (0 until sheet.rows).joinToString("\r\n") { row ->
            (0 until sheet.columns).joinToString(separator.toString()) { column ->
                escape(display(CellRef.key(row, column)), separator)
            }
        }

    private fun escape(value: String, separator: Char): String =
        if (value.any { it == separator || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun read(text: String, name: String = "Feuille1"): Sheet {
        val separator = detectSeparator(text)
        val rows = parse(text, separator)
        val cells = LinkedHashMap<String, String>()
        var maxColumn = 0
        rows.forEachIndexed { row, values ->
            values.forEachIndexed { column, value ->
                if (value.isNotEmpty()) cells[CellRef.key(row, column)] = value
            }
            maxColumn = maxOf(maxColumn, values.size)
        }
        return Sheet(
            name = name,
            cells = cells,
            columns = maxOf(maxColumn, 12),
            rows = maxOf(rows.size, 40)
        )
    }

    /** On prend le séparateur le plus fréquent hors guillemets sur la 1re ligne. */
    private fun detectSeparator(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ';'
        var quoted = false
        val counts = HashMap<Char, Int>()
        firstLine.forEach { ch ->
            if (ch == '"') quoted = !quoted
            else if (!quoted && ch in listOf(';', ',', '\t', '|')) {
                counts[ch] = (counts[ch] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: ';'
    }

    private fun parse(text: String, separator: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && text.getOrNull(index + 1) == '"' -> {
                    field.append('"'); index++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == separator -> {
                    row.add(field.toString()); field.setLength(0)
                }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(index + 1) == '\n') index++
                    row.add(field.toString()); field.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> field.append(ch)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}

// ---------------------------------------------------------------- RTF

object Rtf {

    fun write(document: TextDocument): String {
        val sb = StringBuilder("{\\rtf1\\ansi\\ansicpg1252\\deff0\n")

        val allParagraphs = document.blocks.flatMap { block ->
            when (block) {
                is TextParagraph -> listOf(block)
                is TextTable -> block.rows.flatMap { row -> row.cells.flatMap { it.paragraphs } }
            }
        }
        val fonts = LinkedHashMap<String, Int>()
        val colors = LinkedHashMap<Long, Int>()
        allParagraphs.forEach { paragraph ->
            paragraph.runs.forEach { run ->
                fonts.getOrPut(officeFontName(run.fontName)) { fonts.size }
                colors.getOrPut(run.color) { colors.size + 1 }
                if (run.highlight != 0L) colors.getOrPut(run.highlight) { colors.size + 1 }
            }
        }
        document.blocks.filterIsInstance<TextTable>().forEach { table ->
            table.rows.forEach { row -> row.cells.forEach { if (it.fill != 0L) colors.getOrPut(it.fill) { colors.size + 1 } } }
        }
        colors.getOrPut(0xFFA0A7B4L) { colors.size + 1 }
        if (fonts.isEmpty()) fonts["Calibri"] = 0

        sb.append("{\\fonttbl")
        fonts.forEach { (name, index) -> sb.append("{\\f$index\\fnil $name;}") }
        sb.append("}\n{\\colortbl ;")
        colors.keys.forEach { color ->
            sb.append("\\red${(color shr 16) and 0xFF}")
            sb.append("\\green${(color shr 8) and 0xFF}")
            sb.append("\\blue${color and 0xFF};")
        }
        sb.append("}\n")
        sb.append("{\\stylesheet{\\s0 Normal;}{\\s1\\outlinelevel0 heading 1;}{\\s2\\outlinelevel1 heading 2;}{\\s3\\outlinelevel2 heading 3;}}\n")

        fun paragraphProps(paragraph: TextParagraph): String = buildString {
            if (paragraph.heading in 1..3) append("\\s${paragraph.heading}\\outlinelevel${paragraph.heading - 1}")
            append(
                when (paragraph.align) {
                    1 -> "\\qc"
                    2 -> "\\qr"
                    3 -> "\\qj"
                    else -> "\\ql"
                }
            )
            if (paragraph.indent > 0) append("\\li${paragraph.indent * 720}")
            if (document.lineSpacing != 100) append("\\sl${240 * document.lineSpacing / 100}\\slmult1")
        }

        fun runs(paragraph: TextParagraph) {
            paragraph.runs.forEach { run ->
                sb.append("{")
                sb.append("\\f${fonts[officeFontName(run.fontName)] ?: 0}")
                // Le RTF compte les tailles en demi-points.
                sb.append("\\fs${run.size * 2}")
                sb.append("\\cf${colors[run.color] ?: 0}")
                if (run.highlight != 0L) sb.append("\\highlight${colors[run.highlight] ?: 0}")
                if (run.bold) sb.append("\\b")
                if (run.italic) sb.append("\\i")
                if (run.underline) sb.append("\\ul")
                if (run.strike) sb.append("\\strike")
                if (run.baseline == 1) sb.append("\\super")
                if (run.baseline == -1) sb.append("\\sub")
                sb.append(" ").append(escape(run.text)).append("}")
            }
        }

        val border = colors[0xFFA0A7B4L] ?: 0
        document.blocks.forEach { block ->
            when (block) {
                is TextParagraph -> {
                    sb.append("\\pard").append(paragraphProps(block)).append(' ')
                    runs(block)
                    sb.append("\\par\n")
                }
                is TextTable -> {
                    val table = block.normalized()
                    val width = 9070 / table.columnCount.coerceAtLeast(1)
                    table.rows.forEachIndexed { rowIndex, row ->
                        sb.append("\\trowd\\trgaph108")
                        if (rowIndex == 0 && table.headerRow) sb.append("\\trhdr")
                        var right = 0
                        var column = 0
                        row.cells.forEach { cell ->
                            val below = table.rows.getOrNull(rowIndex + 1)?.let { cellAtColumn(it, column) }
                            repeat(cell.colSpan) { part ->
                                if (cell.mergedAbove) sb.append("\\clvmrg")
                                else if (below?.mergedAbove == true) sb.append("\\clvmgf")
                                if (cell.colSpan > 1) sb.append(if (part == 0) "\\clmgf" else "\\clmrg")
                                for (side in listOf("t", "l", "b", "r")) sb.append("\\clbrdr$side\\brdrs\\brdrw10\\brdrcf$border")
                                if (cell.fill != 0L) sb.append("\\clcbpat${colors[cell.fill] ?: 0}")
                                right += width
                                sb.append("\\cellx$right")
                            }
                            column += cell.colSpan
                        }
                        sb.append('\n')
                        row.cells.forEach { cell ->
                            val paragraphs = if (cell.mergedAbove) listOf(TextParagraph()) else cell.paragraphs.ifEmpty { listOf(TextParagraph()) }
                            paragraphs.forEachIndexed { index, paragraph ->
                                sb.append("\\pard\\intbl").append(paragraphProps(paragraph)).append(' ')
                                runs(paragraph)
                                if (index < paragraphs.size - 1) sb.append("\\par ")
                            }
                            sb.append("\\cell")
                            // Une cellule fusionnée sur plusieurs colonnes en occupe
                            // autant dans la définition : il faut autant de \cell.
                            repeat(cell.colSpan - 1) { sb.append("\\pard\\intbl\\cell") }
                            sb.append('\n')
                        }
                        sb.append("\\row\n")
                    }
                    sb.append("\\pard\n")
                }
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun cellAtColumn(row: TableRow, column: Int): TableCell? {
        var position = 0
        for (cell in row.cells) {
            if (position == column) return cell
            position += cell.colSpan
            if (position > column) return null
        }
        return null
    }

    private fun escape(text: String): String = buildString {
        text.forEach { ch ->
            when {
                ch == '\\' || ch == '{' || ch == '}' -> append('\\').append(ch)
                ch == '\n' -> append("\\line ")
                ch == '\t' -> append("\\tab ")
                // Hors ASCII, le RTF veut l'unité de code en décimal signé.
                ch.code > 127 -> append("\\u${ch.code.toShort()}?")
                else -> append(ch)
            }
        }
    }

    fun read(text: String, title: String = "Document"): TextDocument = RtfReader(text).read(title)
}

// ---------------------------------------------------------------- HTML

object Html {

    fun write(document: TextDocument): String {
        fun paragraphHtml(paragraph: TextParagraph): String {
            val align = when (paragraph.align) {
                1 -> "center"
                2 -> "right"
                3 -> "justify"
                else -> "left"
            }
            val runs = paragraph.runs.joinToString("") { run ->
                val style = buildList {
                    add("font-size:${run.size}pt")
                    add("color:#${run.color.toHexRgb()}")
                    add("font-family:'${officeFontName(run.fontName)}'")
                    if (run.bold) add("font-weight:bold")
                    if (run.italic) add("font-style:italic")
                    if (run.highlight != 0L) add("background-color:#${run.highlight.toHexRgb()}")
                    val decorations = listOfNotNull(
                        "underline".takeIf { run.underline },
                        "line-through".takeIf { run.strike }
                    )
                    if (decorations.isNotEmpty()) add("text-decoration:${decorations.joinToString(" ")}")
                }.joinToString(";")
                val text = escape(run.text).replace("\n", "<br/>").replace("\t", "&emsp;")
                val span = "<span style=\"$style\">$text</span>"
                when (run.baseline) {
                    1 -> "<sup>$span</sup>"
                    -1 -> "<sub>$span</sub>"
                    else -> span
                }
            }
            val tag = if (paragraph.heading in 1..3) "h${paragraph.heading}" else "p"
            val indent = if (paragraph.indent > 0) ";margin-left:${paragraph.indent * 2}em" else ""
            return "<$tag style=\"text-align:$align;margin:0 0 .6em 0$indent\">${runs.ifEmpty { "<br/>" }}</$tag>"
        }

        val body = document.blocks.joinToString("\n") { block ->
            when (block) {
                is TextParagraph -> paragraphHtml(block)
                is TextTable -> {
                    val table = block.normalized()
                    val rows = table.rows.mapIndexed { rowIndex, row ->
                        var column = 0
                        val cells = row.cells.mapNotNull { cell ->
                            val start = column
                            column += cell.colSpan
                            if (cell.mergedAbove) return@mapNotNull null
                            val rowSpan = 1 + table.rows.drop(rowIndex + 1)
                                .takeWhile { below -> cellStartingAt(below, start)?.mergedAbove == true }.size
                            val tag = if (rowIndex == 0 && table.headerRow) "th" else "td"
                            val attributes = buildString {
                                if (cell.colSpan > 1) append(" colspan=\"${cell.colSpan}\"")
                                if (rowSpan > 1) append(" rowspan=\"$rowSpan\"")
                                append(" style=\"border:1px solid #a0a7b4;padding:4px 8px;vertical-align:top")
                                if (cell.fill != 0L) append(";background:#${cell.fill.toHexRgb()}")
                                append("\"")
                            }
                            "<$tag$attributes>${cell.paragraphs.joinToString("") { paragraphHtml(it) }}</$tag>"
                        }
                        "<tr>${cells.joinToString("")}</tr>"
                    }
                    "<table style=\"border-collapse:collapse;width:100%;margin:0 0 .8em 0\">${rows.joinToString("\n")}</table>"
                }
            }
        }
        val lineHeight = 1.5 * document.lineSpacing / 100
        return """
            <!DOCTYPE html>
            <html lang="fr"><head><meta charset="utf-8"/>
            <title>${escape(document.title)}</title>
            <style>body{margin:2.5em auto;max-width:46em;padding:0 1em;line-height:$lineHeight}</style>
            </head><body>
            $body
            </body></html>
        """.trimIndent()
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

    fun write(sheet: Sheet, display: (String) -> String): String {
        val rows = (0 until sheet.rows).joinToString("\n") { row ->
            val cells = (0 until sheet.columns).joinToString("") { column ->
                val ref = CellRef.key(row, column)
                val style = sheet.styles[ref]
                val css = buildList {
                    add("border:1px solid #d0d5dd")
                    add("padding:4px 8px")
                    if (style?.bold == true) add("font-weight:bold")
                    if (style?.italic == true) add("font-style:italic")
                    if (style != null) add("color:#${style.color.toHexRgb()}")
                    if (style != null && style.background != 0L) {
                        add("background:#${style.background.toHexRgb()}")
                    }
                    when (style?.align ?: 0) {
                        1 -> add("text-align:left")
                        2 -> add("text-align:center")
                        3 -> add("text-align:right")
                    }
                }.joinToString(";")
                "<td style=\"$css\">${escape(display(ref))}</td>"
            }
            "<tr>$cells</tr>"
        }
        return """
            <!DOCTYPE html>
            <html lang="fr"><head><meta charset="utf-8"/><title>${escape(sheet.name)}</title></head>
            <body><table style="border-collapse:collapse;font-family:sans-serif;font-size:13px">
            $rows
            </table></body></html>
        """.trimIndent()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun read(html: String, title: String = "Document"): TextDocument = HtmlReader(html).read(title)

    /** Lecture depuis les octets : le jeu de caractères est annoncé dans la page. */
    fun read(bytes: ByteArray, title: String = "Document"): TextDocument =
        HtmlReader(HtmlReader.decode(bytes)).read(title)
}

// ---------------------------------------------------------------- Markdown

object Markdown {

    fun write(document: TextDocument): String = buildString {
        append("# ").append(document.title).append("\n\n")
        document.blocks.forEach { block ->
            when (block) {
                is TextParagraph -> {
                    if (block.heading in 1..3) append("#".repeat(block.heading + 1)).append(' ')
                    else if (block.indent > 0) append("  ".repeat(block.indent))
                    append(inline(block))
                    append("\n\n")
                }
                is TextTable -> {
                    val table = block.normalized()
                    val columns = table.columnCount.coerceAtLeast(1)
                    fun rowText(row: TableRow): String {
                        val cells = ArrayList<String>()
                        row.cells.forEach { cell ->
                            cells.add(
                                if (cell.mergedAbove) ""
                                else cell.paragraphs.joinToString("<br>") { inline(it) }.replace("|", "\\|")
                            )
                            repeat(cell.colSpan - 1) { cells.add("") }
                        }
                        return cells.joinToString(" | ", "| ", " |")
                    }
                    // Un tableau Markdown a toujours une ligne d'en-tête.
                    append(rowText(table.rows.first())).append('\n')
                    append((0 until columns).joinToString(" | ", "| ", " |") { "---" }).append('\n')
                    table.rows.drop(1).forEach { append(rowText(it)).append('\n') }
                    append('\n')
                }
            }
        }
    }.trimEnd() + "\n"

    private fun inline(paragraph: TextParagraph): String = buildString {
        paragraph.runs.forEach { run ->
            var text = run.text
            if (text.isEmpty()) return@forEach
            if (run.strike) text = "~~$text~~"
            if (run.bold && run.italic) text = "***$text***"
            else if (run.bold) text = "**$text**"
            else if (run.italic) text = "*$text*"
            if (run.baseline == 1) text = "<sup>$text</sup>"
            if (run.baseline == -1) text = "<sub>$text</sub>"
            append(text)
        }
    }

    fun write(sheet: Sheet, display: (String) -> String): String = buildString {
        val columns = sheet.columns
        append((0 until columns).joinToString(" | ", "| ", " |") { CellRef.columnLabel(it) })
        append("\n")
        append((0 until columns).joinToString(" | ", "| ", " |") { "---" })
        append("\n")
        (0 until sheet.rows).forEach { row ->
            append(
                (0 until columns).joinToString(" | ", "| ", " |") { column ->
                    display(CellRef.key(row, column)).replace("|", "\\|")
                }
            )
            append("\n")
        }
    }

    fun write(deck: Deck): String = buildString {
        deck.slides.forEachIndexed { index, slide ->
            if (index > 0) append("\n---\n\n")
            if (slide.title.isNotBlank()) append("## ").append(slide.title).append("\n\n")
            if (slide.content.isNotBlank()) append(slide.content).append("\n")
        }
    }

    fun read(markdown: String, title: String = "Document"): TextDocument {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = ArrayList<DocBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            // Tableau GFM : une ligne à barres suivie d'une ligne de tirets.
            if (line.trim().startsWith("|") && index + 1 < lines.size &&
                Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$").matches(lines[index + 1])
            ) {
                val rows = ArrayList<TableRow>()
                rows.add(tableRow(line))
                index += 2
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    rows.add(tableRow(lines[index])); index++
                }
                blocks.add(TextTable(rows, headerRow = true).normalized())
                continue
            }
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").find(line)
            blocks.add(
                when {
                    heading != null -> {
                        val level = heading.groupValues[1].length
                        TextParagraph(
                            parseInline(heading.groupValues[2]).map {
                                it.copy(bold = true, size = (30 - level * 3).coerceAtLeast(16))
                            },
                            heading = level.coerceAtMost(3)
                        )
                    }
                    bullet != null -> TextParagraph(
                        listOf(TextRun("• ")) + parseInline(bullet.groupValues[2]),
                        indent = (bullet.groupValues[1].length / 2).coerceAtMost(8)
                    )
                    else -> TextParagraph(parseInline(line))
                }
            )
            index++
        }
        return TextDocument(title, blocks.ifEmpty { listOf(TextParagraph()) })
    }

    private fun tableRow(line: String): TableRow {
        val cells = line.trim().removePrefix("|").removeSuffix("|")
            .split(Regex("(?<!\\\\)\\|"))
            .map { raw ->
                TableCell(
                    paragraphs = raw.trim().replace("\\|", "|").split(Regex("<br\\s*/?>"))
                        .map { TextParagraph(parseInline(it.trim())) }
                )
            }
        return TableRow(cells)
    }

    private val emphasis = Regex("\\*\\*\\*(.+?)\\*\\*\\*|\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|~~(.+?)~~")

    private fun parseInline(line: String): List<TextRun> {
        val runs = ArrayList<TextRun>()
        var cursor = 0
        emphasis.findAll(line).forEach { match ->
            if (match.range.first > cursor) {
                runs.add(TextRun(line.substring(cursor, match.range.first)))
            }
            val groups = match.groupValues
            runs.add(
                when {
                    groups[1].isNotEmpty() -> TextRun(groups[1], bold = true, italic = true)
                    groups[2].isNotEmpty() -> TextRun(groups[2], bold = true)
                    groups[3].isNotEmpty() -> TextRun(groups[3], italic = true)
                    else -> TextRun(groups[4], strike = true)
                }
            )
            cursor = match.range.last + 1
        }
        if (cursor < line.length) runs.add(TextRun(line.substring(cursor)))
        return runs.ifEmpty { listOf(TextRun("")) }
    }
}
