package com.docssuite.presentation

import com.docssuite.fileformats.Odf
import com.docssuite.fileformats.Pptx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeckBridgeTest {

    private val slides = listOf(
        Slide(
            title = "Bilan annuel",
            content = "Chiffre d'affaires en hausse\nMarge stable",
            background = 0xFF0B4F6CL,
            textColor = 0xFFE0F7FAL,
            titleSize = 40,
            contentSize = 22,
            align = 1,
            fontIndex = 2,
            transition = 3
        ),
        Slide(title = "Perspectives", content = "Trois chantiers pour 2026")
    )

    @Test
    fun `un aller-retour par un pptx conserve textes et couleurs`() {
        val restored = decodeDeckModel(Pptx.read(Pptx.write(buildDeck("Bilan", slides))))

        assertEquals(2, restored.size)
        assertEquals("Bilan annuel", restored[0].title)
        assertEquals("Chiffre d'affaires en hausse\nMarge stable", restored[0].content)
        assertEquals(0xFF0B4F6CL, restored[0].background)
        assertEquals(40, restored[0].titleSize)
        assertEquals(22, restored[0].contentSize)
        assertEquals("Perspectives", restored[1].title)
    }

    @Test
    fun `un aller-retour par un odp conserve les textes`() {
        val restored = decodeDeckModel(Odf.readDeck(Odf.writeDeck(buildDeck("Bilan", slides))))
        assertEquals(2, restored.size)
        assertEquals("Bilan annuel", restored[0].title)
        assertTrue(restored[0].content.contains("Marge stable"))
    }

    @Test
    fun `les diapositives gardent leur ordre au-dela de neuf`() {
        val many = (1..15).map { Slide(title = "Diapo $it", content = "Contenu $it") }
        val restored = decodeDeckModel(Pptx.read(Pptx.write(buildDeck("Long", many))))
        assertEquals((1..15).map { "Diapo $it" }, restored.map { it.title })
    }

    @Test
    fun `une transition inconnue du format retombe sur le fondu`() {
        val restored = decodeDeckModel(Pptx.read(Pptx.write(buildDeck("Bilan", slides))))
        assertTrue(restored.all { it.transition == 1 })
    }

    @Test
    fun `les valeurs hors bornes sont ramenees dans les limites de l editeur`() {
        val extreme = listOf(Slide(title = "T", titleSize = 500, contentSize = 0, align = 9))
        val restored = decodeDeckModel(buildDeck("X", extreme))
        assertTrue(restored[0].titleSize in 10..96)
        assertTrue(restored[0].contentSize in 8..72)
        assertTrue(restored[0].align in 0..2)
    }
}
