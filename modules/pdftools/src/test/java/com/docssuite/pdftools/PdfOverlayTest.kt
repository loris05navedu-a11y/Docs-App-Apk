package com.docssuite.pdftools

import com.docssuite.pdftools.PdfTestFiles.open
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfOverlayTest {

    private fun keep(name: String, bytes: ByteArray): PdfFile {
        File("build/pdftools-out").apply { mkdirs() }.resolve(name).writeBytes(bytes)
        return PdfFile.parse(bytes).also { assertFalse(it.repaired) }
    }

    /** Une « signature » : un trait bleu épais sur fond transparent. */
    private fun signature(w: Int = 120, h: Int = 40) = OverlayItem.Image(
        u = 0.55f, v = 0.8f, width = 0.35f, height = 0.1f,
        pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val curve = (h / 2 + (h / 3) * kotlin.math.sin(x / 12.0)).toInt()
            if (kotlin.math.abs(y - curve) <= 3) 0xFF1E3A8A.toInt() else 0x00FFFFFF
        },
        pixelWidth = w, pixelHeight = h
    )

    @Test
    fun `les coins de la page affichee tombent aux bons coins du pdf pour chaque rotation`() {
        fun g(r: Int) = PageGeometry(0f, 0f, 600f, 800f, r)
        assertEquals(0f to 800f, g(0).toPdf(0f, 0f))       // haut-gauche affiché = haut-gauche du PDF
        assertEquals(600f to 0f, g(0).toPdf(1f, 1f))
        assertEquals(0f to 0f, g(90).toPdf(0f, 0f))         // tournée d'un quart : le bas-gauche monte en haut-gauche
        assertEquals(800f, g(90).displayWidth)
        assertEquals(600f to 0f, g(180).toPdf(0f, 0f))      // à l'envers : le bas-droit passe en haut-gauche
        assertEquals(600f to 800f, g(270).toPdf(0f, 0f))    // trois quarts : le haut-droit passe en haut-gauche
        assertEquals(0f to 800f, g(270).toPdf(0f, 1f))
    }

    @Test
    fun `signer une page ajoute texte coche et signature sans toucher au contenu d origine`() {
        val alpha = open("alpha-simple.pdf")
        val items = listOf(
            OverlayItem.Text(0.1f, 0.78f, "Lu et approuvé (bon pour accord)\nLéa Dupont — 24/09/2026 — 12 €", 0.025f),
            OverlayItem.Check(0.1f, 0.7f, 0.04f),
            signature()
        )
        val out = keep(
            "signe-alpha.pdf",
            PdfAssembler.assemble(alpha.pages.indices.map { PageSelection(alpha, it, overlay = if (it == 0) items else emptyList()) })
        )
        assertEquals(3, out.pages.size)
        val contents = out.resolve(out.pages[0].dict["Contents"]) as PdfArray
        val originalStreams = (alpha.resolve(alpha.pages[0].dict["Contents"]) as? PdfArray)?.items?.size ?: 1
        assertEquals(originalStreams + 2, contents.items.size) // q · contenu d'origine · Q + ajouts
        assertEquals("q\n", String((out.resolve(contents.items.first()) as PdfStream).data, Charsets.ISO_8859_1))
        assertTrue(PdfTestFiles.content(out, 0).let { String(it, Charsets.ISO_8859_1) }.contains(String(PdfTestFiles.content(alpha, 0), Charsets.ISO_8859_1)))
        val resources = out.resolve(out.pages[0].dict["Resources"]) as PdfDict
        assertNotNull((out.resolve(resources["Font"]) as PdfDict)["DocsHelv"])
        val image = out.resolve((out.resolve(resources["XObject"]) as PdfDict)["DocsIm1"]) as PdfStream
        assertNotNull(out.resolve(image.dict["SMask"]) as? PdfStream)
        // Les autres pages restent telles quelles.
        org.junit.Assert.assertArrayEquals(PdfTestFiles.content(alpha, 1), PdfTestFiles.content(out, 1))
    }

    @Test
    fun `une page tournee recoit la signature droite a l ecran`() {
        val epsilon = open("epsilon-rotated.pdf") // page 2 : tournée de 90°
        keep("signe-tournee.pdf", PdfAssembler.assemble(listOf(PageSelection(epsilon, 1, overlay = listOf(
            OverlayItem.Text(0.1f, 0.1f, "En haut à gauche", 0.03f), signature()
        )))))
    }

    @Test
    fun `des ressources heritees partagees ne sont pas modifiees pour les autres pages`() {
        val zeta = open("zeta-inherited.pdf")
        val out = keep("signe-herite.pdf", PdfAssembler.assemble(listOf(
            PageSelection(zeta, 0, overlay = listOf(OverlayItem.Text(0.1f, 0.1f, "Signé", 0.05f))),
            PageSelection(zeta, 1)
        )))
        val second = out.resolve(out.pages[1].dict["Resources"]) as PdfDict
        val fonts = out.resolve(second["Font"]) as PdfDict
        assertTrue("la page 2 garde ses seules polices", fonts["DocsHelv"] == null && fonts["F1"] != null)
    }

    @Test
    fun `accents et symboles passent en winansi`() {
        val bytes = OverlayWriter.encode("éàçœ€ — ok ✓")
        assertEquals(0xE9, bytes[0].toInt() and 0xFF)
        assertEquals(0x9C, bytes[3].toInt() and 0xFF) // œ
        assertEquals(0x80, bytes[4].toInt() and 0xFF) // €
        assertEquals('?'.code, bytes.last().toInt())   // ✓ hors WinAnsi
    }
}
