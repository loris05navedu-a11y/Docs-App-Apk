package com.docssuite.fileformats

import java.nio.charset.Charset

/**
 * Formats binaires Office 97-2003 (`.doc`, `.xls`, `.ppt`). Ces formats ne
 * portent pas leur mise en forme d'une manière transposable telle quelle : on
 * en extrait fidèlement le contenu (texte, valeurs, formules calculées), puis
 * l'app le réenregistre dans le format moderne correspondant.
 */

internal val CP1252: Charset = runCatching { Charset.forName("windows-1252") }
    .getOrElse { Charsets.ISO_8859_1 }

private fun cp1252Char(byte: Int): Char =
    String(byteArrayOf(byte.toByte()), CP1252).firstOrNull() ?: ' '

// ---------------------------------------------------------------- .doc

object DocLegacy {

    fun read(bytes: ByteArray, title: String = "Document"): TextDocument {
        val cfb = Cfb.open(bytes)
        val word = cfb.firstStream("WordDocument")
            ?: throw FormatException("Ce .doc ne contient pas de flux WordDocument")

        // Le bit 9 de l'octet 0x0A du FIB dit lequel des deux flux de tables
        // est celui en vigueur.
        val useTable1 = (word.u16le(0x0A) and 0x0200) != 0
        val table = cfb.firstStream(if (useTable1) "1Table" else "0Table")
            ?: cfb.firstStream("1Table", "0Table")

        val text = if (table != null) {
            extractWithPieceTable(word, table) ?: extractRaw(word)
        } else {
            extractRaw(word)
        }

        val paragraphs = text
            .replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n')
            .split('\n')
            .map { line -> TextParagraph(listOf(TextRun(cleanup(line)))) }
        return TextDocument(title, paragraphs.ifEmpty { listOf(TextParagraph()) })
    }

    /**
     * Le texte d'un .doc n'est pas contigu : une « table de morceaux » (piece
     * table) indique où lire chaque tronçon et dans quel encodage.
     */
    private fun extractWithPieceTable(word: ByteArray, table: ByteArray): String? {
        val fcClx = word.i32le(0x01A2)
        val lcbClx = word.i32le(0x01A6)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) return null

        // Le Clx est une suite de Prc (0x01) suivie d'un unique Pcdt (0x02).
        var offset = fcClx
        val end = fcClx + lcbClx
        while (offset < end) {
            when (table.u8(offset)) {
                0x01 -> {
                    val size = table.u16le(offset + 1)
                    offset += 3 + size
                }
                0x02 -> {
                    val size = table.i32le(offset + 1)
                    val start = offset + 5
                    if (size <= 0 || start + size > table.size) return null
                    return readPieces(word, table, start, size)
                }
                else -> return null
            }
        }
        return null
    }

    private fun readPieces(word: ByteArray, table: ByteArray, start: Int, size: Int): String? {
        // PlcPcd : (n+1) positions de caractères sur 4 octets, puis n descripteurs de 8.
        val count = (size - 4) / 12
        if (count <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until count) {
            val cpStart = table.i32le(start + i * 4)
            val cpEnd = table.i32le(start + (i + 1) * 4)
            val length = cpEnd - cpStart
            if (length <= 0) continue

            val descriptor = start + (count + 1) * 4 + i * 8
            val fc = table.i32le(descriptor + 2)
            // Le bit 30 signale du texte 8 bits ; l'adresse réelle est alors
            // la moitié de la valeur, une fois ce bit retiré.
            val compressed = (fc and 0x40000000) != 0
            val position = if (compressed) (fc and 0x3FFFFFFF) / 2 else fc
            if (position < 0) continue

            if (compressed) {
                if (position + length > word.size) continue
                sb.append(String(word, position, length, CP1252))
            } else {
                if (position + length * 2 > word.size) continue
                sb.append(String(word, position, length * 2, Charsets.UTF_16LE))
            }
        }
        return sb.toString().ifEmpty { null }
    }

    /** Repli quand la table de morceaux est absente ou illisible. */
    private fun extractRaw(word: ByteArray): String {
        val from = word.i32le(0x18)
        val to = word.i32le(0x1C)
        if (from in 0 until to && to <= word.size) {
            return String(word, from, to - from, CP1252)
        }
        return ""
    }

    private fun cleanup(text: String): String = buildString {
        for (ch in text) {
            when {
                ch == '\u0007' || ch == '\u0001' || ch == '\u0002' -> Unit
                ch == '\u0013' || ch == '\u0014' || ch == '\u0015' -> Unit
                ch == '\t' -> append('\t')
                ch.code >= 0x20 -> append(ch)
            }
        }
    }.trimEnd()
}

// ---------------------------------------------------------------- .xls

object XlsLegacy {

    private const val BOF = 0x0809
    private const val EOF_RECORD = 0x000A
    private const val SUBSTREAM_WORKSHEET = 0x0010
    private const val CONTINUE = 0x003C
    private const val SST = 0x00FC
    private const val BOUNDSHEET = 0x0085
    private const val LABELSST = 0x00FD
    private const val LABEL = 0x0204
    private const val NUMBER = 0x0203
    private const val RK = 0x027E
    private const val MULRK = 0x00BD
    private const val FORMULA = 0x0006
    private const val STRING_RECORD = 0x0207
    private const val BOOLERR = 0x0205
    private const val RSTRING = 0x00D6

    private class Record(val type: Int, val data: ByteArray)

    fun read(bytes: ByteArray, title: String = "Classeur"): Workbook {
        val cfb = Cfb.open(bytes)
        val stream = cfb.firstStream("Workbook", "Book")
            ?: throw FormatException("Ce .xls ne contient pas de flux Workbook")

        val records = readRecords(stream)
        val shared = readSharedStrings(records)
        val names = records.filter { it.type == BOUNDSHEET }.map { readBoundSheetName(it.data) }

        val sheets = ArrayList<Sheet>()
        var current: MutableMap<String, String>? = null
        var maxRow = 0
        var maxColumn = 0
        var pendingStringCell: String? = null

        fun closeSheet() {
            val cells = current ?: return
            sheets.add(
                Sheet(
                    name = names.getOrElse(sheets.size) { "Feuille${sheets.size + 1}" },
                    cells = cells,
                    columns = maxOf(maxColumn, 12),
                    rows = maxOf(maxRow, 40)
                )
            )
            current = null
            maxRow = 0; maxColumn = 0
        }

        fun put(row: Int, column: Int, value: String) {
            if (value.isEmpty()) return
            val cells = current ?: return
            cells[CellRef.key(row, column)] = value
            maxRow = maxOf(maxRow, row + 1)
            maxColumn = maxOf(maxColumn, column + 1)
        }

        records.forEach { record ->
            when (record.type) {
                // Les sous-flux se suivent au lieu de s'imbriquer : le champ
                // `dt` du BOF dit si celui qui s'ouvre est une feuille.
                BOF -> if (record.data.u16le(2) == SUBSTREAM_WORKSHEET) current = LinkedHashMap()
                EOF_RECORD -> closeSheet()
                LABELSST -> {
                    val index = record.data.i32le(6)
                    put(record.data.u16le(0), record.data.u16le(2), shared.getOrElse(index) { "" })
                }
                LABEL, RSTRING -> {
                    put(
                        record.data.u16le(0), record.data.u16le(2),
                        readInlineString(record.data, 6)
                    )
                }
                NUMBER -> put(
                    record.data.u16le(0), record.data.u16le(2),
                    formatNumber(record.data.f64le(6))
                )
                RK -> put(
                    record.data.u16le(0), record.data.u16le(2),
                    formatNumber(decodeRk(record.data.i32le(6)))
                )
                MULRK -> {
                    val row = record.data.u16le(0)
                    val first = record.data.u16le(2)
                    var offset = 4
                    var column = first
                    while (offset + 6 <= record.data.size - 2) {
                        put(row, column, formatNumber(decodeRk(record.data.i32le(offset + 2))))
                        offset += 6
                        column++
                    }
                }
                BOOLERR -> {
                    val value = record.data.u8(6)
                    val isError = record.data.u8(7) != 0
                    put(
                        record.data.u16le(0), record.data.u16le(2),
                        if (isError) "#ERREUR" else if (value != 0) "VRAI" else "FAUX"
                    )
                }
                FORMULA -> {
                    val row = record.data.u16le(0)
                    val column = record.data.u16le(2)
                    // Un résultat dont les deux derniers octets valent 0xFFFF
                    // n'est pas un nombre : la vraie valeur suit dans un STRING.
                    if (record.data.u16le(12) == 0xFFFF) {
                        pendingStringCell = CellRef.key(row, column)
                        maxRow = maxOf(maxRow, row + 1)
                        maxColumn = maxOf(maxColumn, column + 1)
                    } else {
                        put(row, column, formatNumber(record.data.f64le(6)))
                    }
                }
                STRING_RECORD -> {
                    val ref = pendingStringCell
                    if (ref != null) {
                        current?.put(ref, readInlineString(record.data, 0))
                        pendingStringCell = null
                    }
                }
            }
        }
        closeSheet()

        if (sheets.isEmpty()) throw FormatException("Ce .xls ne contient aucune feuille lisible")
        return Workbook(title, sheets)
    }

    private fun readRecords(stream: ByteArray): List<Record> {
        val out = ArrayList<Record>()
        var offset = 0
        while (offset + 4 <= stream.size) {
            val type = stream.u16le(offset)
            val length = stream.u16le(offset + 2)
            val from = offset + 4
            if (from + length > stream.size) break
            out.add(Record(type, stream.copyOfRange(from, from + length)))
            offset = from + length
        }
        return out
    }

    private fun readBoundSheetName(data: ByteArray): String {
        val length = data.u8(6)
        val flags = data.u8(7)
        return if (flags and 0x01 != 0) {
            String(data, 8, (length * 2).coerceAtMost(data.size - 8), Charsets.UTF_16LE)
        } else {
            String(data, 8, length.coerceAtMost(data.size - 8), CP1252)
        }
    }

    /**
     * La table de chaînes partagées déborde sur des enregistrements CONTINUE ;
     * l'octet d'options est réémis à chaque reprise, au milieu d'une chaîne s'il
     * le faut. On garde donc la trace des frontières.
     */
    private fun readSharedStrings(records: List<Record>): List<String> {
        val index = records.indexOfFirst { it.type == SST }
        if (index < 0) return emptyList()

        val chunks = ArrayList<ByteArray>()
        chunks.add(records[index].data)
        var i = index + 1
        while (i < records.size && records[i].type == CONTINUE) {
            chunks.add(records[i].data)
            i++
        }

        var total = 0
        val boundaries = HashSet<Int>()
        chunks.forEachIndexed { position, chunk ->
            if (position > 0) boundaries.add(total)
            total += chunk.size
        }
        val data = ByteArray(total)
        var written = 0
        chunks.forEach { chunk ->
            System.arraycopy(chunk, 0, data, written, chunk.size)
            written += chunk.size
        }

        val count = data.i32le(4)
        val reader = BiffStrings(data, boundaries)
        reader.position = 8
        val out = ArrayList<String>(count.coerceIn(0, 200_000))
        repeat(count.coerceIn(0, 200_000)) {
            val text = reader.read() ?: return@repeat
            out.add(text)
        }
        return out
    }

    private class BiffStrings(private val data: ByteArray, private val boundaries: Set<Int>) {
        var position = 0

        fun read(): String? {
            if (position + 3 > data.size) return null
            val characters = data.u16le(position)
            position += 2
            var flags = data.u8(position)
            position += 1
            var wide = flags and 0x01 != 0
            val rich = flags and 0x08 != 0
            val extended = flags and 0x04 != 0
            val runs = if (rich) data.u16le(position).also { position += 2 } else 0
            val extraBytes = if (extended) data.i32le(position).also { position += 4 } else 0

            val sb = StringBuilder(characters.coerceIn(0, 65_536))
            var read = 0
            while (read < characters && position < data.size) {
                if (position in boundaries) {
                    flags = data.u8(position)
                    position += 1
                    wide = flags and 0x01 != 0
                }
                if (wide) {
                    sb.append((data.u8(position) or (data.u8(position + 1) shl 8)).toChar())
                    position += 2
                } else {
                    sb.append(cp1252Char(data.u8(position)))
                    position += 1
                }
                read++
            }
            position += runs * 4 + extraBytes.coerceAtLeast(0)
            return sb.toString()
        }
    }

    private fun readInlineString(data: ByteArray, offset: Int): String {
        val characters = data.u16le(offset)
        val flags = data.u8(offset + 2)
        val start = offset + 3
        return if (flags and 0x01 != 0) {
            val length = (characters * 2).coerceAtMost((data.size - start).coerceAtLeast(0))
            String(data, start, length, Charsets.UTF_16LE)
        } else {
            val length = characters.coerceAtMost((data.size - start).coerceAtLeast(0))
            String(data, start, length, CP1252)
        }
    }

    /**
     * Un RK compresse un nombre sur 4 octets : les deux bits de poids faible
     * disent s'il s'agit d'un entier et s'il faut diviser par cent.
     */
    private fun decodeRk(raw: Int): Double {
        val hundredths = raw and 0x01 != 0
        val isInteger = raw and 0x02 != 0
        val value = if (isInteger) {
            (raw shr 2).toDouble()
        } else {
            Double.fromBits((raw.toLong() and 0xFFFFFFFCL) shl 32)
        }
        return if (hundredths) value / 100.0 else value
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}

// ---------------------------------------------------------------- .ppt

object PptLegacy {

    private const val SLIDE_CONTAINER = 0x03EE
    private const val TEXT_CHARS_ATOM = 0x0FA0
    private const val TEXT_BYTES_ATOM = 0x0FA8

    fun read(bytes: ByteArray, title: String = "Présentation"): Deck {
        val cfb = Cfb.open(bytes)
        val stream = cfb.firstStream("PowerPoint Document", "PP97_DUALSTORAGE")
            ?: throw FormatException("Ce .ppt ne contient pas de flux PowerPoint")

        val perSlide = ArrayList<MutableList<String>>()
        walk(stream, 0, stream.size, perSlide, insideSlide = false)

        val slides = perSlide.mapNotNull { blocks ->
            val cleaned = blocks.map { cleanup(it) }.filter { it.isNotBlank() }
            if (cleaned.isEmpty()) null
            else SlideModel(
                title = cleaned.first(),
                content = cleaned.drop(1).joinToString("\n")
            )
        }
        if (slides.isEmpty()) throw FormatException("Ce .ppt ne contient aucun texte lisible")
        return Deck(title, slides)
    }

    /**
     * Les enregistrements PowerPoint forment un arbre : l'en-tête fait 8 octets
     * et un conteneur se reconnaît à ses 4 bits de version à 0xF.
     */
    private fun walk(
        data: ByteArray,
        from: Int,
        to: Int,
        slides: MutableList<MutableList<String>>,
        insideSlide: Boolean,
        depth: Int = 0
    ) {
        if (depth > 24) return
        var offset = from
        while (offset + 8 <= to) {
            val versionInstance = data.u16le(offset)
            val type = data.u16le(offset + 2)
            val length = data.i32le(offset + 4)
            val body = offset + 8
            if (length < 0 || body + length > to) return

            val isContainer = (versionInstance and 0x000F) == 0x000F
            when {
                type == SLIDE_CONTAINER -> {
                    slides.add(ArrayList())
                    walk(data, body, body + length, slides, insideSlide = true, depth = depth + 1)
                }
                isContainer -> walk(data, body, body + length, slides, insideSlide, depth + 1)
                insideSlide && type == TEXT_CHARS_ATOM ->
                    slides.lastOrNull()?.add(String(data, body, length, Charsets.UTF_16LE))
                insideSlide && type == TEXT_BYTES_ATOM ->
                    slides.lastOrNull()?.add(String(data, body, length, CP1252))
            }
            offset = body + length
        }
    }

    private fun cleanup(text: String): String = text
        .replace('\r', '\n')
        .replace('\u000B', '\n')
        .filter { it == '\n' || it == '\t' || it.code >= 0x20 }
        .trim()
}
