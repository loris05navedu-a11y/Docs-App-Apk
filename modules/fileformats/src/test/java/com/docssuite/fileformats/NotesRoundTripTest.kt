package com.docssuite.fileformats

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Les notes du présentateur doivent survivre à l'écriture puis à la relecture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesRoundTripTest {

    @Test
    fun `les notes traversent un aller-retour pptx`() {
        val deck = Deck(
            title = "Réunion",
            slides = listOf(
                SlideModel(title = "Ouverture", content = "Bonjour", notes = "Parler lentement"),
                SlideModel(title = "Chiffres", content = "2024", notes = "Insister sur\nla marge"),
                SlideModel(title = "Fin", content = "Merci")
            )
        )

        val restored = Pptx.read(Pptx.write(deck), "Réunion")

        assertEquals(3, restored.slides.size)
        assertEquals("Parler lentement", restored.slides[0].notes)
        assertEquals("Insister sur\nla marge", restored.slides[1].notes)
        assertEquals("", restored.slides[2].notes)
    }

    @Test
    fun `une presentation sans notes reste lisible`() {
        val deck = Deck(slides = listOf(SlideModel(title = "Seule", content = "Rien")))
        val restored = Pptx.read(Pptx.write(deck), "Titre")
        assertEquals(1, restored.slides.size)
        assertEquals("", restored.slides[0].notes)
    }

    @Test
    fun `les caracteres speciaux des notes sont echappes`() {
        val deck = Deck(slides = listOf(SlideModel(title = "T", notes = "A < B & C > D")))
        val restored = Pptx.read(Pptx.write(deck), "Titre")
        assertEquals("A < B & C > D", restored.slides[0].notes)
    }
}
