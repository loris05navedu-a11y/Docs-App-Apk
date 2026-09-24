package com.docssuite.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Structure du PDF. Les « JPEG » ici n'ont que leurs en-têtes (APP0, DQT,
 * SOF0) : c'est tout ce que lit l'écrivain, qui recopie le reste tel quel.
 * De vraies photos encodées par Android sont exportées dans
 * `ScanImagingTest`.
 */
class ScanPdfTest {

    private fun jpeg(width: Int, height: Int, components: Int = 3): ByteArray {
        val out = ByteArrayOutputStream()
        fun b(vararg v: Int) = v.forEach { out.write(it) }
        fun u16(v: Int) = b(v shr 8 and 0xFF, v and 0xFF)
        b(0xFF, 0xD8)
        b(0xFF, 0xE0); u16(16); "JFIF".forEach { out.write(it.code) }; b(0, 1, 1, 0); u16(1); u16(1); b(0, 0)
        b(0xFF, 0xDB); u16(67); b(0); repeat(64) { b(1) }
        b(0xFF, 0xC0); u16(8 + 3 * components); b(8); u16(height); u16(width); b(components)
        repeat(components) { b(it + 1, 0x11, 0) }
        b(0xFF, 0xD9)
        return out.toByteArray()
    }

    private fun latin1(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun `l en-tete d un jpeg donne ses dimensions et ses composantes`() {
        assertEquals(JpegInfo(320, 200, 3), readJpegInfo(jpeg(320, 200)))
        assertEquals(1, readJpegInfo(jpeg(50, 60, 1)).components)
    }

    @Test
    fun `chaque entree de la table xref pointe sur son objet`() {
        val pdf = buildPdf(listOf(jpeg(400, 560), jpeg(600, 400)), PageFormat.A4, "Scan", "20260924120000")
        val text = latin1(pdf)

        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))

        val startXref = Regex("startxref\\n(\\d+)").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref", startXref))

        val entries = Regex("(\\d{10}) 00000 n ").findAll(text.substring(startXref)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(2 + 2 * 3 + 1, entries.size)
        entries.forEachIndexed { index, offset ->
            assertTrue("objet ${index + 1}", text.startsWith("${index + 1} 0 obj", offset))
        }
    }

    @Test
    fun `les octets du jpeg sont repris tels quels`() {
        val image = jpeg(300, 300)
        val pdf = buildPdf(listOf(image), PageFormat.IMAGE, "Scan", "20260924120000")
        val text = latin1(pdf)
        assertTrue(text.contains(latin1(image)))
        assertTrue(text.contains("/Length ${image.size}"))
        assertTrue(text.contains("/DCTDecode"))
    }

    @Test
    fun `une page en niveaux de gris est declaree comme telle`() {
        val pdf = buildPdf(listOf(jpeg(80, 100, 1)), PageFormat.A4, "x", "20260924120000")
        assertTrue(latin1(pdf).contains("/ColorSpace /DeviceGray"))
    }

    @Test
    fun `un titre accentue avec parentheses reste lisible`() {
        val pdf = buildPdf(listOf(jpeg(10, 10)), PageFormat.A4, "Reçu (mai)", "20260924120000")
        // R e ç u ␠ ( m a i ) en UTF-16BE.
        assertTrue(latin1(pdf).contains("/Title <FEFF0052006500E7007500200028006D006100690029>"))
    }

    @Test
    fun `une image en hauteur remplit la largeur d une page a4 portrait`() {
        val layout = layoutPage(1000, 2000, PageFormat.A4)
        assertEquals(595.28f, layout.pageWidth, 0.01f)
        assertEquals(841.89f, layout.pageHeight, 0.01f)
        assertEquals(420.95f, layout.width, 0.01f) // limitée par la hauteur
        assertEquals(841.89f, layout.height, 0.01f)
        assertEquals((595.28f - 420.95f) / 2, layout.x, 0.01f)
    }

    @Test
    fun `une image en largeur donne une page paysage`() {
        val layout = layoutPage(3000, 2000, PageFormat.LETTER)
        assertTrue(layout.pageWidth > layout.pageHeight)
    }

    @Test
    fun `au format de l image la page n a aucune marge`() {
        val layout = layoutPage(1200, 1600, PageFormat.IMAGE)
        assertEquals(0f, layout.x, 0.01f)
        assertEquals(0f, layout.y, 0.01f)
        assertEquals(layout.pageWidth / layout.pageHeight, 1200f / 1600f, 0.001f)
    }
}
