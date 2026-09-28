package com.docssuite.pdftools

import java.io.ByteArrayOutputStream

/**
 * Les objets PDF, gardés au plus près de leur forme d'origine : nombres,
 * chaînes et noms conservent leur texte exact, pour être réécrits octet
 * pour octet. Rien n'est réinterprété de ce qu'on ne modifie pas.
 */
sealed interface PdfObject

object PdfNull : PdfObject {
    override fun toString() = "null"
}

data class PdfBool(val value: Boolean) : PdfObject

/** Le texte d'origine du nombre (`12`, `-3.5`, `.25`…). */
data class PdfNumber(val raw: String) : PdfObject {
    val doubleValue: Double get() = raw.toDoubleOrNull() ?: 0.0
    val intValue: Int get() = doubleValue.toInt()

    companion object {
        fun of(value: Int) = PdfNumber(value.toString())
    }
}

/** Chaîne littérale `(…)` ou hexadécimale `<…>`, délimiteurs compris. */
class PdfString(val raw: ByteArray) : PdfObject {

    /** Les octets que la chaîne représente, échappements et hexadécimal décodés. */
    fun bytes(): ByteArray =
        if (raw.isNotEmpty() && raw[0] == '<'.code.toByte()) hexBytes() else literalBytes()

    private fun hexBytes(): ByteArray {
        val digits = StringBuilder()
        for (i in 1 until raw.size) {
            val c = raw[i].toInt().toChar()
            if (c == '>') break
            if (c.isLetterOrDigit()) digits.append(c)
        }
        // Un nombre impair de chiffres se complète d'un 0 (§ 7.3.4.3).
        if (digits.length % 2 == 1) digits.append('0')
        return ByteArray(digits.length / 2) { i ->
            digits.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun literalBytes(): ByteArray {
        val out = ByteArrayOutputStream(raw.size)
        var i = 1
        val end = raw.size - 1 // la parenthèse fermante
        while (i < end) {
            val c = raw[i].toInt() and 0xFF
            if (c != '\\'.code) {
                // Une fin de ligne dans la chaîne vaut toujours \n.
                if (c == '\r'.code) {
                    out.write('\n'.code)
                    if (i + 1 < end && raw[i + 1] == '\n'.code.toByte()) i++
                } else {
                    out.write(c)
                }
                i++
                continue
            }
            i++
            if (i >= end) break
            val e = raw[i].toInt() and 0xFF
            when (e.toChar()) {
                'n' -> out.write('\n'.code)
                'r' -> out.write('\r'.code)
                't' -> out.write('\t'.code)
                'b' -> out.write(8)
                'f' -> out.write(12)
                '\r' -> if (i + 1 < end && raw[i + 1] == '\n'.code.toByte()) i++ // coupure de ligne
                '\n' -> {}
                in '0'..'7' -> {
                    var value = e - '0'.code
                    var digits = 1
                    while (digits < 3 && i + 1 < end && (raw[i + 1].toInt() and 0xFF) in '0'.code..'7'.code) {
                        i++
                        value = value * 8 + ((raw[i].toInt() and 0xFF) - '0'.code)
                        digits++
                    }
                    out.write(value and 0xFF)
                }
                else -> out.write(e)
            }
            i++
        }
        return out.toByteArray()
    }

    companion object {
        /** Une chaîne hexadécimale : sûre quels que soient les octets. */
        fun hex(bytes: ByteArray): PdfString {
            val text = StringBuilder(bytes.size * 2 + 2).append('<')
            bytes.forEach { text.append(String.format("%02X", it.toInt() and 0xFF)) }
            return PdfString(text.append('>').toString().toByteArray(Charsets.ISO_8859_1))
        }
    }
}

/** Nom sans la barre oblique, avec ses éventuels échappements `#xx`. */
data class PdfName(val raw: String) : PdfObject

class PdfArray(val items: MutableList<PdfObject>) : PdfObject

class PdfDict(val entries: LinkedHashMap<String, PdfObject> = LinkedHashMap()) : PdfObject {
    operator fun get(key: String): PdfObject? = entries[key]
    operator fun set(key: String, value: PdfObject) {
        entries[key] = value
    }
    fun remove(key: String) = entries.remove(key)
    fun nameOf(key: String): String? = (entries[key] as? PdfName)?.raw
    fun copy(): PdfDict = PdfDict(LinkedHashMap(entries))
}

data class PdfRef(val num: Int, val gen: Int) : PdfObject

/** Un flux : son dictionnaire et ses octets bruts, encore compressés. */
class PdfStream(val dict: PdfDict, val data: ByteArray) : PdfObject

class PdfFormatException(message: String) : Exception(message)

class PdfEncryptedException :
    Exception("Ce PDF est protégé par un mot de passe : il ne peut pas être modifié sans être déverrouillé")

class PdfWrongPasswordException : Exception("Mot de passe incorrect")

/** Sérialisation, séparateurs toujours explicites pour ne jamais coller deux jetons. */
internal fun ByteArrayOutputStream.writePdf(obj: PdfObject) {
    when (obj) {
        PdfNull -> ascii("null")
        is PdfBool -> ascii(if (obj.value) "true" else "false")
        is PdfNumber -> ascii(obj.raw)
        is PdfString -> write(obj.raw)
        is PdfName -> ascii("/" + obj.raw)
        is PdfRef -> ascii("${obj.num} ${obj.gen} R")
        is PdfArray -> {
            ascii("[")
            obj.items.forEachIndexed { i, item ->
                if (i > 0) ascii(" ")
                writePdf(item)
            }
            ascii("]")
        }
        is PdfDict -> {
            ascii("<<")
            obj.entries.forEach { (key, value) ->
                ascii("/$key ")
                writePdf(value)
                ascii(" ")
            }
            ascii(">>")
        }
        is PdfStream -> {
            val dict = obj.dict.copy().apply { set("Length", PdfNumber.of(obj.data.size)) }
            writePdf(dict)
            ascii("\nstream\n")
            write(obj.data)
            ascii("\nendstream")
        }
    }
}

internal fun ByteArrayOutputStream.ascii(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))
