package com.docssuite.fileformats

import java.nio.charset.Charset

/**
 * Lecteur RTF. Word et LibreOffice écrivent, avant le texte, des tables de
 * polices, de couleurs, de styles, de listes, un thème en hexadécimal… Tout
 * groupe marqué `\*` ou connu pour ne pas être du texte est ignoré ; les
 * tableaux sont reconstruits à partir de `\cell` et `\row`.
 */
internal class RtfReader(private val source: String) {

    private data class CharProps(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val halfPoints: Int = 24,
        val font: Int = -1,
        val color: Int = 0,
        val highlight: Int = 0,
        val baseline: Int = 0
    )

    private class Group(
        var chars: CharProps,
        var skip: Boolean,
        var uc: Int,
        var listText: Boolean
    ) {
        fun copy() = Group(chars, skip, uc, listText)
    }

    /** Définition d'une cellule, lue dans `\trowd` avant son contenu. */
    private class CellDef(val horizontal: Int, val vertical: Int, val fill: Int, var right: Int)

    private var index = 0
    private val stack = ArrayList<Group>()
    private var group = Group(CharProps(), skip = false, uc = 1, listText = false)

    private var charset: Charset = CP1252
    private val fonts = HashMap<Int, String>()
    private val colors = ArrayList<Long>()
    private val styleNames = HashMap<Int, String>()

    private val builder = BlockBuilder()
    private val runs = ArrayList<TextRun>()
    private val buffer = StringBuilder()
    private var bufferProps = CharProps()
    private val listPrefix = StringBuilder()

    // Propriétés du paragraphe en cours.
    private var align = 0
    private var indentTwips = 0
    private var inTable = false
    private var styleIndex = -1
    private var outline = -1

    // Tableau en cours.
    private var pendingHorizontal = 0
    private var pendingVertical = 0
    private var pendingFill = 0
    private val rowDefs = ArrayList<CellDef>()
    private val rowCells = ArrayList<List<TextParagraph>>()
    private val cellParagraphs = ArrayList<TextParagraph>()
    private val pendingRows = ArrayList<GridRow>()
    private var headerRow = false

    fun read(title: String): TextDocument {
        readTables()
        while (index < source.length) {
            when (val ch = source[index]) {
                '{' -> {
                    index++
                    stack.add(group.copy())
                    group = group.copy()
                    // `{\*\motclé …}` : destination facultative, à ignorer si on
                    // ne la connaît pas — c'est la règle même du format.
                    if (source.startsWith("\\*", index)) group.skip = true
                }
                '}' -> {
                    index++
                    val closing = group
                    group = stack.removeLastOrNull() ?: group
                    if (closing.listText && !group.listText) {
                        flushBuffer()
                        emitListPrefix()
                    }
                }
                '\\' -> controlWord()
                '\r', '\n' -> index++
                else -> {
                    index++
                    appendText(ch.toString())
                }
            }
        }
        endParagraph(force = false)
        flushTable()
        val blocks = builder.build()
        return TextDocument(title, blocks.ifEmpty { listOf(TextParagraph()) })
    }

    // ------------------------------------------------------------ mots de contrôle

    private fun controlWord() {
        index++ // la barre oblique
        if (index >= source.length) return
        val first = source[index]
        if (!first.isLetter()) {
            index++
            when (first) {
                '\'' -> {
                    val hex = source.substring(index, minOf(index + 2, source.length))
                    index += hex.length
                    hex.toIntOrNull(16)?.let { appendText(String(byteArrayOf(it.toByte()), charset)) }
                }
                '~' -> appendText(" ")
                '_' -> appendText("-")
                '-' -> Unit
                '*' -> group.skip = true
                '\\', '{', '}' -> appendText(first.toString())
                '\r', '\n' -> endParagraph(force = true)
                else -> Unit
            }
            return
        }
        val start = index
        while (index < source.length && source[index].isLetter()) index++
        val word = source.substring(start, index)
        val numberStart = index
        if (index < source.length && (source[index] == '-' || source[index].isDigit())) {
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        val argument = source.substring(numberStart, index).toIntOrNull()
        if (index < source.length && source[index] == ' ') index++
        handle(word, argument)
    }

    private fun handle(word: String, arg: Int?) {
        when (word) {
            // Destinations sans texte courant.
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "listtable", "listoverridetable",
            "revtbl", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
            "datastore", "latentstyles", "pgdsctbl", "mmathPr", "header", "headerl", "headerr",
            "headerf", "footer", "footerl", "footerr", "footerf", "footnote", "fldinst", "filetbl",
            "userprops", "docvar", "nonshppict", "shppict", "shp", "object", "template", "annotation",
            "atnid", "atnauthor", "bkmkstart", "bkmkend", "pn", "pnseclvl", "operator", "title",
            "author", "subject", "keywords", "comment", "doccomm", "company", "falt", "panose",
            "wgrffmtfilter", "xe", "tc" -> group.skip = true
            "listtext", "pntext" -> group.listText = true
            "ansicpg" -> arg?.let { code ->
                charset = runCatching { Charset.forName("windows-$code") }.getOrDefault(CP1252)
            }
            "uc" -> group.uc = (arg ?: 1).coerceIn(0, 4)
            "u" -> arg?.let { value ->
                val code = if (value < 0) value + 65536 else value
                appendText(code.toChar().toString())
                skipFallback()
            }
            "bin" -> index = (index + (arg ?: 0)).coerceAtMost(source.length)

            // Paragraphes.
            "par", "sect", "page" -> endParagraph(force = true)
            "pard" -> {
                align = 0; indentTwips = 0; inTable = false; styleIndex = -1; outline = -1
            }
            "ql" -> align = 0
            "qc" -> align = 1
            "qr" -> align = 2
            "qj", "qd" -> align = 3
            "li", "lin" -> indentTwips = arg ?: 0
            "s" -> styleIndex = arg ?: -1
            "outlinelevel" -> outline = arg ?: -1
            "intbl" -> inTable = true

            // Tableaux.
            "trowd" -> {
                rowDefs.clear()
                pendingHorizontal = 0; pendingVertical = 0; pendingFill = 0
                headerRow = false
            }
            "trhdr" -> headerRow = true
            "clmgf" -> pendingHorizontal = 1
            "clmrg" -> pendingHorizontal = 2
            "clvmgf" -> pendingVertical = 1
            "clvmrg" -> pendingVertical = 2
            "clcbpat" -> pendingFill = arg ?: 0
            "cellx" -> {
                rowDefs.add(CellDef(pendingHorizontal, pendingVertical, pendingFill, arg ?: 0))
                pendingHorizontal = 0; pendingVertical = 0; pendingFill = 0
            }
            "cell" -> {
                endParagraph(force = false)
                rowCells.add(cellParagraphs.toList())
                cellParagraphs.clear()
            }
            "row" -> endRow()
            "nestcell" -> appendText(" | ")
            "nestrow" -> appendText("\n")

            // Caractères.
            "plain" -> setChars(CharProps(font = group.chars.font))
            "b" -> setChars(group.chars.copy(bold = arg != 0))
            "i" -> setChars(group.chars.copy(italic = arg != 0))
            "ul", "uld", "uldb", "ulw", "ulth", "uldash", "ulwave" -> setChars(group.chars.copy(underline = arg != 0))
            "ulnone" -> setChars(group.chars.copy(underline = false))
            "strike", "striked" -> setChars(group.chars.copy(strike = arg != 0))
            "fs" -> setChars(group.chars.copy(halfPoints = (arg ?: 24).coerceIn(8, 400)))
            "f" -> setChars(group.chars.copy(font = arg ?: -1))
            "cf" -> setChars(group.chars.copy(color = arg ?: 0))
            "highlight", "cb", "chcbpat" -> setChars(group.chars.copy(highlight = arg ?: 0))
            "super" -> setChars(group.chars.copy(baseline = 1))
            "sub" -> setChars(group.chars.copy(baseline = -1))
            "nosupersub" -> setChars(group.chars.copy(baseline = 0))
            "up" -> setChars(group.chars.copy(baseline = if ((arg ?: 6) > 0) 1 else 0))
            "dn" -> setChars(group.chars.copy(baseline = if ((arg ?: 6) > 0) -1 else 0))

            // Caractères nommés.
            "line" -> appendText("\n")
            "tab" -> appendText("\t")
            "emdash" -> appendText("—")
            "endash" -> appendText("–")
            "bullet" -> appendText("•")
            "lquote" -> appendText("‘")
            "rquote" -> appendText("’")
            "ldblquote" -> appendText("“")
            "rdblquote" -> appendText("”")
            "emspace", "enspace", "qmspace" -> appendText(" ")
        }
    }

    /** Après `\uN`, le lecteur doit passer le caractère de repli qui suit. */
    private fun skipFallback() {
        var remaining = group.uc
        while (remaining > 0 && index < source.length) {
            when {
                source.startsWith("\\'", index) -> index += 4
                source[index] == '\\' || source[index] == '{' || source[index] == '}' -> return
                source[index] == '\r' || source[index] == '\n' -> {
                    index++; continue
                }
                else -> index++
            }
            remaining--
        }
    }

    private fun setChars(props: CharProps) {
        group.chars = props
    }

    // ------------------------------------------------------------ texte

    private fun appendText(text: String) {
        if (group.skip) return
        if (group.listText) {
            listPrefix.append(text)
            return
        }
        if (buffer.isNotEmpty() && bufferProps != group.chars) flushBuffer()
        if (buffer.isEmpty()) bufferProps = group.chars
        buffer.append(text)
    }

    private fun flushBuffer() {
        if (buffer.isEmpty()) return
        runs.add(toRun(buffer.toString(), bufferProps))
        buffer.setLength(0)
    }

    /**
     * Le texte d'une puce (`\listtext`) : un symbole dans la police Symbol,
     * illisible tel quel, ou un numéro. On garde le numéro, on remplace le
     * symbole par une vraie puce.
     */
    private fun emitListPrefix() {
        val raw = listPrefix.toString().replace("\t", "").trim()
        listPrefix.setLength(0)
        if (raw.isEmpty()) return
        val prefix = if (raw.any { it.isLetterOrDigit() }) "$raw " else "• "
        runs.add(toRun(prefix, group.chars))
    }

    private fun toRun(text: String, props: CharProps) = TextRun(
        text = text,
        bold = props.bold,
        italic = props.italic,
        underline = props.underline,
        strike = props.strike,
        size = (props.halfPoints / 2).coerceIn(4, 200),
        fontName = fonts[props.font]?.let { appFontLabel(it) }.orEmpty(),
        color = colors.getOrNull(props.color - 1) ?: 0xFF1A1A1AL,
        highlight = if (props.highlight > 0) colors.getOrNull(props.highlight - 1) ?: 0L else 0L,
        baseline = props.baseline
    )

    private fun endParagraph(force: Boolean) {
        flushBuffer()
        if (!force && runs.isEmpty()) return
        val heading = when {
            outline in 0..8 -> outline + 1
            else -> headingFromStyle(styleNames[styleIndex])
        }
        val paragraph = TextParagraph(
            runs = Docx.mergeRuns(runs),
            align = align,
            heading = heading.coerceAtMost(3),
            indent = ((indentTwips + 360) / 720).coerceIn(0, 8)
        )
        runs.clear()
        if (inTable) {
            cellParagraphs.add(paragraph)
        } else {
            flushTable()
            builder.paragraph(paragraph)
        }
    }

    private fun endRow() {
        if (cellParagraphs.isNotEmpty() || runs.isNotEmpty() || buffer.isNotEmpty()) {
            endParagraph(force = false)
            if (cellParagraphs.isNotEmpty()) {
                rowCells.add(cellParagraphs.toList()); cellParagraphs.clear()
            }
        }
        if (rowCells.isEmpty()) return
        // Une cellule « fusionnée avec la précédente » (ancienne manière de
        // Word) s'ajoute à celle-ci ; la manière récente est une cellule plus
        // large, que la grille commune retrouve.
        val cells = ArrayList<List<TextParagraph>>()
        val defs = ArrayList<CellDef>()
        rowCells.forEachIndexed { i, content ->
            val def = rowDefs.getOrNull(i)
            if (def?.horizontal == 2 && defs.isNotEmpty()) {
                defs.last().right = def.right
            } else {
                cells.add(content)
                defs.add(def ?: CellDef(0, 0, 0, 0))
            }
        }
        val knownEdges = rowDefs.size == rowCells.size && defs.all { it.right > 0 }
        pendingRows.add(
            GridRow(
                cells = cells,
                rightEdges = if (knownEdges) defs.map { it.right } else emptyList(),
                vertical = defs.map { it.vertical },
                fills = defs.map { def -> if (def.fill > 0) colors.getOrNull(def.fill - 1) ?: 0L else 0L },
                header = headerRow && pendingRows.size == 0
            )
        )
        rowCells.clear()
    }

    private fun flushTable() {
        if (pendingRows.isEmpty()) return
        builder.gridTable(pendingRows.toList())
        pendingRows.clear()
    }

    private fun headingFromStyle(name: String?): Int {
        val label = name?.trim()?.lowercase() ?: return 0
        if (label == "title" || label == "titre") return 1
        return Regex("^(heading|titre)\\s*(\\d)$").find(label)?.groupValues?.get(2)?.toInt() ?: 0
    }

    // ------------------------------------------------------------ tables d'en-tête

    /** Polices, couleurs et noms de styles, lus avant le corps. */
    private fun readTables() {
        groupBody("\\fonttbl")?.let { body ->
            childGroups(body).forEach { font ->
                val number = Regex("^\\\\f(\\d+)").find(font)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                plainName(font)?.let { fonts[number] = it }
            }
            // Certaines tables listent leurs polices sans sous-groupes.
            if (fonts.isEmpty()) {
                Regex("\\\\f(\\d+)[^;{}]*?\\s([^\\\\;{}]+);").findAll(body).forEach {
                    fonts[it.groupValues[1].toInt()] = it.groupValues[2].trim()
                }
            }
        }
        groupBody("\\colortbl")?.let { body ->
            body.split(';').dropLast(1).drop(1).forEach { entry ->
                val red = Regex("\\\\red(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val green = Regex("\\\\green(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val blue = Regex("\\\\blue(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                colors.add(0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong())
            }
        }
        groupBody("\\stylesheet")?.let { body ->
            childGroups(body).forEach { style ->
                // Word omet `\s0` pour le style « Normal » ; les styles de
                // caractère et de tableau (`\*\cs`, `\*\ts`) ne nous servent pas.
                val explicit = Regex("^\\\\s(\\d+)").find(style)?.groupValues?.get(1)?.toIntOrNull()
                val number = when {
                    explicit != null -> explicit
                    style.startsWith("\\*") -> return@forEach
                    else -> 0
                }
                plainName(style)?.let { styleNames[number] = it }
            }
        }
    }

    /** Contenu du groupe qui commence par [keyword], sans ses accolades. */
    private fun groupBody(keyword: String): String? {
        val at = source.indexOf("{$keyword")
        if (at < 0) return null
        var depth = 0
        var i = at
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at + 1 + keyword.length, i)
                }
            }
            i++
        }
        return null
    }

    private fun childGroups(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '\\' -> i++
                '{' -> {
                    if (depth == 0) start = i + 1
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) out.add(body.substring(start, i).trim())
                }
            }
            i++
        }
        return out
    }

    /** Le nom lisible d'une entrée de table : son texte hors commandes, avant « ; ». */
    private fun plainName(entry: String): String? {
        val withoutGroups = entry.replace(Regex("\\{[^{}]*\\}"), "")
        val text = withoutGroups
            .replace(Regex("\\\\'([0-9a-fA-F]{2})")) { String(byteArrayOf(it.groupValues[1].toInt(16).toByte()), charset) }
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .substringBefore(';')
            .trim()
        return text.ifEmpty { null }
    }
}
