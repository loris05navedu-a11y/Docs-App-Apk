package com.docssuite.fileformats

import java.nio.charset.Charset

/**
 * Lecteur HTML tolérant : les pages reçues (courriels enregistrés, exports de
 * Word ou de LibreOffice, pages web) sont rarement bien formées. On en garde
 * les paragraphes, titres, listes, mises en forme simples et tableaux, et les
 * blancs de mise en page du code source ne deviennent plus des paragraphes
 * vides.
 */
internal class HtmlReader(private val html: String) {

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val size: Int? = null,
        val color: Long? = null,
        val highlight: Long = 0L,
        val baseline: Int = 0
    )

    private class ListFrame(val ordered: Boolean) {
        var counter = 0
    }

    private val builder = BlockBuilder()
    private val runs = ArrayList<TextRun>()
    private val styles = ArrayList<Style>().apply { add(Style()) }
    private val openInline = ArrayList<String>()
    private val lists = ArrayList<ListFrame>()
    private var align = 0
    private var heading = 0
    private var preformatted = 0
    private var pendingSpace = false
    private var pendingListPrefix: String? = null
    private var explicitEmpty = false
    private var headerRow = false

    fun read(title: String): TextDocument {
        var index = 0
        val text = StringBuilder()
        while (index < html.length) {
            val ch = html[index]
            if (ch == '<') {
                if (text.isNotEmpty()) {
                    appendText(decodeEntities(text.toString()))
                    text.setLength(0)
                }
                index = readTag(index)
            } else {
                text.append(ch)
                index++
            }
        }
        if (text.isNotEmpty()) appendText(decodeEntities(text.toString()))
        endParagraph()
        val blocks = builder.build()
        return TextDocument(title, blocks.ifEmpty { listOf(TextParagraph()) })
    }

    /** Lit la balise qui commence à [start] et renvoie la position qui la suit. */
    private fun readTag(start: Int): Int {
        if (html.startsWith("<!--", start)) {
            val end = html.indexOf("-->", start + 4)
            return if (end < 0) html.length else end + 3
        }
        if (html.startsWith("<!", start) || html.startsWith("<?", start)) {
            val end = html.indexOf('>', start)
            return if (end < 0) html.length else end + 1
        }
        val end = findTagEnd(start)
        val raw = html.substring(start + 1, end).trim()
        val next = end + 1
        if (raw.isEmpty()) return next
        val closing = raw.startsWith("/")
        val body = raw.removePrefix("/").removeSuffix("/").trim()
        // `<o:p>` et consorts (HTML de Word) gardent leur préfixe : ils ne
        // correspondent ainsi à aucune balise connue et sont ignorés.
        val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
        val attributes = body.drop(name.length)

        // Le contenu de ces éléments n'est pas du texte à afficher.
        if (!closing && name in setOf("script", "style", "head", "title", "template", "noscript", "svg", "xml")) {
            val close = html.indexOf("</", next).let { var at = it
                while (at >= 0 && !html.regionMatches(at + 2, name, 0, name.length, ignoreCase = true)) {
                    at = html.indexOf("</", at + 2)
                }
                at
            }
            // Sans balise fermante, mieux vaut lire la suite que tout perdre.
            if (close < 0) return next
            val after = html.indexOf('>', close)
            return if (after < 0) html.length else after + 1
        }
        if (closing) closeTag(name) else openTag(name, attributes, raw.endsWith("/"))
        return next
    }

    /** Fin de balise, en sautant les `>` des valeurs d'attributs entre guillemets. */
    private fun findTagEnd(start: Int): Int {
        var quote: Char? = null
        var i = start + 1
        while (i < html.length) {
            val c = html[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return html.length - 1
    }

    private fun openTag(name: String, attributes: String, selfClosing: Boolean) {
        when (name) {
            "p", "div", "section", "article", "header", "footer", "blockquote", "center", "address", "dd", "dt" -> {
                endParagraph()
                align = alignOf(attributes) ?: if (name == "center") 1 else 0
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                endParagraph()
                heading = name[1].digitToInt()
                align = alignOf(attributes) ?: 0
                pushStyle(name, current().copy(bold = true, size = headingSize(heading)))
            }
            "br" -> appendRaw("\n")
            "hr" -> endParagraph()
            "pre" -> {
                endParagraph(); preformatted++
            }
            "ul", "ol" -> {
                endParagraph()
                lists.add(ListFrame(ordered = name == "ol"))
            }
            "li" -> {
                endParagraph()
                val list = lists.lastOrNull()
                pendingListPrefix = if (list == null || !list.ordered) {
                    when ((lists.size - 1).coerceAtLeast(0) % 3) {
                        0 -> "• "
                        1 -> "◦ "
                        else -> "▪ "
                    }
                } else {
                    list.counter++
                    "${list.counter}. "
                }
            }
            "table" -> {
                endParagraph()
                builder.startTable()
            }
            "thead" -> headerRow = true
            "tr" -> {
                endParagraph()
                builder.startRow(header = headerRow)
            }
            "td", "th" -> {
                endParagraph()
                builder.startCell(
                    colSpan = attribute(attributes, "colspan")?.toIntOrNull() ?: 1,
                    rowSpan = attribute(attributes, "rowspan")?.toIntOrNull() ?: 1,
                    fill = backgroundOf(attributes) ?: 0L
                )
                align = alignOf(attributes) ?: 0
                if (name == "th") pushStyle(name, current().copy(bold = true))
            }
            "b", "strong" -> pushStyle(name, current().copy(bold = true))
            "i", "em", "cite", "var", "dfn" -> pushStyle(name, current().copy(italic = true))
            "u", "ins" -> pushStyle(name, current().copy(underline = true))
            "s", "strike", "del" -> pushStyle(name, current().copy(strike = true))
            "sup" -> pushStyle(name, current().copy(baseline = 1))
            "sub" -> pushStyle(name, current().copy(baseline = -1))
            "span", "font", "a", "mark", "small", "big", "code", "tt", "label" -> {
                if (!selfClosing) pushStyle(name, styled(current(), attributes, name))
            }
            "img" -> attribute(attributes, "alt")?.takeIf { it.isNotBlank() }?.let { appendText("[$it]") }
        }
    }

    private fun closeTag(name: String) {
        when (name) {
            "p", "div", "section", "article", "header", "footer", "blockquote", "center", "address", "dd", "dt", "li", "pre" -> {
                endParagraph()
                if (name == "pre") preformatted = (preformatted - 1).coerceAtLeast(0)
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                endParagraph()
                popStyle(name)
                heading = 0
            }
            "ul", "ol" -> {
                endParagraph()
                lists.removeLastOrNull()
            }
            "td", "th" -> {
                endParagraph()
                if (name == "th") popStyle(name)
                builder.endCell()
            }
            "tr" -> {
                endParagraph()
                builder.endRow()
            }
            "thead" -> headerRow = false
            "table" -> {
                endParagraph()
                builder.endTable()
            }
            else -> popStyle(name)
        }
    }

    private fun current() = styles.last()

    private fun pushStyle(tag: String, style: Style) {
        openInline.add(tag)
        styles.add(style)
    }

    /** Referme la dernière balise de ce nom, et celles restées ouvertes par-dessus. */
    private fun popStyle(tag: String) {
        val at = openInline.lastIndexOf(tag)
        if (at < 0) return
        while (openInline.size > at) {
            openInline.removeAt(openInline.size - 1)
            styles.removeAt(styles.size - 1)
        }
    }

    // ------------------------------------------------------------ texte

    private fun appendText(text: String) {
        if (preformatted > 0) {
            appendRaw(text)
            return
        }
        // Les blancs du code source se replient en une espace, comme au rendu.
        val sb = StringBuilder()
        text.forEach { c ->
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pendingSpace = true
            } else {
                if (pendingSpace && (runs.isNotEmpty() || sb.isNotEmpty())) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) appendRaw(sb.toString())
    }

    private fun appendRaw(text: String) {
        if (text == "\u00A0" || text == "\n") explicitEmpty = true
        pendingListPrefix?.let { prefix ->
            pendingListPrefix = null
            runs.add(toRun(prefix))
        }
        val last = runs.lastOrNull()
        val run = toRun(text)
        if (last != null && last.copy(text = "") == run.copy(text = "")) {
            runs[runs.size - 1] = last.copy(text = last.text + text)
        } else {
            runs.add(run)
        }
        if (text == "\n") pendingSpace = false
    }

    private fun toRun(text: String): TextRun {
        val style = current()
        return TextRun(
            text = text,
            bold = style.bold,
            italic = style.italic,
            underline = style.underline,
            strike = style.strike,
            size = style.size ?: 12,
            color = style.color ?: 0xFF1A1A1AL,
            highlight = style.highlight,
            baseline = style.baseline
        )
    }

    private fun endParagraph() {
        // Un paragraphe fait seulement de blancs n'était qu'un retour à la
        // ligne du code source ; `<p>&nbsp;</p>` en revanche est voulu.
        val content = runs.joinToString("") { it.text }
        val meaningful = content.isNotBlank() || (explicitEmpty && content.isNotEmpty())
        if (meaningful) {
            // Les espaces et sauts de ligne en fin de paragraphe ne se voient pas.
            val trimmed = ArrayList(runs)
            val last = trimmed.last()
            trimmed[trimmed.size - 1] = last.copy(text = last.text.trimEnd(' ', '\n', '\u00A0'))
            trimmed.removeAll { it.text.isEmpty() }
            builder.paragraph(
                TextParagraph(
                    runs = trimmed,
                    align = align,
                    heading = heading.coerceAtMost(3),
                    indent = (lists.size - 1).coerceIn(0, 8)
                )
            )
        }
        runs.clear()
        pendingSpace = false
        explicitEmpty = false
        pendingListPrefix = pendingListPrefix.takeIf { !meaningful && it != null }
    }

    // ------------------------------------------------------------ attributs et styles

    private fun styled(base: Style, attributes: String, tag: String): Style {
        var style = base
        if (tag == "code" || tag == "tt") return style
        if (tag == "small") style = style.copy(size = ((style.size ?: 12) - 2).coerceAtLeast(6))
        if (tag == "big") style = style.copy(size = (style.size ?: 12) + 2)
        if (tag == "mark") style = style.copy(highlight = 0xFFFFFF00L)
        if (tag == "a") style = style.copy(underline = true, color = style.color ?: 0xFF1D4ED8L)
        attribute(attributes, "color")?.let { cssColor(it) }?.let { style = style.copy(color = it) }
        val css = attribute(attributes, "style") ?: return style
        css.split(';').forEach { declaration ->
            val property = declaration.substringBefore(':').trim().lowercase()
            val value = declaration.substringAfter(':', "").trim().lowercase()
            when (property) {
                "font-weight" -> style = style.copy(bold = value == "bold" || value == "bolder" || (value.toIntOrNull() ?: 400) >= 600)
                "font-style" -> style = style.copy(italic = value == "italic" || value == "oblique")
                "text-decoration", "text-decoration-line" -> style = style.copy(
                    underline = value.contains("underline") || style.underline,
                    strike = value.contains("line-through") || style.strike
                )
                "color" -> cssColor(value)?.let { style = style.copy(color = it) }
                "background", "background-color" -> cssColor(value)?.let { style = style.copy(highlight = it) }
                "font-size" -> cssSize(value)?.let { style = style.copy(size = it) }
                "vertical-align" -> style = style.copy(
                    baseline = when (value) {
                        "super" -> 1
                        "sub" -> -1
                        else -> style.baseline
                    }
                )
            }
        }
        return style
    }

    private fun alignOf(attributes: String): Int? {
        val value = attribute(attributes, "align")?.lowercase()
            ?: attribute(attributes, "style")?.let { css ->
                Regex("text-align\\s*:\\s*([a-z]+)", RegexOption.IGNORE_CASE).find(css)?.groupValues?.get(1)?.lowercase()
            }
        return when (value) {
            "center" -> 1
            "right", "end" -> 2
            "justify" -> 3
            "left", "start" -> 0
            else -> null
        }
    }

    private fun backgroundOf(attributes: String): Long? =
        attribute(attributes, "bgcolor")?.let { cssColor(it) }
            ?: attribute(attributes, "style")?.let { css ->
                Regex("background(?:-color)?\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
                    .find(css)?.groupValues?.get(1)?.let { cssColor(it.trim()) }
            }

    private fun headingSize(level: Int) = when (level) {
        1 -> 20
        2 -> 16
        3 -> 14
        else -> 12
    }

    companion object {

        /** Décode les octets d'une page selon le jeu de caractères qu'elle annonce. */
        fun decode(bytes: ByteArray): String {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val declared = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.get(1)
            val hasBom = bytes.size >= 2 && (
                (bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte()) ||
                    bytes[0] == 0xFF.toByte() || bytes[0] == 0xFE.toByte()
                )
            if (declared != null && !hasBom) {
                runCatching { Charset.forName(declared) }.getOrNull()?.let { charset ->
                    // Une page qui se dit latine mais est en réalité en UTF-8
                    // reste lisible : on fait confiance au contenu d'abord.
                    val utf8 = decodeText(bytes)
                    if (charset == Charsets.UTF_8 || utf8.none { it == '\uFFFD' } && isUtf8(bytes)) return utf8
                    return String(bytes, charset)
                }
            }
            return decodeText(bytes)
        }

        private fun isUtf8(bytes: ByteArray): Boolean = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)

        fun attribute(attributes: String, name: String): String? {
            val match = Regex(
                "(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                RegexOption.IGNORE_CASE
            ).find(attributes) ?: return null
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }
            return decodeEntities(value)
        }

        private val NAMED_COLORS = mapOf(
            "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000,
            "blue" to 0x0000FF, "yellow" to 0xFFFF00, "orange" to 0xFFA500, "gray" to 0x808080,
            "grey" to 0x808080, "silver" to 0xC0C0C0, "maroon" to 0x800000, "navy" to 0x000080,
            "purple" to 0x800080, "teal" to 0x008080, "olive" to 0x808000, "lime" to 0x00FF00,
            "aqua" to 0x00FFFF, "fuchsia" to 0xFF00FF
        )

        fun cssColor(value: String): Long? {
            val clean = value.trim().lowercase()
            if (clean == "transparent" || clean == "inherit" || clean == "none") return null
            if (clean.startsWith("#")) {
                val hex = clean.removePrefix("#")
                val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
                return full.take(6).toLongOrNull(16)?.let { 0xFF000000L or it }
            }
            Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(clean)?.let { m ->
                val (r, g, b) = m.destructured
                return 0xFF000000L or (r.toLong().coerceIn(0, 255) shl 16) or
                    (g.toLong().coerceIn(0, 255) shl 8) or b.toLong().coerceIn(0, 255)
            }
            return NAMED_COLORS[clean]?.let { 0xFF000000L or it.toLong() }
        }

        private fun cssSize(value: String): Int? {
            val number = value.dropLastWhile { it.isLetter() || it == '%' }.toDoubleOrNull() ?: return when (value) {
                "small", "x-small" -> 10
                "large" -> 14
                "x-large" -> 18
                "xx-large" -> 24
                else -> null
            }
            return when {
                value.endsWith("pt") -> number
                value.endsWith("px") -> number * 0.75
                value.endsWith("em") || value.endsWith("rem") -> number * 12
                value.endsWith("%") -> number * 0.12
                else -> null
            }?.toInt()?.coerceIn(4, 200)
        }

        /** Les noms de caractères Latin-1, dans l'ordre de leurs codes 160 à 255. */
        private val LATIN1 = (
            "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
                "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
                "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
                "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
                "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
                "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
            ).split(' ').withIndex().associate { (i, name) -> name to (160 + i).toChar().toString() }

        private val ENTITIES = LATIN1 + mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "euro" to "€", "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘",
            "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "trade" to "™",
            "oelig" to "œ", "OElig" to "Œ", "rarr" to "→", "larr" to "←", "hArr" to "⇔",
            "rArr" to "⇒", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "", "zwj" to "",
            "lsaquo" to "‹", "rsaquo" to "›", "dagger" to "†", "permil" to "‰", "minus" to "−",
            "le" to "≤", "ge" to "≥", "ne" to "≠", "infin" to "∞", "check" to "✓"
        )

        fun decodeEntities(text: String): String {
            if (!text.contains('&')) return text
            return Regex("&(#[xX]?[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);?").replace(text) { match ->
                val name = match.groupValues[1]
                when {
                    name.startsWith("#x") || name.startsWith("#X") ->
                        name.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: match.value
                    name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { codePoint(it) } ?: match.value
                    else -> ENTITIES[name] ?: match.value
                }
            }
        }

        private fun codePoint(value: Int): String = when {
            // Les pages « Windows » écrivent les guillemets typographiques en cp1252.
            value in 0x80..0x9F -> String(byteArrayOf(value.toByte()), CP1252)
            value in 1..0x10FFFF -> String(Character.toChars(value))
            else -> ""
        }
    }
}
