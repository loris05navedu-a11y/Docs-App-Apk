package com.docssuite.fileformats

/**
 * Lecteur de « Compound File Binary Format » (OLE2), le conteneur des vieux
 * fichiers Office `.doc`, `.xls` et `.ppt`. C'est un système de fichiers
 * miniature : un en-tête, une table d'allocation (FAT) et un annuaire d'entrées
 * qui décrivent les flux nommés.
 */
class Cfb private constructor(
    private val data: ByteArray,
    private val sectorSize: Int,
    private val miniSectorSize: Int,
    private val fat: IntArray,
    private val miniFat: IntArray,
    private val entries: List<Entry>
) {

    class Entry(
        val name: String,
        val type: Int,
        val startSector: Int,
        val size: Long
    )

    /** Contenu d'un flux, ou `null` s'il n'existe pas. */
    fun stream(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.type == TYPE_STREAM && it.name == name } ?: return null
        return readEntry(entry)
    }

    /** Premier flux dont le nom correspond à l'un de [names]. */
    fun firstStream(vararg names: String): ByteArray? =
        names.firstNotNullOfOrNull { stream(it) }

    fun streamNames(): List<String> = entries.filter { it.type == TYPE_STREAM }.map { it.name }

    private fun readEntry(entry: Entry): ByteArray {
        if (entry.size < MINI_CUTOFF) {
            val root = entries.firstOrNull { it.type == TYPE_ROOT } ?: return ByteArray(0)
            val miniStream = readChain(root.startSector, fat, sectorSize, root.size)
            return readChain(entry.startSector, miniFat, miniSectorSize, entry.size, miniStream)
        }
        return readChain(entry.startSector, fat, sectorSize, entry.size)
    }

    private fun readChain(
        start: Int,
        allocation: IntArray,
        unit: Int,
        size: Long,
        source: ByteArray? = null,
        offsetBase: Int = if (source == null) sectorSize else 0
    ): ByteArray {
        val target = size.coerceAtMost(MAX_STREAM).toInt()
        val out = ByteArray(target)
        val bytes = source ?: data
        var sector = start
        var written = 0
        var guard = 0
        while (sector >= 0 && written < target && guard++ < MAX_SECTORS) {
            val from = offsetBase + sector * unit
            if (from < 0 || from >= bytes.size) break
            val length = minOf(unit, target - written, bytes.size - from)
            System.arraycopy(bytes, from, out, written, length)
            written += length
            sector = allocation.getOrElse(sector) { END_OF_CHAIN }
        }
        return if (written == target) out else out.copyOf(written)
    }

    companion object {
        private const val TYPE_STREAM = 2
        private const val TYPE_ROOT = 5
        private const val END_OF_CHAIN = -2
        private const val MINI_CUTOFF = 4096
        private const val MAX_STREAM = 48L * 1024 * 1024
        private const val MAX_SECTORS = 1 shl 21

        private val SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )

        fun looksLikeCfb(bytes: ByteArray): Boolean =
            bytes.size > 512 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

        fun open(bytes: ByteArray): Cfb {
            if (!looksLikeCfb(bytes)) throw FormatException("Ce fichier n'est pas un document Office 97-2003")

            val sectorSize = 1 shl u16(bytes, 0x1E)
            val miniSectorSize = 1 shl u16(bytes, 0x20)
            if (sectorSize < 128 || sectorSize > 1 shl 16) {
                throw FormatException("En-tête OLE2 illisible")
            }

            val fat = readFat(bytes, sectorSize)
            val miniFatStart = i32(bytes, 0x3C)
            val miniFat = readAllocationChain(bytes, fat, sectorSize, miniFatStart)
            val directoryStart = i32(bytes, 0x30)
            val entries = readDirectory(bytes, fat, sectorSize, directoryStart)

            return Cfb(bytes, sectorSize, miniSectorSize, fat, miniFat, entries)
        }

        /** Assemble la FAT à partir de la DIFAT (109 entrées en tête, puis chaînée). */
        private fun readFat(bytes: ByteArray, sectorSize: Int): IntArray {
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) {
                val sector = i32(bytes, 0x4C + i * 4)
                if (sector < 0) break
                fatSectors.add(sector)
            }

            var difatSector = i32(bytes, 0x44)
            var remaining = i32(bytes, 0x48)
            var guard = 0
            val perSector = sectorSize / 4
            while (difatSector >= 0 && remaining > 0 && guard++ < MAX_SECTORS) {
                val base = sectorSize + difatSector * sectorSize
                if (base + sectorSize > bytes.size) break
                for (i in 0 until perSector - 1) {
                    val sector = i32(bytes, base + i * 4)
                    if (sector >= 0) fatSectors.add(sector)
                }
                difatSector = i32(bytes, base + (perSector - 1) * 4)
                remaining--
            }

            val fat = IntArray(fatSectors.size * perSector) { END_OF_CHAIN }
            fatSectors.forEachIndexed { index, sector ->
                val base = sectorSize + sector * sectorSize
                if (base + sectorSize > bytes.size) return@forEachIndexed
                for (i in 0 until perSector) {
                    fat[index * perSector + i] = i32(bytes, base + i * 4)
                }
            }
            return fat
        }

        /** Lit une chaîne de secteurs et l'interprète comme un tableau d'entiers. */
        private fun readAllocationChain(
            bytes: ByteArray,
            fat: IntArray,
            sectorSize: Int,
            start: Int
        ): IntArray {
            val sectors = chainOf(fat, start)
            val out = IntArray(sectors.size * (sectorSize / 4)) { END_OF_CHAIN }
            sectors.forEachIndexed { index, sector ->
                val base = sectorSize + sector * sectorSize
                if (base + sectorSize > bytes.size) return@forEachIndexed
                for (i in 0 until sectorSize / 4) {
                    out[index * (sectorSize / 4) + i] = i32(bytes, base + i * 4)
                }
            }
            return out
        }

        private fun chainOf(fat: IntArray, start: Int): List<Int> {
            val out = ArrayList<Int>()
            var sector = start
            var guard = 0
            val seen = HashSet<Int>()
            while (sector >= 0 && guard++ < MAX_SECTORS && seen.add(sector)) {
                out.add(sector)
                sector = fat.getOrElse(sector) { END_OF_CHAIN }
            }
            return out
        }

        private fun readDirectory(
            bytes: ByteArray,
            fat: IntArray,
            sectorSize: Int,
            start: Int
        ): List<Entry> {
            val out = ArrayList<Entry>()
            chainOf(fat, start).forEach { sector ->
                val base = sectorSize + sector * sectorSize
                var offset = base
                while (offset + 128 <= base + sectorSize && offset + 128 <= bytes.size) {
                    val nameLength = u16(bytes, offset + 64)
                    val type = bytes[offset + 66].toInt() and 0xFF
                    if (type == TYPE_STREAM || type == TYPE_ROOT) {
                        // La longueur inclut le caractère nul final.
                        val chars = ((nameLength - 2).coerceAtLeast(0)) / 2
                        val name = String(bytes, offset, chars * 2, Charsets.UTF_16LE)
                        out.add(
                            Entry(
                                name = name,
                                type = type,
                                startSector = i32(bytes, offset + 116),
                                size = u32(bytes, offset + 120)
                            )
                        )
                    }
                    offset += 128
                }
            }
            return out
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            if (offset + 1 >= bytes.size) 0
            else (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun i32(bytes: ByteArray, offset: Int): Int =
            if (offset + 3 >= bytes.size) END_OF_CHAIN
            else (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            i32(bytes, offset).toLong() and 0xFFFFFFFFL
    }
}

// ---------------------------------------------------------------- accès binaire

internal fun ByteArray.u8(offset: Int): Int =
    if (offset < 0 || offset >= size) 0 else this[offset].toInt() and 0xFF

internal fun ByteArray.u16le(offset: Int): Int =
    if (offset + 1 >= size) 0 else u8(offset) or (u8(offset + 1) shl 8)

internal fun ByteArray.i32le(offset: Int): Int =
    if (offset + 3 >= size) 0
    else u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)

internal fun ByteArray.u32le(offset: Int): Long = i32le(offset).toLong() and 0xFFFFFFFFL

internal fun ByteArray.f64le(offset: Int): Double {
    if (offset + 7 >= size) return 0.0
    var bits = 0L
    for (i in 7 downTo 0) bits = (bits shl 8) or (this[offset + i].toLong() and 0xFF)
    return Double.fromBits(bits)
}
