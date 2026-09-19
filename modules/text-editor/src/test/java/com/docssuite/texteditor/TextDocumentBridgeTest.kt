package com.docssuite.texteditor

import androidx.compose.ui.text.style.TextAlign
import com.docssuite.fileformats.Docx
import com.docssuite.fileformats.Odf
import com.docssuite.fileformats.Rtf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vérifie le trajet complet : état de l'éditeur → fichier Word → état de
 * l'éditeur. C'est ce chemin-là que l'utilisateur emprunte en exportant puis
 * en réimportant son document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextDocumentBridgeTest {

    private val text = "Titre du rapport\nUn paragraphe normal.\nDernière ligne"

    /** Un style par ligne, dérivé du texte pour que les longueurs suivent. */
    private val styles: List<CharStyle> = buildList {
        val perLine = listOf(
            CharStyle(bold = true, size = 24, color = 0xFF1D4ED8L, font = 2),
            CharStyle(italic = true, size = 14),
            CharStyle(underline = true, strike = true, highlight = 0xFFFFFF00L)
        )
        text.split('\n').forEachIndexed { line, content ->
            if (line > 0) add(CharStyle())
            repeat(content.length) { add(perLine[line]) }
        }
    }

    @Test
    fun `un aller-retour par un docx conserve texte et mise en forme`() {
        val document = buildTextDocument("Rapport", text, styles, TextAlign.Center)
        val restored = decodeTextDocument(Docx.read(Docx.write(document)))

        assertEquals(text, restored.text)
        assertEquals(TextAlign.Center, restored.align)
        assertEquals(text.length, restored.styles.size)

        assertTrue(restored.styles[0].bold)
        assertEquals(24, restored.styles[0].size)
        assertEquals(0xFF1D4ED8L, restored.styles[0].color)
        assertEquals("Serif", com.docssuite.core.fontLabelAt(restored.styles[0].font))

        assertTrue(restored.styles[20].italic)
        assertEquals(14, restored.styles[20].size)

        val lastIndex = text.length - 1
        assertTrue(restored.styles[lastIndex].underline)
        assertTrue(restored.styles[lastIndex].strike)
        assertEquals(0xFFFFFF00L, restored.styles[lastIndex].highlight)
    }

    @Test
    fun `les paragraphes suivent les retours a la ligne`() {
        val document = buildTextDocument("Rapport", text, styles, TextAlign.Start)
        assertEquals(3, document.paragraphs.size)
        assertEquals("Titre du rapport", document.paragraphs[0].plainText)
        assertEquals("Dernière ligne", document.paragraphs[2].plainText)
    }

    @Test
    fun `un document vide ne perd pas la main`() {
        val document = buildTextDocument("Vide", "", emptyList(), TextAlign.Start)
        val restored = decodeTextDocument(document)
        assertEquals("", restored.text)
        assertTrue(restored.styles.isEmpty())
    }

    @Test
    fun `les autres formats texte gardent au moins le contenu`() {
        val document = buildTextDocument("Rapport", text, styles, TextAlign.Start)
        assertEquals(text, decodeTextDocument(Odf.readText(Odf.writeText(document))).text)
        assertEquals(text, decodeTextDocument(Rtf.read(Rtf.write(document))).text)
    }

    @Test
    fun `un style se traduit dans les deux sens sans deriver`() {
        val document = buildTextDocument("Rapport", text, styles, TextAlign.End)
        val once = decodeTextDocument(Docx.read(Docx.write(document)))
        val twice = decodeTextDocument(
            Docx.read(Docx.write(buildTextDocument("Rapport", once.text, once.styles, once.align)))
        )
        assertEquals(once.text, twice.text)
        assertEquals(once.styles, twice.styles)
        assertEquals(once.align, twice.align)
    }
}
