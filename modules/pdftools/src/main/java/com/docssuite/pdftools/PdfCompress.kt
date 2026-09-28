package com.docssuite.pdftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Jusqu'où réduire. [maxSide] : le plus grand côté d'une image, en pixels —
 * 1 600 px couvrent une page A4 à 190 points par pouce, bien assez pour
 * l'écran et une impression de bureau.
 */
enum class CompressionLevel(val label: String, val hint: String, val maxSide: Int, val quality: Int) {
    LIGHT("Légère", "Presque aucune perte visible", 2400, 85),
    BALANCED("Équilibrée", "Le bon compromis pour partager", 1600, 72),
    STRONG("Forte", "Le plus léger, pour un e-mail", 1100, 55)
}

data class CompressionResult(
    val bytes: ByteArray,
    val before: Int,
    val after: Int,
    val imagesReduced: Int
) {
    /** Part gagnée, de 0 à 1. */
    val saved: Float get() = if (before == 0) 0f else (before - after).toFloat() / before
}

/**
 * Réduire un PDF : les photos et scans, qui font l'essentiel du poids, sont
 * ramenés à une taille raisonnable et recompressés en JPEG ; les flux
 * laissés sans compression sont compressés. Le texte, les polices, les
 * dessins et la structure (signets, liens, formulaires) ne changent pas.
 *
 * Une image n'est remplacée que si elle y gagne vraiment ; le document
 * n'est rendu que s'il a maigri — sinon c'est l'original qui revient.
 */
object PdfCompress {

    /** En dessous, une image est une icône ou un logo : on n'y touche pas. */
    private const val MIN_PIXELS = 40_000

    fun compress(bytes: ByteArray, level: CompressionLevel, password: String? = null): CompressionResult {
        val file = PdfFile.parse(bytes, password)
        val rewrite = PdfRewrite(file)
        var reduced = 0
        file.objectNumbers().forEach { num ->
            val stream = file.getObject(num) as? PdfStream ?: return@forEach
            val replacement = if (stream.dict.nameOf("Subtype") == "Image") {
                runCatching { image(file, stream, level) }.getOrNull()?.also { reduced++ }
            } else {
                runCatching { recompress(file, stream) }.getOrNull()
            }
            if (replacement != null) rewrite.replace(num, replacement)
        }
        val out = rewrite.write(PdfLock.carry(file, password))
        return if (out.size < bytes.size) {
            CompressionResult(out, bytes.size, out.size, reduced)
        } else {
            CompressionResult(bytes, bytes.size, bytes.size, 0)
        }
    }

    /** Une photo réduite et recompressée, ou `null` si elle ne s'y prête pas. */
    private fun image(file: PdfFile, stream: PdfStream, level: CompressionLevel): PdfStream? {
        val dict = stream.dict
        fun int(key: String) = (file.resolve(dict[key]) as? PdfNumber)?.intValue
        val width = int("Width") ?: return null
        val height = int("Height") ?: return null
        if (width.toLong() * height < MIN_PIXELS) return null
        if ((file.resolve(dict["ImageMask"]) as? PdfBool)?.value == true) return null
        if (int("BitsPerComponent") != 8) return null
        // Un masque par couleur clé ou un décodage inversé ne survivent pas au JPEG.
        if (file.resolve(dict["Mask"]) is PdfArray || dict["Decode"] != null) return null
        val components = components(file, dict["ColorSpace"]) ?: return null

        val filters = when (val f = file.resolve(dict["Filter"])) {
            is PdfName -> listOf(f.raw)
            is PdfArray -> f.items.map { (file.resolve(it) as? PdfName)?.raw }
            else -> emptyList()
        }
        val bitmap = when (filters) {
            listOf("DCTDecode") -> decodeJpeg(stream.data, width, height, level.maxSide) ?: return null
            listOf("FlateDecode"), emptyList<String>() -> {
                val raw = if (filters.isEmpty()) stream.data else PdfFilters.decode(stream, file::resolve)
                pixels(raw, width, height, components) ?: return null
            }
            else -> return null
        }
        val scaled = scale(bitmap, level.maxSide)
        val jpeg = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, level.quality, out)) return null
            out.toByteArray()
        }
        val newWidth = scaled.width
        val newHeight = scaled.height
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        // Un gain de moins de 10 % ne vaut pas une perte de qualité.
        if (jpeg.size > stream.data.size * 0.9) return null

        val copy = dict.copy().apply {
            listOf("Length", "DecodeParms", "Filter").forEach { remove(it) }
            set("Filter", PdfName("DCTDecode"))
            set("Width", PdfNumber.of(newWidth))
            set("Height", PdfNumber.of(newHeight))
            set("BitsPerComponent", PdfNumber.of(8))
            // Le JPEG écrit est toujours en couleur, même pour un scan en gris.
            set("ColorSpace", PdfName("DeviceRGB"))
        }
        return PdfStream(copy, jpeg)
    }

    /** 1 pour du gris, 3 pour de la couleur ; `null` pour le reste (CMJN, palettes…). */
    private fun components(file: PdfFile, colorSpace: PdfObject?): Int? = when (val cs = file.resolve(colorSpace)) {
        is PdfName -> when (cs.raw) {
            "DeviceRGB", "CalRGB", "RGB" -> 3
            "DeviceGray", "CalGray", "G" -> 1
            else -> null
        }
        is PdfArray -> when ((file.resolve(cs.items.firstOrNull()) as? PdfName)?.raw) {
            "ICCBased" -> (file.resolve(cs.items.getOrNull(1)) as? PdfStream)?.dict?.let {
                (file.resolve(it["N"]) as? PdfNumber)?.intValue
            }?.takeIf { it == 1 || it == 3 }
            "CalRGB" -> 3
            "CalGray" -> 1
            else -> null
        }
        else -> null
    }

    /** Décodage réduit dès la lecture : une photo de 12 Mpx n'entre jamais entière en mémoire. */
    private fun decodeJpeg(data: ByteArray, width: Int, height: Int, maxSide: Int): Bitmap? {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun pixels(raw: ByteArray, width: Int, height: Int, components: Int): Bitmap? {
        val count = width * height
        if (raw.size < count * components) return null
        val colors = IntArray(count)
        for (i in 0 until count) {
            colors[i] = if (components == 1) {
                val g = raw[i].toInt() and 0xFF
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            } else {
                val o = i * 3
                (0xFF shl 24) or ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
            }
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun scale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * ratio).toInt()),
            maxOf(1, (bitmap.height * ratio).toInt()),
            true
        )
    }

    /**
     * Les autres flux : compressés s'ils ne l'étaient pas, recompressés au
     * mieux s'ils l'étaient mal. Les métadonnées XMP restent lisibles en clair.
     */
    private fun recompress(file: PdfFile, stream: PdfStream): PdfStream? {
        val dict = stream.dict
        if (dict.nameOf("Type") == "Metadata" || stream.data.size < 256) return null
        val filter = file.resolve(dict["Filter"])
        val raw = when {
            filter == null || filter == PdfNull -> stream.data
            filter is PdfName && filter.raw == "FlateDecode" && dict["DecodeParms"] == null -> inflateWhole(stream.data) ?: return null
            else -> return null
        }
        val packed = deflate(raw)
        if (packed.size >= stream.data.size * 0.95) return null
        return PdfStream(dict.copy().apply {
            remove("Length")
            set("Filter", PdfName("FlateDecode"))
        }, packed)
    }

    /** Seulement un flux complet : un flux abîmé est laissé tel quel, pas réécrit tronqué. */
    private fun inflateWhole(data: ByteArray): ByteArray? {
        val inflater = java.util.zip.Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 3 + 64)
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}
