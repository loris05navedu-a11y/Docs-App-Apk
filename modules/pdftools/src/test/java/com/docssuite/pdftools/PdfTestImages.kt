package com.docssuite.pdftools

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.sin

/** Des PDF à photos, fabriqués à la volée : trop lourds pour être gardés en fichiers de test. */
object PdfTestImages {

    /** Une « photo » : dégradés et motifs, ce qui se compresse mal sans perte. */
    fun photo(width: Int, height: Int, gray: Boolean = false): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        var r = ((x * 255 / width) + (sin(y / 7.0) * 40).toInt()).coerceIn(0, 255)
        var g = ((y * 255 / height) + (sin(x / 11.0) * 40).toInt()).coerceIn(0, 255)
        var b = (((x + y) * 3 + (x * y) % 97) % 256)
        if (gray) {
            val l = (r * 3 + g * 5 + b * 2) / 10
            r = l; g = l; b = l
        }
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun jpeg(pixels: IntArray, width: Int, height: Int, quality: Int = 95): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
    }

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    }

    /** Un PDF d'une page A4 portant une image, et un peu de texte. */
    fun pdfWithImage(imageDict: String, imageData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(body: String, stream: ByteArray? = null) {
            offsets.add(out.size())
            out.write("${offsets.size} 0 obj\n$body".toByteArray(Charsets.ISO_8859_1))
            if (stream != null) {
                out.write("\nstream\n".toByteArray()); out.write(stream); out.write("\nendstream".toByteArray())
            }
            out.write("\nendobj\n".toByteArray())
        }
        out.write("%PDF-1.4\n".toByteArray())
        val content = "q 500 0 0 375 47 400 cm /Im1 Do Q BT /F1 14 Tf 72 780 Td (Photo de chantier) Tj ET".toByteArray()
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im1 5 0 R >> /Font << /F1 6 0 R >> >> /Contents 4 0 R >>")
        obj("<< /Length ${content.size} >>", content)
        obj("<< /Type /XObject /Subtype /Image $imageDict /Length ${imageData.size} >>", imageData)
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val xref = out.size()
        out.write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { out.write(String.format(Locale.US, "%010d 00000 n \n", it).toByteArray()) }
        out.write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    fun jpegPdf(width: Int = 2400, height: Int = 1800, gray: Boolean = false): ByteArray {
        val data = jpeg(photo(width, height, gray), width, height)
        return pdfWithImage("/Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", data)
    }

}
