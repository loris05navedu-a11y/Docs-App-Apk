package com.docssuite.scanner

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Écrit un PDF dont chaque page est une image JPEG, insérée telle quelle
 * (filtre `DCTDecode`) : le fichier pèse à peu près la somme des photos.
 * Le `PdfDocument` d'Android, lui, recompresse les images sans perte et
 * produirait facilement une dizaine de mégaoctets par page scannée.
 */

enum class PageFormat(val label: String) {
    A4("A4"),
    LETTER("Lettre US"),
    IMAGE("Taille de l'image")
}

/** Emplacement de l'image sur sa page, en points PDF (1/72 de pouce), origine en bas à gauche. */
data class PageLayout(
    val pageWidth: Float,
    val pageHeight: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * Page portrait ou paysage selon l'image, qui y est agrandie au maximum sans
 * déformation et centrée.
 */
fun layoutPage(imageWidth: Int, imageHeight: Int, format: PageFormat): PageLayout {
    require(imageWidth > 0 && imageHeight > 0) { "Image vide" }
    val landscape = imageWidth > imageHeight
    val (shortSide, longSide) = when (format) {
        PageFormat.A4 -> 595.28f to 841.89f
        PageFormat.LETTER -> 612f to 792f
        PageFormat.IMAGE -> {
            val long = 841.89f
            val short = long * minOf(imageWidth, imageHeight) / maxOf(imageWidth, imageHeight)
            short to long
        }
    }
    val pageWidth = if (landscape) longSide else shortSide
    val pageHeight = if (landscape) shortSide else longSide
    val scale = minOf(pageWidth / imageWidth, pageHeight / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    return PageLayout(pageWidth, pageHeight, (pageWidth - w) / 2, (pageHeight - h) / 2, w, h)
}

/** Dimensions et nombre de composantes lus dans l'en-tête SOF du JPEG. */
data class JpegInfo(val width: Int, val height: Int, val components: Int)

fun readJpegInfo(jpeg: ByteArray): JpegInfo {
    fun u8(i: Int) = jpeg[i].toInt() and 0xFF
    fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    if (jpeg.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) throw IllegalArgumentException("Ce n'est pas un JPEG")
    var i = 2
    while (i + 9 < jpeg.size) {
        if (u8(i) != 0xFF) { i++; continue }
        val marker = u8(i + 1)
        if (marker == 0xFF) { i++; continue }
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
        val length = u16(i + 2)
        // SOF0 à SOF15, sauf DHT (C4), JPG (C8) et DAC (CC).
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            return JpegInfo(width = u16(i + 7), height = u16(i + 5), components = u8(i + 9))
        }
        i += 2 + length
    }
    throw IllegalArgumentException("En-tête JPEG incomplet")
}

fun buildPdf(jpegPages: List<ByteArray>, format: PageFormat, title: String, creationDate: String): ByteArray {
    require(jpegPages.isNotEmpty()) { "Aucune page à exporter" }
    val out = ByteArrayOutputStream()
    val offsets = ArrayList<Int>()

    fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
    fun beginObject(): Int {
        offsets.add(out.size())
        val id = offsets.size
        write("$id 0 obj\n")
        return id
    }

    write("%PDF-1.4\n%âãÏÓ\n")

    // Objets 1 (catalogue) et 2 (arbre des pages) ; chaque page occupe
    // ensuite trois objets : la page, son contenu, son image.
    val pageIds = jpegPages.indices.map { 3 + it * 3 }
    beginObject(); write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    beginObject()
    write("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${jpegPages.size} >>\nendobj\n")

    jpegPages.forEach { jpeg ->
        val info = readJpegInfo(jpeg)
        val layout = layoutPage(info.width, info.height, format)
        val pageId = beginObject()
        write(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(layout.pageWidth)} ${n(layout.pageHeight)}] " +
                "/Resources << /XObject << /Im0 ${pageId + 2} 0 R >> >> /Contents ${pageId + 1} 0 R >>\nendobj\n"
        )
        val content = "q ${n(layout.width)} 0 0 ${n(layout.height)} ${n(layout.x)} ${n(layout.y)} cm /Im0 Do Q"
        beginObject()
        write("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

        val colorSpace = when (info.components) {
            1 -> "/DeviceGray"
            4 -> "/DeviceCMYK"
            else -> "/DeviceRGB"
        }
        beginObject()
        write(
            "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
        )
        out.write(jpeg)
        write("\nendstream\nendobj\n")
    }

    val infoId = beginObject()
    write("<< /Title ${pdfTextString(title)} /Producer (DocsApp Suite) /CreationDate (D:$creationDate) >>\nendobj\n")

    val xref = out.size()
    write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { write(String.format(Locale.US, "%010d 00000 n \n", it)) }
    write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info $infoId 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toByteArray()
}

private fun n(value: Float): String = String.format(Locale.US, "%.2f", value)

/** Chaîne PDF en UTF-16BE hexadécimal : accents et parenthèses passent sans échappement. */
private fun pdfTextString(text: String): String {
    val builder = StringBuilder("<FEFF")
    text.forEach { builder.append(String.format(Locale.US, "%04X", it.code)) }
    return builder.append('>').toString()
}
