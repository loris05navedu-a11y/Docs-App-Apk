package com.docssuite.pdftools

import com.docssuite.pdftools.PdfTestFiles.bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfStampTest {

    private fun keep(name: String, bytes: ByteArray) {
        val dir = System.getProperty("pdfout") ?: return
        File(dir).mkdirs()
        File(dir, name).writeBytes(bytes)
    }

    /** Tout le contenu d'une page, décompressé, en texte. */
    private fun content(file: PdfFile, index: Int): String {
        val streams = when (val contents = file.resolve(file.pages[index].dict["Contents"])) {
            is PdfStream -> listOf(contents)
            is PdfArray -> contents.items.map { file.resolve(it) as PdfStream }
            else -> emptyList()
        }
        return streams.joinToString("\n") { String(PdfFilters.decode(it, file::resolve), Charsets.ISO_8859_1) }
    }

    private val confidential = Watermark("CONFIDENTIEL")

    @Test
    fun `le filigrane est pose sur chaque page`() {
        val stamped = PdfStamp.apply(bytes("plain-bulletin.pdf"), confidential, null)
        keep("stamp-watermark.pdf", stamped)
        val file = PdfFile.parse(stamped)
        assertEquals(2, file.pages.size)
        for (i in 0..1) {
            val text = content(file, i)
            assertTrue(text, text.contains("(CONFIDENTIEL) Tj"))
            assertTrue(text, text.contains("/DocsStampGS gs"))
            val resources = file.resolve(file.pages[i].dict["Resources"]) as PdfDict
            assertTrue((file.resolve(resources["Font"]) as PdfDict)["DocsStampB"] != null)
            assertTrue((file.resolve(resources["ExtGState"]) as PdfDict)["DocsStampGS"] != null)
        }
    }

    @Test
    fun `le contenu d'origine reste intact, sous le tampon`() {
        val original = PdfFile.parse(bytes("plain-bulletin.pdf"))
        val file = PdfFile.parse(PdfStamp.apply(bytes("plain-bulletin.pdf"), confidential, PageNumbering()))
        val before = content(original, 0)
        val after = content(file, 0)
        assertTrue(after.startsWith("q\n"))
        assertTrue(after.contains(before))
        assertTrue(after.indexOf(before) < after.indexOf("CONFIDENTIEL"))
    }

    @Test
    fun `les pages sont numerotees`() {
        val stamped = PdfStamp.apply(bytes("plain-bulletin.pdf"), null, PageNumbering(PageNumberFormat.PAGE_OF))
        keep("stamp-numbers.pdf", stamped)
        val file = PdfFile.parse(stamped)
        assertTrue(content(file, 0).contains("(Page 1 sur 2) Tj"))
        assertTrue(content(file, 1).contains("(Page 2 sur 2) Tj"))
        assertFalse(content(file, 0).contains("DocsStampGS"))
    }

    @Test
    fun `chaque format de numero`() {
        assertEquals("3", PageNumberFormat.PLAIN.text(3, 12))
        assertEquals("3 / 12", PageNumberFormat.SLASH.text(3, 12))
        assertEquals("Page 3 sur 12", PageNumberFormat.PAGE_OF.text(3, 12))
        assertEquals("– 3 –", PageNumberFormat.DASHES.text(3, 12))
    }

    @Test
    fun `la couverture peut rester sans numero, mais compte`() {
        val file = PdfFile.parse(PdfStamp.apply(bytes("alpha-simple.pdf"), null, PageNumbering(PageNumberFormat.SLASH, skipFirst = true)))
        assertFalse(content(file, 0).contains(" / 3) Tj"))
        assertTrue(content(file, 1).contains("(2 / 3) Tj"))
        assertTrue(content(file, 2).contains("(3 / 3) Tj"))
    }

    @Test
    fun `la numerotation peut commencer plus loin`() {
        val file = PdfFile.parse(PdfStamp.apply(bytes("plain-bulletin.pdf"), null, PageNumbering(PageNumberFormat.PAGE_OF, startAt = 5)))
        assertTrue(content(file, 0).contains("(Page 5 sur 6) Tj"))
        assertTrue(content(file, 1).contains("(Page 6 sur 6) Tj"))
    }

    @Test
    fun `une page tournee est tamponnee dans le sens ou elle s'affiche`() {
        val stamped = PdfStamp.apply(bytes("epsilon-rotated.pdf"), confidential, PageNumbering(position = PageNumberPosition.BOTTOM_RIGHT))
        keep("stamp-rotated.pdf", stamped)
        val file = PdfFile.parse(stamped)
        assertEquals(90, PdfTestFiles.rotation(file, 1))
        // Le repère de la page tournée passe par une rotation d'un quart de tour.
        assertTrue(content(file, 1).contains("q 0 1 -1 0 "))
        assertTrue(content(file, 0).contains("q 1 0 0 1 "))
    }

    @Test
    fun `des ressources heritees et partagees ne sont pas modifiees pour les autres pages`() {
        val file = PdfFile.parse(PdfStamp.apply(bytes("zeta-inherited.pdf"), null, PageNumbering()))
        val first = file.resolve(file.pages[0].dict["Resources"]) as PdfDict
        val second = file.resolve(file.pages[1].dict["Resources"]) as PdfDict
        assertNotSame(first, second)
        // Les polices d'origine sont toujours là, à côté des nôtres.
        val fonts = file.resolve(first["Font"]) as PdfDict
        assertTrue(fonts.entries.keys.any { !it.startsWith("DocsStamp") })
        assertTrue(fonts["DocsStampF"] != null)
    }

    @Test
    fun `les accents du filigrane passent`() {
        val file = PdfFile.parse(PdfStamp.apply(bytes("plain-bulletin.pdf"), Watermark("À RELIRE — BROUILLON"), null))
        val raw = content(file, 0).toByteArray(Charsets.ISO_8859_1)
        // « À » en WinAnsi : 0xC0 ; le tiret cadratin : 0x97.
        assertTrue(raw.contains(0xC0.toByte()))
        assertTrue(raw.contains(0x97.toByte()))
    }

    @Test
    fun `un PDF protege reste protege par le meme mot de passe`() {
        val stamped = PdfStamp.apply(bytes("enc-aes-128.pdf"), confidential, null, password = "lune")
        keep("stamp-protected.pdf", stamped)
        assertThrows(PdfEncryptedException::class.java) { PdfFile.parse(stamped) }
        val file = PdfFile.parse(stamped, "lune")
        assertTrue(content(file, 0).contains("(CONFIDENTIEL) Tj"))
    }

    @Test
    fun `sans rien a ajouter c'est refuse`() {
        assertThrows(IllegalArgumentException::class.java) { PdfStamp.apply(bytes("plain-bulletin.pdf"), null, null) }
    }

    @Test
    fun `signets et liens restent`() {
        val file = PdfFile.parse(PdfStamp.apply(bytes("plain-bulletin.pdf"), confidential, PageNumbering()))
        val catalog = file.resolve(file.trailer["Root"]) as PdfDict
        assertTrue(file.resolve(catalog["Outlines"]) is PdfDict)
        assertEquals(1, (file.resolve(file.pages[0].dict["Annots"]) as PdfArray).items.size)
    }

    @Test
    fun `les largeurs helvetica sont celles d'Adobe`() {
        // « Hello » en Helvetica : 722 + 556 + 222 + 222 + 556.
        assertEquals(2278f, Helvetica.width("Hello", bold = false))
        assertEquals(Helvetica.width("E", bold = true), Helvetica.width("É", bold = true))
    }
}
