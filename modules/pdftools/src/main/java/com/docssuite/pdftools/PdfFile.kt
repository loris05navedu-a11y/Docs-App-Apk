package com.docssuite.pdftools

/** Une page telle que trouvée dans l'arbre, avec les attributs hérités de ses parents. */
class PdfPage(
    /** La référence de l'objet page ; `null` seulement dans un fichier non conforme. */
    val ref: PdfRef?,
    val dict: PdfDict,
    /** `Resources`, `MediaBox`, `CropBox`, `Rotate` venus d'un nœud `/Pages` parent. */
    val inherited: Map<String, PdfObject>
) {
    fun attribute(key: String): PdfObject? = dict[key] ?: inherited[key]
}

/**
 * Un PDF ouvert pour être réorganisé : table des objets et liste des pages.
 *
 * Gère les tables de références classiques et compressées (PDF 1.5), les
 * flux d'objets, les mises à jour incrémentales (`/Prev`), et reconstruit
 * la table en parcourant le fichier quand elle est fausse — cas fréquent
 * des PDF réparés ou modifiés à la main.
 */
class PdfFile private constructor(val bytes: ByteArray) {

    private sealed interface Entry
    private data class InFile(val offset: Int) : Entry
    private data class InObjectStream(val stream: Int, val index: Int) : Entry

    private val entries = HashMap<Int, Entry>()
    private val cache = HashMap<Int, PdfObject>()
    private val objectStreams = HashMap<Int, Pair<ByteArray, List<Pair<Int, Int>>>>()
    private val loading = HashSet<Int>()

    lateinit var trailer: PdfDict
        private set

    /** Numéro de version de l'en-tête (`1.4`, `1.7`…). */
    val version: String = Regex("%PDF-(\\d\\.\\d)").find(String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1))
        ?.groupValues?.get(1) ?: "1.4"

    /** Vrai si la table des références a dû être reconstruite. */
    var repaired = false
        private set

    lateinit var pages: List<PdfPage>
        private set

    val highestObjectNumber: Int get() = entries.keys.maxOrNull() ?: 0

    fun resolve(obj: PdfObject?): PdfObject? {
        var current = obj
        var hops = 0
        while (current is PdfRef && hops++ < 32) current = getObject(current.num)
        return current
    }

    fun getObject(num: Int): PdfObject {
        cache[num]?.let { return it }
        if (!loading.add(num)) return PdfNull // référence circulaire
        try {
            val obj = runCatching { load(num) }.getOrNull() ?: PdfNull
            cache[num] = obj
            return obj
        } finally {
            loading.remove(num)
        }
    }

    private fun load(num: Int): PdfObject? = when (val entry = entries[num]) {
        null -> null
        is InFile -> {
            val parser = PdfParser(bytes, entry.offset) { ref -> (resolve(ref) as? PdfNumber)?.intValue }
            val (found, obj) = parser.readIndirectObject()
            if (found != num) throw PdfFormatException("Objet $found trouvé au lieu de $num")
            obj
        }
        is InObjectStream -> {
            val (data, offsets) = objectStream(entry.stream)
            val offset = offsets.firstOrNull { it.first == num }?.second
                ?: offsets.getOrNull(entry.index)?.second
                ?: throw PdfFormatException("Objet $num absent de son flux")
            PdfParser(data, offset).readObject()
        }
    }

    /** Contenu décompressé d'un flux d'objets et la position de chacun. */
    private fun objectStream(num: Int): Pair<ByteArray, List<Pair<Int, Int>>> =
        objectStreams.getOrPut(num) {
            val stream = getObject(num) as? PdfStream ?: throw PdfFormatException("Flux d'objets $num introuvable")
            val data = PdfFilters.decode(stream, ::resolve)
            val count = (resolve(stream.dict["N"]) as? PdfNumber)?.intValue ?: 0
            val first = (resolve(stream.dict["First"]) as? PdfNumber)?.intValue ?: 0
            val header = PdfParser(data)
            val offsets = (0 until count).map { header.readInt() to first + header.readInt() }
            data to offsets
        }

    // ------------------------------------------------------ table des références

    private fun loadXref() {
        val startxref = lastIndexOf(bytes, "startxref".toByteArray())
        if (startxref < 0) throw PdfFormatException("Pas de « startxref »")
        var offset: Int? = PdfParser(bytes, startxref + "startxref".length).readInt()
        val visited = HashSet<Int>()
        var newest: PdfDict? = null

        while (offset != null && visited.add(offset)) {
            if (offset !in bytes.indices) throw PdfFormatException("Table des références hors du fichier")
            val parser = PdfParser(bytes, offset)
            parser.skipWhitespace()
            val section: PdfDict = if (parser.startsWithKeyword("xref")) {
                parser.pos += 4
                val trailerDict = readXrefTable(parser)
                // Fichier « hybride » : une table compressée complète l'ancienne.
                (trailerDict["XRefStm"] as? PdfNumber)?.intValue?.let { readXrefStream(it) }
                trailerDict
            } else {
                readXrefStream(offset)
            }
            if (newest == null) newest = section
            else if (newest["Root"] == null && section["Root"] != null) newest["Root"] = section["Root"]!!
            offset = (section["Prev"] as? PdfNumber)?.intValue
        }
        trailer = newest ?: throw PdfFormatException("Pas de dictionnaire de fin")
    }

    /** Table classique : sections « premier nombre » puis lignes `décalage génération n|f`. */
    private fun readXrefTable(parser: PdfParser): PdfDict {
        while (true) {
            parser.skipWhitespace()
            if (parser.startsWithKeyword("trailer")) {
                parser.pos += "trailer".length
                return parser.readObject() as? PdfDict ?: throw PdfFormatException("Dictionnaire de fin illisible")
            }
            val first = parser.readInt()
            val count = parser.readInt()
            for (i in 0 until count) {
                val offset = parser.readInt()
                parser.readInt()
                parser.skipWhitespace()
                val inUse = parser.startsWithKeyword("n")
                parser.pos++
                val num = first + i
                // Les sections les plus récentes sont lues d'abord : elles gagnent.
                if (num !in entries && inUse && offset > 0) entries[num] = InFile(offset)
                else if (num !in entries && !inUse) entries[num] = InFile(-1)
            }
        }
    }

    private fun readXrefStream(offset: Int): PdfDict {
        val (_, obj) = PdfParser(bytes, offset) { ref -> (resolve(ref) as? PdfNumber)?.intValue }.readIndirectObject()
        val stream = obj as? PdfStream ?: throw PdfFormatException("Table des références compressée illisible")
        val data = PdfFilters.decode(stream, { it })
        val widths = (stream.dict["W"] as? PdfArray)?.items?.map { (it as PdfNumber).intValue }
            ?: throw PdfFormatException("/W manquant")
        val size = (stream.dict["Size"] as? PdfNumber)?.intValue ?: 0
        val index = (stream.dict["Index"] as? PdfArray)?.items?.map { (it as PdfNumber).intValue } ?: listOf(0, size)
        val rowLength = widths.sum()
        var pos = 0
        fun field(width: Int, default: Int): Int {
            if (width == 0) return default
            var value = 0
            repeat(width) { value = (value shl 8) or (data[pos++].toInt() and 0xFF) }
            return value
        }
        for (s in index.indices step 2) {
            val first = index[s]
            val count = index.getOrElse(s + 1) { 0 }
            for (i in 0 until count) {
                if (pos + rowLength > data.size) break
                val type = field(widths[0], 1)
                val a = field(widths[1], 0)
                val b = field(widths.getOrElse(2) { 0 }, 0)
                val num = first + i
                if (num in entries) continue
                entries[num] = when (type) {
                    1 -> InFile(a)
                    2 -> InObjectStream(a, b)
                    else -> InFile(-1)
                }
            }
        }
        return stream.dict
    }

    /**
     * Reconstruction : on repère chaque `n g obj` du fichier (le dernier
     * l'emporte, comme dans une mise à jour incrémentale), puis on ajoute
     * le contenu des flux d'objets.
     */
    private fun rebuildXref() {
        repaired = true
        entries.clear()
        cache.clear()
        objectStreams.clear()
        val text = String(bytes, Charsets.ISO_8859_1)
        Regex("(?<![0-9])(\\d{1,9})[ \\t\\r\\n]+(\\d{1,5})[ \\t\\r\\n]+obj(?=[\\s<\\[(/%]|$)").findAll(text).forEach { m ->
            entries[m.groupValues[1].toInt()] = InFile(m.range.first)
        }
        entries.keys.toList().forEach { num ->
            val stream = runCatching { getObject(num) }.getOrNull() as? PdfStream ?: return@forEach
            if (stream.dict.nameOf("Type") != "ObjStm") return@forEach
            runCatching {
                objectStream(num).second.forEachIndexed { index, (member, _) ->
                    if (member !in entries) entries[member] = InObjectStream(num, index)
                }
            }
        }
        cache.clear()

        var found: PdfDict? = null
        var at = text.lastIndexOf("trailer")
        while (at >= 0 && found == null) {
            found = runCatching { PdfParser(bytes, at + 7).readObject() as? PdfDict }.getOrNull()?.takeIf { it["Root"] != null }
            at = text.lastIndexOf("trailer", at - 1)
        }
        if (found == null) {
            // Pas de dictionnaire de fin : une table compressée, ou rien du tout.
            val catalog = entries.keys.firstOrNull { num ->
                (runCatching { getObject(num) }.getOrNull() as? PdfDict)?.nameOf("Type") == "Catalog"
            } ?: throw PdfFormatException("Aucun catalogue trouvé : ce fichier n'est pas un PDF lisible")
            val encrypt = entries.keys.firstNotNullOfOrNull { num ->
                (runCatching { getObject(num) }.getOrNull() as? PdfStream)?.dict?.takeIf { it.nameOf("Type") == "XRef" }?.get("Encrypt")
            }
            found = PdfDict().apply {
                set("Root", PdfRef(catalog, 0))
                if (encrypt != null) set("Encrypt", encrypt)
            }
        }
        trailer = found
    }

    // --------------------------------------------------------------- pages

    private fun collectPages(): List<PdfPage> {
        val catalog = resolve(trailer["Root"]) as? PdfDict ?: throw PdfFormatException("Catalogue introuvable")
        val result = ArrayList<PdfPage>()
        val visited = HashSet<Int>()

        fun walk(node: PdfObject?, inherited: Map<String, PdfObject>) {
            if (node is PdfRef && !visited.add(node.num)) return
            val dict = resolve(node) as? PdfDict ?: return
            val kids = resolve(dict["Kids"]) as? PdfArray
            val isPage = dict.nameOf("Type") == "Page" || (kids == null && dict.nameOf("Type") != "Pages")
            if (isPage) {
                result.add(PdfPage(node as? PdfRef, dict, inherited))
                return
            }
            val next = HashMap(inherited)
            INHERITABLE.forEach { key -> dict[key]?.let { next[key] = it } }
            kids?.items?.forEach { walk(it, next) }
        }

        walk(catalog["Pages"], emptyMap())
        return result
    }

    private fun open() {
        val ok = runCatching {
            loadXref()
            val catalog = resolve(trailer["Root"]) as? PdfDict
            catalog != null && resolve(catalog["Pages"]) is PdfDict
        }.getOrDefault(false)
        if (!ok) rebuildXref()
        if (trailer["Encrypt"] != null && trailer["Encrypt"] != PdfNull) throw PdfEncryptedException()
        pages = collectPages()
        if (pages.isEmpty() && !repaired) {
            rebuildXref()
            pages = collectPages()
        }
        if (pages.isEmpty()) throw PdfFormatException("Aucune page trouvée dans ce PDF")
    }

    companion object {
        val INHERITABLE = listOf("Resources", "MediaBox", "CropBox", "Rotate")

        fun parse(bytes: ByteArray): PdfFile {
            if (lastIndexOf(bytes, "%PDF-".toByteArray(), minOf(bytes.size, 1024)) < 0) {
                throw PdfFormatException("Ce fichier n'est pas un PDF")
            }
            return PdfFile(bytes).apply { open() }
        }
    }
}
