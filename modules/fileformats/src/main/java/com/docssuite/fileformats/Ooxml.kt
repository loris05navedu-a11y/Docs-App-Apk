package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Briques communes aux formats « ZIP de XML » : OOXML (docx/xlsx/pptx) et
 * OpenDocument (odt/ods/odp). Aucune dépendance externe : `java.util.zip` et
 * le parseur XML de la plateforme suffisent.
 */

const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

/** Échappe le texte pour un nœud ou un attribut XML. */
fun xmlEscape(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (ch in text) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            // Word et Excel refusent le fichier entier si un caractère de
            // contrôle non autorisé traîne dans le XML.
            else -> if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) sb.append(ch)
        }
    }
    return sb.toString()
}

/** Construit une archive en mémoire à partir de parties nommées. */
class ZipBuilder {
    private val parts = LinkedHashMap<String, ByteArray>()

    fun add(path: String, content: String): ZipBuilder = add(path, content.toByteArray(Charsets.UTF_8))

    fun add(path: String, content: ByteArray): ZipBuilder {
        parts[path] = content
        return this
    }

    /** Écrit `mimetype` en premier et non compressé, comme l'exige OpenDocument. */
    fun addStoredFirst(path: String, content: String): ZipBuilder {
        val rebuilt = LinkedHashMap<String, ByteArray>()
        rebuilt[path] = content.toByteArray(Charsets.UTF_8)
        rebuilt.putAll(parts.filterKeys { it != path })
        parts.clear()
        parts.putAll(rebuilt)
        stored.add(path)
        return this
    }

    private val stored = HashSet<String>()

    fun build(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            parts.forEach { (path, content) ->
                if (path in stored) {
                    val entry = ZipEntry(path)
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.compressedSize = content.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(content) }.value
                    zip.putNextEntry(entry)
                } else {
                    zip.putNextEntry(ZipEntry(path))
                }
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}

/** Lit toutes les entrées d'une archive en mémoire. */
fun unzip(bytes: ByteArray, limit: Long = 64L * 1024 * 1024): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = zip.read(chunk)
                if (read <= 0) break
                total += read
                // Garde-fou contre les archives « zip bomb » : on abandonne
                // plutôt que de saturer la mémoire du téléphone.
                if (total > limit) throw FormatException("Archive trop volumineuse")
                buffer.write(chunk, 0, read)
            }
            out[entry.name] = buffer.toByteArray()
        }
    }
    return out
}

class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun newPullParser(xml: String): XmlPullParser {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    return factory.newPullParser().apply { setInput(StringReader(xml)) }
}

fun newPullParser(bytes: ByteArray): XmlPullParser = newPullParser(bytes.toString(Charsets.UTF_8))

fun InputStream.readAllBytesCompat(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val read = read(chunk)
        if (read <= 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

/**
 * Récupère un attribut sans se soucier du préfixe de namespace : le parseur
 * tourne en mode « namespace non conscient », donc `w:val` arrive tel quel dans
 * un fichier et `val` dans un autre selon le producteur.
 */
fun XmlPullParser.attr(local: String): String? {
    for (i in 0 until attributeCount) {
        val name = getAttributeName(i)
        if (name == local || name.substringAfter(':') == local) return getAttributeValue(i)
    }
    return null
}

/**
 * Identifiant de relation (`r:id`). On ne peut pas passer par [attr] : sur
 * `<p:sldId id="256" r:id="rId2"/>` les deux attributs ont `id` pour nom local.
 */
fun XmlPullParser.relationshipId(): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).contains(':') &&
            getAttributeName(i).substringAfter(':') == "id"
        ) return getAttributeValue(i)
    }
    return null
}

/** Nom de balise sans son préfixe (`w:p` → `p`). */
val XmlPullParser.localName: String get() = name?.substringAfter(':') ?: ""

/** Le `<Relationship>` d'un `.rels` : cible d'une partie par identifiant. */
fun parseRelationships(xml: ByteArray?): Map<String, String> {
    if (xml == null) return emptyMap()
    val out = HashMap<String, String>()
    runCatching {
        val parser = newPullParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.localName == "Relationship") {
                val id = parser.attr("Id")
                val target = parser.attr("Target")
                if (id != null && target != null) out[id] = target
            }
            event = parser.next()
        }
    }
    return out
}

/** Concatène le contenu textuel des balises dont le nom local est [tag]. */
fun collectText(xml: ByteArray, tag: String, separator: String = ""): String {
    val sb = StringBuilder()
    runCatching {
        val parser = newPullParser(xml)
        var event = parser.eventType
        var depth = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.localName == tag) depth++
                XmlPullParser.END_TAG -> if (parser.localName == tag) {
                    depth--
                    if (depth == 0) sb.append(separator)
                }
                XmlPullParser.TEXT -> if (depth > 0) sb.append(parser.text)
            }
            event = parser.next()
        }
    }
    return sb.toString()
}
