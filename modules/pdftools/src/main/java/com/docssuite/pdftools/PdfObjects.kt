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
class PdfString(val raw: ByteArray) : PdfObject

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
