package com.docssuite.pdftools

/**
 * Lecture des objets PDF depuis les octets du fichier (ISO 32000, § 7.2–7.3).
 * [lengthOf] résout une longueur de flux donnée par référence indirecte.
 */
internal class PdfParser(
    private val bytes: ByteArray,
    var pos: Int = 0,
    private val lengthOf: (PdfRef) -> Int? = { null }
) {

    private fun at(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1

    val atEnd: Boolean get() = pos >= bytes.size

    fun skipWhitespace() {
        while (pos < bytes.size) {
            val c = at(pos)
            when {
                isWhitespace(c) -> pos++
                c == '%'.code -> while (pos < bytes.size && at(pos) != '\n'.code && at(pos) != '\r'.code) pos++
                else -> return
            }
        }
    }

    /** Vrai si le mot-clé [word] commence ici et se termine sur un séparateur. */
    fun startsWithKeyword(word: String, from: Int = pos): Boolean {
        if (from + word.length > bytes.size) return false
        for (i in word.indices) if (at(from + i) != word[i].code) return false
        val next = at(from + word.length)
        return next == -1 || isWhitespace(next) || isDelimiter(next)
    }

    fun expectKeyword(word: String) {
        skipWhitespace()
        if (!startsWithKeyword(word)) throw PdfFormatException("« $word » attendu à l'octet $pos")
        pos += word.length
    }

    fun readInt(): Int {
        skipWhitespace()
        val start = pos
        if (at(pos) == '+'.code || at(pos) == '-'.code) pos++
        while (at(pos) in '0'.code..'9'.code) pos++
        if (pos == start) throw PdfFormatException("Entier attendu à l'octet $start")
        return String(bytes, start, pos - start, Charsets.ISO_8859_1).toIntOrNull()
            ?: throw PdfFormatException("Entier invalide à l'octet $start")
    }

    /** Un objet direct ; une référence `n g R` est reconnue en regardant deux jetons plus loin. */
    fun readObject(): PdfObject {
        skipWhitespace()
        val c = at(pos)
        return when {
            c == -1 -> throw PdfFormatException("Fin de fichier inattendue")
            c == '<'.code && at(pos + 1) == '<'.code -> readDict()
            c == '<'.code -> readHexString()
            c == '('.code -> readLiteralString()
            c == '['.code -> readArray()
            c == '/'.code -> readName()
            c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> readNumberOrRef()
            startsWithKeyword("true") -> { pos += 4; PdfBool(true) }
            startsWithKeyword("false") -> { pos += 5; PdfBool(false) }
            startsWithKeyword("null") -> { pos += 4; PdfNull }
            else -> throw PdfFormatException("Objet inattendu à l'octet $pos")
        }
    }

    private fun readDict(): PdfDict {
        pos += 2
        val dict = PdfDict()
        while (true) {
            skipWhitespace()
            if (at(pos) == '>'.code && at(pos + 1) == '>'.code) {
                pos += 2
                return dict
            }
            if (at(pos) != '/'.code) {
                // Valeur sans clé (fichier abîmé) : on la saute plutôt que d'abandonner.
                readObject()
                continue
            }
            val key = readName().raw
            skipWhitespace()
            if (at(pos) == '>'.code && at(pos + 1) == '>'.code) {
                dict[key] = PdfNull
                continue
            }
            dict[key] = readObject()
        }
    }

    private fun readArray(): PdfArray {
        pos++
        val items = ArrayList<PdfObject>()
        while (true) {
            skipWhitespace()
            if (at(pos) == ']'.code) {
                pos++
                return PdfArray(items)
            }
            items.add(readObject())
        }
    }

    private fun readName(): PdfName {
        val start = ++pos
        while (pos < bytes.size && !isWhitespace(at(pos)) && !isDelimiter(at(pos))) pos++
        return PdfName(String(bytes, start, pos - start, Charsets.ISO_8859_1))
    }

    private fun readHexString(): PdfString {
        val start = pos
        pos++
        while (pos < bytes.size && at(pos) != '>'.code) pos++
        if (pos >= bytes.size) throw PdfFormatException("Chaîne hexadécimale non terminée")
        pos++
        return PdfString(bytes.copyOfRange(start, pos))
    }

    /** Parenthèses imbriquées équilibrées ; `\` protège le caractère suivant. */
    private fun readLiteralString(): PdfString {
        val start = pos
        pos++
        var depth = 1
        while (pos < bytes.size && depth > 0) {
            when (at(pos)) {
                '\\'.code -> pos++
                '('.code -> depth++
                ')'.code -> depth--
            }
            pos++
        }
        if (depth > 0) throw PdfFormatException("Chaîne non terminée")
        return PdfString(bytes.copyOfRange(start, pos))
    }

    private fun readNumberOrRef(): PdfObject {
        val first = readNumberToken()
        if (first.contains('.') || first.startsWith('-') || first.startsWith('+')) return PdfNumber(first)
        val save = pos
        skipWhitespace()
        if (at(pos) in '0'.code..'9'.code) {
            val second = readNumberToken()
            skipWhitespace()
            if (!second.contains('.') && at(pos) == 'R'.code) {
                val next = at(pos + 1)
                if (next == -1 || isWhitespace(next) || isDelimiter(next)) {
                    pos++
                    return PdfRef(first.toInt(), second.toInt())
                }
            }
        }
        pos = save
        return PdfNumber(first)
    }

    private fun readNumberToken(): String {
        val start = pos
        if (at(pos) == '+'.code || at(pos) == '-'.code) pos++
        while (at(pos) in '0'.code..'9'.code || at(pos) == '.'.code) pos++
        if (pos == start) throw PdfFormatException("Nombre attendu à l'octet $start")
        return String(bytes, start, pos - start, Charsets.ISO_8859_1)
    }

    /** `n g obj … endobj` à la position courante ; renvoie le numéro et l'objet. */
    fun readIndirectObject(): Pair<Int, PdfObject> {
        val num = readInt()
        readInt()
        expectKeyword("obj")
        val obj = readObject()
        skipWhitespace()
        if (obj is PdfDict && startsWithKeyword("stream")) {
            pos += "stream".length
            return num to PdfStream(obj, readStreamData(obj))
        }
        return num to obj
    }

    /**
     * Les octets d'un flux. La longueur annoncée est vérifiée ; si elle ne
     * tombe pas juste devant `endstream` (longueur fausse, ou référence
     * introuvable), on cherche ce mot-clé.
     */
    private fun readStreamData(dict: PdfDict): ByteArray {
        if (at(pos) == '\r'.code && at(pos + 1) == '\n'.code) pos += 2
        else if (at(pos) == '\n'.code || at(pos) == '\r'.code) pos++
        val start = pos

        val declared = when (val length = dict["Length"]) {
            is PdfNumber -> length.intValue
            is PdfRef -> lengthOf(length)
            else -> null
        }
        if (declared != null && declared >= 0 && start + declared <= bytes.size) {
            var end = start + declared
            while (end < bytes.size && isWhitespace(at(end))) end++
            if (startsWithKeyword("endstream", end)) {
                pos = end + "endstream".length
                return bytes.copyOfRange(start, start + declared)
            }
        }

        val marker = indexOf(bytes, "endstream".toByteArray(Charsets.ISO_8859_1), start)
        if (marker < 0) throw PdfFormatException("Flux non terminé à l'octet $start")
        var end = marker
        if (end > start && at(end - 1) == '\n'.code) end--
        if (end > start && at(end - 1) == '\r'.code) end--
        pos = marker + "endstream".length
        return bytes.copyOfRange(start, end)
    }

    companion object {
        fun isWhitespace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDelimiter(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
    }
}

internal fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    if (needle.isEmpty()) return from
    var i = maxOf(0, from)
    val last = haystack.size - needle.size
    outer@ while (i <= last) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                i++
                continue@outer
            }
        }
        return i
    }
    return -1
}

internal fun lastIndexOf(haystack: ByteArray, needle: ByteArray, from: Int = haystack.size - needle.size): Int {
    var i = minOf(from, haystack.size - needle.size)
    outer@ while (i >= 0) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                i--
                continue@outer
            }
        }
        return i
    }
    return -1
}
