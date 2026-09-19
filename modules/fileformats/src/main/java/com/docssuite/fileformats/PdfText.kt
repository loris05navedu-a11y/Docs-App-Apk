package com.docssuite.fileformats

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Extraction du texte d'un PDF.
 *
 * Un PDF décrit des glyphes positionnés, pas des phrases : il n'existe pas de
 * récupération parfaite du texte d'origine. On décompresse les flux de contenu
 * et on relit les opérateurs de texte, ce qui rend fidèlement les documents
 * produits par un traitement de texte. Un PDF scanné, lui, ne contient aucun
 * texte à retrouver — d'où le mode lecteur, qui affiche les pages telles quelles.
 */
object PdfText {

    fun looksLikePdf(bytes: ByteArray): Boolean =
        bytes.size > 5 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    fun read(bytes: ByteArray, title: String = "Document"): TextDocument {
        val text = extract(bytes)
        val paragraphs = text.split('\n')
            .map { it.trimEnd() }
            .dropLastWhile { it.isEmpty() }
            .map { TextParagraph(listOf(TextRun(it))) }
        return TextDocument(title, paragraphs.ifEmpty { listOf(TextParagraph()) })
    }

    fun extract(bytes: ByteArray): String {
        val out = StringBuilder()
        streams(bytes).forEach { stream ->
            val decoded = inflate(stream) ?: stream
            // Les flux d'images ou de polices ne contiennent pas d'opérateurs de
            // texte ; on les reconnaît à l'absence de bloc BT…ET.
            if (containsTextBlock(decoded)) {
                val page = parseContent(decoded.toString(Charsets.ISO_8859_1))
                if (page.isNotBlank()) {
                    if (out.isNotEmpty()) out.append("\n\n")
                    out.append(page.trimEnd())
                }
            }
        }
        return out.toString()
    }

    private fun containsTextBlock(data: ByteArray): Boolean {
        var index = 0
        while (index + 1 < data.size) {
            if (data[index] == 'B'.code.toByte() && data[index + 1] == 'T'.code.toByte()) return true
            index++
        }
        return false
    }

    /** Repère chaque `stream … endstream` et renvoie les octets bruts. */
    private fun streams(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val begin = "stream".toByteArray(Charsets.ISO_8859_1)
        val end = "endstream".toByteArray(Charsets.ISO_8859_1)
        var index = 0
        while (index < bytes.size) {
            val start = indexOf(bytes, begin, index)
            if (start < 0) break
            var from = start + begin.size
            if (from < bytes.size && bytes[from] == '\r'.code.toByte()) from++
            if (from < bytes.size && bytes[from] == '\n'.code.toByte()) from++
            val stop = indexOf(bytes, end, from)
            if (stop < 0) break
            if (stop > from) out.add(bytes.copyOfRange(from, stop))
            index = stop + end.size
        }
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun inflate(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        return try {
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, read)
                if (out.size() > 32 * 1024 * 1024) break
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    /**
     * Interprète les opérateurs de texte d'un flux de contenu : `Tj` et `'`
     * pour une chaîne, `TJ` pour un tableau, `Td`/`TD`/`T*`/`TL` pour les sauts
     * de ligne.
     */
    private fun parseContent(content: String): String {
        val out = StringBuilder()
        val line = StringBuilder()
        val operands = ArrayList<String>()
        var index = 0

        fun endLine() {
            if (line.isNotEmpty()) {
                out.append(line).append('\n')
                line.setLength(0)
            } else if (out.isNotEmpty() && !out.endsWith("\n\n")) {
                out.append('\n')
            }
        }

        while (index < content.length) {
            when (val ch = content[index]) {
                '(' -> {
                    val (text, next) = readLiteral(content, index + 1)
                    operands.add(text)
                    index = next
                }
                '<' -> {
                    if (content.getOrNull(index + 1) == '<') {
                        index += 2
                    } else {
                        val close = content.indexOf('>', index + 1)
                        if (close < 0) {
                            index = content.length
                        } else {
                            operands.add(readHex(content.substring(index + 1, close)))
                            index = close + 1
                        }
                    }
                }
                '[', ']' -> index++
                else -> {
                    if (ch.isWhitespace()) {
                        index++
                        continue
                    }
                    val start = index
                    while (index < content.length &&
                        !content[index].isWhitespace() &&
                        content[index] !in "()<>[]/"
                    ) index++
                    if (index == start) {
                        index++
                        continue
                    }
                    when (val token = content.substring(start, index)) {
                        "Tj", "TJ" -> {
                            operands.forEach { line.append(it) }
                            operands.clear()
                        }
                        "'", "\"" -> {
                            endLine()
                            operands.forEach { line.append(it) }
                            operands.clear()
                        }
                        "Td", "TD", "T*", "ET" -> {
                            endLine()
                            operands.clear()
                        }
                        "BT" -> operands.clear()
                        else -> {
                            // Un opérateur inconnu invalide les opérandes en attente.
                            if (token.isNotEmpty() && token[0].isLetter()) operands.clear()
                        }
                    }
                }
            }
        }
        endLine()
        return out.toString()
    }

    private fun readLiteral(content: String, from: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var index = from
        var depth = 1
        while (index < content.length) {
            when (val ch = content[index]) {
                '\\' -> {
                    when (val escaped = content.getOrNull(index + 1)) {
                        'n' -> { sb.append('\n'); index += 2 }
                        'r' -> { sb.append('\r'); index += 2 }
                        't' -> { sb.append('\t'); index += 2 }
                        'b', 'f' -> index += 2
                        '\n' -> index += 2
                        in '0'..'7' -> {
                            var digits = 0
                            var value = 0
                            index++
                            while (digits < 3 && content.getOrNull(index)?.let { it in '0'..'7' } == true) {
                                value = value * 8 + (content[index] - '0')
                                index++; digits++
                            }
                            sb.append(latin1(value))
                        }
                        null -> index++
                        else -> { sb.append(escaped); index += 2 }
                    }
                }
                '(' -> { depth++; sb.append(ch); index++ }
                ')' -> {
                    depth--
                    if (depth == 0) return sb.toString() to index + 1
                    sb.append(ch); index++
                }
                else -> { sb.append(ch); index++ }
            }
        }
        return sb.toString() to index
    }

    private fun readHex(hex: String): String {
        val digits = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (digits.isEmpty()) return ""
        // Une chaîne hexadécimale d'octets pairs vient le plus souvent d'une
        // police Identity-H, qui encode en UTF-16BE.
        if (digits.length % 4 == 0) {
            val sb = StringBuilder()
            var index = 0
            var plausible = true
            while (index + 4 <= digits.length) {
                val code = digits.substring(index, index + 4).toInt(16)
                if (code in 0x20..0x2FFF || code == 0x0A || code == 0x09) sb.append(code.toChar())
                else plausible = false
                index += 4
            }
            if (plausible) return sb.toString()
        }
        val sb = StringBuilder()
        var index = 0
        while (index + 2 <= digits.length) {
            sb.append(latin1(digits.substring(index, index + 2).toInt(16)))
            index += 2
        }
        return sb.toString()
    }

    private fun latin1(code: Int): Char =
        String(byteArrayOf(code.toByte()), CP1252).firstOrNull() ?: ' '
}
