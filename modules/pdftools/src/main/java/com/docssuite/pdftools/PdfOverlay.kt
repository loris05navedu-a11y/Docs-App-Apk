package com.docssuite.pdftools

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.Deflater

/**
 * Ce qu'on pose sur une page pour la signer ou la remplir. Les positions
 * et tailles sont des fractions de la page telle qu'elle s'affiche
 * (rotation comprise), coin haut-gauche : exactement ce que voit
 * l'utilisateur quand il place l'élément du doigt.
 */
sealed interface OverlayItem {
    val u: Float
    val v: Float

    /** [size] : hauteur des lettres, en fraction de la largeur affichée. */
    data class Text(override val u: Float, override val v: Float, val text: String, val size: Float, val color: Int = 0xFF000000.toInt()) : OverlayItem

    /** Une coche ✔ pour une case de formulaire. */
    data class Check(override val u: Float, override val v: Float, val size: Float, val color: Int = 0xFF000000.toInt()) : OverlayItem

    /** Une image avec transparence (signature, paraphe), en ARGB. */
    class Image(
        override val u: Float,
        override val v: Float,
        val width: Float,
        val height: Float,
        val pixels: IntArray,
        val pixelWidth: Int,
        val pixelHeight: Int
    ) : OverlayItem
}

/**
 * La page affichée : sa zone visible (CropBox, sinon MediaBox) et sa
 * rotation. Donne la matrice qui passe du repère « comme à l'écran »
 * (largeur × hauteur affichées, origine en bas à gauche) au repère du PDF.
 */
class PageGeometry(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rotation: Int) {
    private val w get() = x1 - x0
    private val h get() = y1 - y0
    private val sideways get() = rotation == 90 || rotation == 270

    val displayWidth: Float get() = if (sideways) h else w
    val displayHeight: Float get() = if (sideways) w else h

    /** `a b c d e f` : X = a·x + c·y + e, Y = b·x + d·y + f. */
    fun matrix(): FloatArray = when (rotation) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, x1, y0)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, x1, y1)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, x0, y1)
        else -> floatArrayOf(1f, 0f, 0f, 1f, x0, y0)
    }

    /** Point affiché (fractions, origine en haut à gauche) → point du PDF. */
    fun toPdf(u: Float, v: Float): Pair<Float, Float> {
        val x = u * displayWidth
        val y = (1 - v) * displayHeight
        val m = matrix()
        return (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])
    }

    companion object {
        fun of(file: PdfFile, page: PdfPage, rotation: Int): PageGeometry {
            val box = (file.resolve(page.attribute("CropBox")) as? PdfArray ?: file.resolve(page.attribute("MediaBox")) as? PdfArray)
                ?.items?.mapNotNull { (file.resolve(it) as? PdfNumber)?.doubleValue?.toFloat() }
                ?.takeIf { it.size == 4 }
                ?: listOf(0f, 0f, 612f, 792f)
            return PageGeometry(
                minOf(box[0], box[2]), minOf(box[1], box[3]), maxOf(box[0], box[2]), maxOf(box[1], box[3]),
                Math.floorMod(rotation, 360) / 90 * 90
            )
        }
    }
}

/** Ce qu'il faut ajouter au PDF pour une page : un flux de contenu et ses images. */
internal class OverlayContent(val content: ByteArray, val images: List<Pair<String, OverlayItem.Image>>)

internal object OverlayWriter {

    const val FONT = "DocsHelv"
    const val CHECK_FONT = "DocsZapf"

    private val winAnsi: Charset = Charset.forName("windows-1252")

    fun build(geometry: PageGeometry, items: List<OverlayItem>): OverlayContent {
        val out = ByteArrayOutputStream()
        val images = ArrayList<Pair<String, OverlayItem.Image>>()
        val m = geometry.matrix()
        val dw = geometry.displayWidth
        val dh = geometry.displayHeight
        out.ascii("q ${m.joinToString(" ") { n(it) }} cm\n")
        items.forEach { item ->
            val left = item.u * dw
            val top = (1 - item.v) * dh
            when (item) {
                is OverlayItem.Text -> {
                    val size = item.size * dw
                    val leading = size * 1.2f
                    out.ascii("BT /$FONT ${n(size)} Tf ${rgb(item.color)} rg ${n(leading)} TL ${n(left)} ${n(top - size * 0.8f)} Td\n")
                    item.text.split('\n').forEachIndexed { i, line ->
                        if (i > 0) out.ascii("T* ")
                        out.ascii("(")
                        out.write(escape(encode(line)))
                        out.ascii(") Tj\n")
                    }
                    out.ascii("ET\n")
                }
                is OverlayItem.Check -> {
                    val size = item.size * dw
                    // « 4 » est la coche ✔ dans la police ZapfDingbats.
                    out.ascii("BT /$CHECK_FONT ${n(size)} Tf ${rgb(item.color)} rg ${n(left)} ${n(top - size * 0.8f)} Td (4) Tj ET\n")
                }
                is OverlayItem.Image -> {
                    val name = "DocsIm${images.size + 1}"
                    images.add(name to item)
                    val w = item.width * dw
                    val h = item.height * dh
                    out.ascii("q ${n(w)} 0 0 ${n(h)} ${n(left)} ${n(top - h)} cm /$name Do Q\n")
                }
            }
        }
        out.ascii("Q\n")
        return OverlayContent(out.toByteArray(), images)
    }

    /** L'image en RGB compressé, et sa transparence à part (masque `SMask`), comme l'exige le PDF. */
    fun imageStreams(image: OverlayItem.Image): Pair<PdfStream, PdfStream> {
        val count = image.pixelWidth * image.pixelHeight
        val rgb = ByteArray(count * 3)
        val alpha = ByteArray(count)
        for (i in 0 until count) {
            val p = image.pixels[i]
            rgb[i * 3] = (p shr 16).toByte()
            rgb[i * 3 + 1] = (p shr 8).toByte()
            rgb[i * 3 + 2] = p.toByte()
            alpha[i] = (p ushr 24).toByte()
        }
        fun dict(colorSpace: String) = PdfDict().apply {
            set("Type", PdfName("XObject"))
            set("Subtype", PdfName("Image"))
            set("Width", PdfNumber.of(image.pixelWidth))
            set("Height", PdfNumber.of(image.pixelHeight))
            set("ColorSpace", PdfName(colorSpace))
            set("BitsPerComponent", PdfNumber.of(8))
            set("Filter", PdfName("FlateDecode"))
        }
        return PdfStream(dict("DeviceRGB"), deflate(rgb)) to PdfStream(dict("DeviceGray"), deflate(alpha))
    }

    fun helvetica() = PdfDict().apply {
        set("Type", PdfName("Font"))
        set("Subtype", PdfName("Type1"))
        set("BaseFont", PdfName("Helvetica"))
        set("Encoding", PdfName("WinAnsiEncoding"))
    }

    fun zapf() = PdfDict().apply {
        set("Type", PdfName("Font"))
        set("Subtype", PdfName("Type1"))
        set("BaseFont", PdfName("ZapfDingbats"))
    }

    /** Accents, €, œ passent en WinAnsi ; un caractère hors de cet alphabet devient « ? ». */
    fun encode(text: String): ByteArray {
        val encoder = winAnsi.newEncoder()
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .onMalformedInput(CodingErrorAction.REPLACE)
            .replaceWith(byteArrayOf('?'.code.toByte()))
        val buffer: ByteBuffer = encoder.encode(CharBuffer.wrap(text.replace(' ', ' ').replace(' ', ' ')))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun escape(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + 8)
        bytes.forEach { b ->
            if (b == '('.code.toByte() || b == ')'.code.toByte() || b == '\\'.code.toByte()) out.write('\\'.code)
            out.write(b.toInt())
        }
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun rgb(color: Int) = listOf(16, 8, 0).joinToString(" ") { n(((color shr it) and 0xFF) / 255f) }

    private fun n(value: Float): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
