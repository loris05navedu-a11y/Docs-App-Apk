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

        val fonts = LinkedHashMap<String, Int>()
        val colors = LinkedHashMap<Long, Int>()
        document.paragraphs.forEach { paragraph ->
            paragraph.runs.forEach { run ->
                fonts.getOrPut(officeFontName(run.fontName)) { fonts.size }
                colors.getOrPut(run.color) { colors.size + 1 }
                if (run.highlight != 0L) colors.getOrPut(run.highlight) { colors.size + 1 }
            }
        }
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

        document.paragraphs.forEach { paragraph ->
            sb.append(
                when (paragraph.align) {
                    1 -> "\\qc"
                    2 -> "\\qr"
                    3 -> "\\qj"
                    else -> "\\ql"
                }
            )
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
                sb.append(" ").append(escape(run.text)).append("}")
            }
            sb.append("\\par\n")
        }
        sb.append("}")
        return sb.toString()
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

    fun read(text: String, title: String = "Document"): TextDocument {
        val paragraphs = ArrayList<TextParagraph>()
        val line = StringBuilder()
        var index = 0
        var skipDepth = -1
        var depth = 0

        fun flush() {
            paragraphs.add(TextParagraph(listOf(TextRun(line.toString().trim()))))
            line.setLength(0)
        }

        while (index < text.length) {
            when (val ch = text[index]) {
                '{' -> {
                    depth++; index++
                }
                '}' -> {
                    depth--
                    // On ne ressort d'un groupe ignoré qu'une fois repassé
                    // au-dessous de sa profondeur : ses sous-groupes ne
                    // doivent pas rouvrir la lecture du texte.
                    if (skipDepth >= 0 && depth < skipDepth) skipDepth = -1
                    index++
                }
                '\\' -> {
                    val start = ++index
                    while (index < text.length && text[index].isLetter()) index++
                    val word = text.substring(start, index)
                    val numberStart = index
                    if (index < text.length && (text[index] == '-' || text[index].isDigit())) {
                        index++
                        while (index < text.length && text[index].isDigit()) index++
                    }
                    val argument = text.substring(numberStart, index).toIntOrNull()
                    if (index < text.length && text[index] == ' ') index++

                    when (word) {
                        "par", "line" -> if (skipDepth < 0) flush()
                        "tab" -> if (skipDepth < 0) line.append('\t')
                        "u" -> if (skipDepth < 0 && argument != null) {
                            line.append(argument.toChar())
                            // Le caractère de repli qui suit ne doit pas être gardé.
                            if (index < text.length && text[index] == '?') index++
                        }
                        // Ces groupes portent des métadonnées, pas du texte.
                        "fonttbl", "colortbl", "stylesheet", "info", "pict", "generator" ->
                            skipDepth = depth
                        "'" -> Unit
                        else -> if (word.isEmpty() && index < text.length) {
                            if (text[index] == '\'') {
                                val hex = text.substring(index + 1, minOf(index + 3, text.length))
                                hex.toIntOrNull(16)?.let {
                                    if (skipDepth < 0) line.append(cp1252Of(it))
                                }
                                index += 3
                            } else {
                                if (skipDepth < 0) line.append(text[index])
                                index++
                            }
                        }
                    }
                }
                '\r', '\n' -> index++
                else -> {
                    if (skipDepth < 0) line.append(ch)
                    index++
                }
            }
        }
        if (line.isNotEmpty()) flush()
        return TextDocument(title, paragraphs.ifEmpty { listOf(TextParagraph()) })
    }

    private fun cp1252Of(code: Int): Char =
        String(byteArrayOf(code.toByte()), CP1252).firstOrNull() ?: ' '
}

// ---------------------------------------------------------------- HTML

object Html {

    fun write(document: TextDocument): String {
        val body = document.paragraphs.joinToString("\n") { paragraph ->
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
                "<span style=\"$style\">${escape(run.text).replace("\n", "<br/>")}</span>"
            }
            "<p style=\"text-align:$align;margin:0 0 .6em 0\">${runs.ifEmpty { "<br/>" }}</p>"
        }
        return """
            <!DOCTYPE html>
            <html lang="fr"><head><meta charset="utf-8"/>
            <title>${escape(document.title)}</title>
            <style>body{margin:2.5em auto;max-width:46em;padding:0 1em;line-height:1.5}</style>
            </head><body>
            $body
            </body></html>
        """.trimIndent()
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

    /** Lecture rudimentaire : on retire le balisage en gardant les paragraphes. */
    fun read(html: String, title: String = "Document"): TextDocument {
        val withBreaks = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
        val text = withBreaks
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'")
        val paragraphs = text.split('\n')
            .map { it.trim() }
            .dropLastWhile { it.isEmpty() }
            .map { TextParagraph(listOf(TextRun(it))) }
        return TextDocument(title, paragraphs.ifEmpty { listOf(TextParagraph()) })
    }
}

// ---------------------------------------------------------------- Markdown

object Markdown {

    fun write(document: TextDocument): String = buildString {
        append("# ").append(document.title).append("\n\n")
        document.paragraphs.forEach { paragraph ->
            paragraph.runs.forEach { run ->
                var text = run.text
                if (text.isEmpty()) return@forEach
                if (run.strike) text = "~~$text~~"
                if (run.bold && run.italic) text = "***$text***"
                else if (run.bold) text = "**$text**"
                else if (run.italic) text = "*$text*"
                append(text)
            }
            append("\n\n")
        }
    }.trimEnd() + "\n"

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
        val paragraphs = markdown.split('\n').map { line ->
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                TextParagraph(
                    listOf(
                        TextRun(
                            heading.groupValues[2],
                            bold = true,
                            size = (30 - level * 3).coerceAtLeast(16)
                        )
                    )
                )
            } else {
                TextParagraph(parseInline(line))
            }
        }
        return TextDocument(title, paragraphs.ifEmpty { listOf(TextParagraph()) })
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
