package com.docssuite.pdftools

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Décompression des seuls flux dont on doit lire le contenu : tables de
 * références (`/XRef`) et flux d'objets (`/ObjStm`). Le contenu des pages,
 * lui, est recopié sans jamais être décompressé.
 */
internal object PdfFilters {

    fun decode(stream: PdfStream, resolve: (PdfObject?) -> PdfObject?): ByteArray {
        val filters = when (val f = resolve(stream.dict["Filter"])) {
            null, PdfNull -> emptyList()
            is PdfName -> listOf(f.raw)
            is PdfArray -> f.items.map { (resolve(it) as? PdfName)?.raw ?: "" }
            else -> throw PdfFormatException("Filtre illisible")
        }
        val params = when (val p = resolve(stream.dict["DecodeParms"])) {
            is PdfDict -> listOf(p)
            is PdfArray -> p.items.map { resolve(it) as? PdfDict }
            else -> emptyList()
        }
        var data = stream.data
        filters.forEachIndexed { i, name ->
            data = when (name) {
                "FlateDecode", "Fl" -> unpredict(inflate(data), params.getOrNull(i), resolve)
                else -> throw PdfFormatException("Compression « $name » non prise en charge pour la structure du fichier")
            }
        }
        return data
    }

    /** Tolère un flux tronqué : on garde ce qui a pu être décompressé. */
    fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(16 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, n)
                }
            }
        } catch (error: java.util.zip.DataFormatException) {
            if (out.size() == 0) throw PdfFormatException("Flux compressé illisible")
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun intParam(dict: PdfDict?, key: String, default: Int, resolve: (PdfObject?) -> PdfObject?): Int =
        (resolve(dict?.get(key)) as? PdfNumber)?.intValue ?: default

    /** Prédicteurs PNG (10 à 15) et TIFF (2), § 7.4.4.4. */
    fun unpredict(data: ByteArray, params: PdfDict?, resolve: (PdfObject?) -> PdfObject? = { it }): ByteArray {
        val predictor = intParam(params, "Predictor", 1, resolve)
        if (predictor < 2) return data
        val colors = intParam(params, "Colors", 1, resolve)
        val bits = intParam(params, "BitsPerComponent", 8, resolve)
        val columns = intParam(params, "Columns", 1, resolve)
        val bpp = maxOf(1, (colors * bits + 7) / 8)
        val rowLength = (columns * colors * bits + 7) / 8

        if (predictor == 2) {
            if (bits != 8) return data
            val out = data.copyOf()
            var row = 0
            while (row + rowLength <= out.size) {
                for (i in bpp until rowLength) {
                    out[row + i] = (out[row + i] + out[row + i - bpp]).toByte()
                }
                row += rowLength
            }
            return out
        }

        val out = ByteArrayOutputStream(data.size)
        var previous = ByteArray(rowLength)
        var i = 0
        while (i + 1 + rowLength <= data.size) {
            val type = data[i].toInt() and 0xFF
            val row = data.copyOfRange(i + 1, i + 1 + rowLength)
            for (x in 0 until rowLength) {
                val left = if (x >= bpp) row[x - bpp].toInt() and 0xFF else 0
                val up = previous[x].toInt() and 0xFF
                val upLeft = if (x >= bpp) previous[x - bpp].toInt() and 0xFF else 0
                val raw = row[x].toInt() and 0xFF
                val value = when (type) {
                    0 -> raw
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> raw + paeth(left, up, upLeft)
                    else -> throw PdfFormatException("Prédicteur PNG $type inconnu")
                }
                row[x] = value.toByte()
            }
            out.write(row)
            previous = row
            i += 1 + rowLength
        }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }
}
