package com.docssuite.reader

import com.docssuite.fileformats.Deck
import com.docssuite.fileformats.FileFormat
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.SlideModel
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadableTextTest {

    private val document = TextDocument(
        "Leçon",
        listOf(
            TextParagraph(listOf(TextRun("La Révolution française", bold = true))),
            TextParagraph(listOf(TextRun("Elle commence en 1789. "), TextRun("Le roi est renversé en 1792."))),
        )
    )

    @Test
    fun `un fichier word se lit comme le document`() {
        val text = ReadableText.fromFile("lecon.docx", FileFormats.exportText(FileFormat.DOCX, document))
        assertEquals("La Révolution française\nElle commence en 1789. Le roi est renversé en 1792.", text)
    }

    @Test
    fun `un pdf avec du texte se lit`() {
        // Un PDF minimal comme en produit un traitement de texte : deux lignes de texte.
        val content = "BT /F1 12 Tf 72 720 Td (La R\\351volution fran\\347aise) Tj 0 -16 Td (Le roi est renvers\\351 en 1792.) Tj ET"
        val pdf = """%PDF-1.4
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >> endobj
4 0 obj << /Length ${content.length} >>
stream
$content
endstream
endobj
trailer << /Root 1 0 R >>
%%EOF
"""
        val text = ReadableText.fromFile("lecon.pdf", pdf.toByteArray(Charsets.ISO_8859_1))
        assertTrue(text, text.contains("Révolution française"))
        assertTrue(text, text.contains("renversé en 1792."))
    }

    @Test
    fun `un fichier texte windows et un tableur csv`() {
        val txt = ReadableText.fromFile("notes.txt", "Ligne un.\r\n\r\n\r\n\r\nLigne   deux.".toByteArray(Charsets.UTF_8))
        assertEquals("Ligne un.\n\nLigne deux.", txt)
        val csv = ReadableText.fromFile("courses.csv", "Article;Prix\nPain;1,20\nLait;0,95".toByteArray(Charsets.UTF_8))
        assertTrue(csv, csv.contains("Pain, 1,20"))
        assertTrue(csv, csv.indexOf("Article") < csv.indexOf("Lait"))
    }

    @Test
    fun `une presentation se lit diapositive par diapositive`() {
        val deck = Deck("Exposé", listOf(SlideModel("Introduction", "Pourquoi ce sujet ?"), SlideModel("Conclusion", "Merci.")))
        val bytes = FileFormats.exportDeck(FileFormat.PPTX, deck)
        assertEquals("Introduction\nPourquoi ce sujet ?\n\nConclusion\nMerci.", ReadableText.fromFile("expose.pptx", bytes))
    }

    @Test
    fun `les cesures des pdf sont recollees`() {
        assertEquals("Une phrase coupée en fin de ligne.", ReadableText.clean("Une phrase cou-\npée en fin de ligne."))
        assertEquals("Jean-\nPierre", ReadableText.clean("Jean-\nPierre"))
    }
}
