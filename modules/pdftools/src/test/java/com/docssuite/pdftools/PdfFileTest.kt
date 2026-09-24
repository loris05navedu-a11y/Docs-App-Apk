package com.docssuite.pdftools

import com.docssuite.pdftools.PdfTestFiles.mediaBox
import com.docssuite.pdftools.PdfTestFiles.open
import com.docssuite.pdftools.PdfTestFiles.rotation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFileTest {

    @Test
    fun `une table classique donne toutes les pages`() {
        val file = open("alpha-simple.pdf")
        assertEquals(3, file.pages.size)
        assertFalse(file.repaired)
    }

    @Test
    fun `table compressee et flux d objets sont lus`() {
        val file = open("beta-objstm.pdf")
        assertEquals(4, file.pages.size)
        assertFalse(file.repaired)
        assertTrue(PdfTestFiles.content(file, 3).isNotEmpty())
    }

    @Test
    fun `la page ajoutee par une mise a jour incrementale est trouvee`() {
        val file = open("gamma-incremental.pdf")
        assertEquals(3, file.pages.size)
        assertFalse(file.repaired)
    }

    @Test
    fun `taille et rotation se transmettent des noeuds parents`() {
        val file = open("zeta-inherited.pdf")
        assertEquals(3, file.pages.size)
        assertEquals(listOf(0.0, 0.0, 200.0, 200.0), mediaBox(file, 0))
        assertEquals(90, rotation(file, 0))
        assertEquals(0, rotation(file, 1)) // sa propre valeur l'emporte
        assertEquals(listOf(0.0, 0.0, 300.0, 400.0), mediaBox(file, 2))
        assertEquals(90, rotation(file, 2))
        assertTrue(file.pages[0].inherited.containsKey("Resources"))
    }

    @Test
    fun `une table des references faussee est reconstruite`() {
        val broken = open("alpha-broken-xref.pdf")
        val sound = open("alpha-simple.pdf")
        assertTrue(broken.repaired)
        assertEquals(3, broken.pages.size)
        for (i in 0 until 3) assertArrayEquals(PdfTestFiles.content(sound, i), PdfTestFiles.content(broken, i))
    }

    @Test
    fun `un pdf protege par mot de passe est refuse clairement`() {
        assertThrows(PdfEncryptedException::class.java) { open("secret-encrypted.pdf") }
    }

    @Test
    fun `un fichier qui n est pas un pdf est refuse`() {
        assertThrows(PdfFormatException::class.java) { PdfFile.parse("Bonjour".toByteArray()) }
    }

    @Test
    fun `les objets se lisent avec leurs formes particulieres`() {
        val source = "<</Type/Page/Kids[3 0 R 4 0 R]/N 12 0/S (a (b) \\) c)/H <4142>/Z -.5 %commentaire\n/F false>>"
        val dict = PdfParser(source.toByteArray(Charsets.ISO_8859_1)).readObject() as PdfDict
        assertEquals("Page", dict.nameOf("Type"))
        assertEquals(listOf(PdfRef(3, 0), PdfRef(4, 0)), (dict["Kids"] as PdfArray).items)
        assertEquals(PdfNumber("12"), dict["N"]) // « 12 0 » sans R : deux nombres, pas une référence
        assertEquals("(a (b) \\) c)", String((dict["S"] as PdfString).raw, Charsets.ISO_8859_1))
        assertEquals("<4142>", String((dict["H"] as PdfString).raw, Charsets.ISO_8859_1))
        assertEquals(-0.5, (dict["Z"] as PdfNumber).doubleValue, 0.0)
        assertEquals(PdfBool(false), dict["F"])
    }

    @Test
    fun `un objet ecrit puis relu est identique`() {
        val source = "<</A [1 2.5 /N (x\\)y) <00FF> true null 7 0 R] /B <</C /D>>>>"
        val first = PdfParser(source.toByteArray(Charsets.ISO_8859_1)).readObject()
        val written = java.io.ByteArrayOutputStream().apply { writePdf(first) }.toByteArray()
        val second = PdfParser(written).readObject()
        val rewritten = java.io.ByteArrayOutputStream().apply { writePdf(second) }.toByteArray()
        assertArrayEquals(written, rewritten)
    }

    @Test
    fun `le predicteur png up est defait`() {
        // Deux rangées de 3 octets, filtre « Up » (2) sur la seconde.
        val encoded = byteArrayOf(0, 1, 2, 3, 2, 1, 1, 1)
        val params = PdfDict().apply {
            set("Predictor", PdfNumber("12"))
            set("Columns", PdfNumber("3"))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 2, 3, 4), PdfFilters.unpredict(encoded, params))
    }
}
