package com.docssuite.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {

    private fun split(text: String) = Sentences.split(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `phrases simples et ponctuation forte`() {
        assertEquals(
            listOf("Bonjour à tous.", "Comment allez-vous ?", "Très bien !", "Alors… on commence."),
            split("Bonjour à tous. Comment allez-vous ? Très bien ! Alors… on commence.")
        )
    }

    @Test
    fun `les abreviations et initiales ne coupent pas`() {
        assertEquals(
            listOf("M. Dupont et Mme Martin ont vu le Dr. Leroy p. 12.", "J. K. Rowling écrit.", "Voir cf. le chap. 3 pour la suite."),
            split("M. Dupont et Mme Martin ont vu le Dr. Leroy p. 12. J. K. Rowling écrit. Voir cf. le chap. 3 pour la suite.")
        )
    }

    @Test
    fun `nombres decimaux, sites et guillemets`() {
        assertEquals(
            listOf("Le prix est de 3.50 euros sur www.exemple.fr aujourd'hui.", "« Vraiment ? »", "Il a dit « oui. »", "Fin."),
            split("Le prix est de 3.50 euros sur www.exemple.fr aujourd'hui. « Vraiment ? » Il a dit « oui. » Fin.")
        )
    }

    @Test
    fun `etc termine la phrase sauf si la suite est en minuscule`() {
        assertEquals(listOf("Pommes, poires, etc.", "Ensuite on part."), split("Pommes, poires, etc. Ensuite on part."))
        assertEquals(listOf("Pommes, poires, etc. et encore plus."), split("Pommes, poires, etc. et encore plus."))
    }

    @Test
    fun `un retour a la ligne separe toujours, les lignes vides sont ignorees`() {
        val text = "Titre du cours\n\n  Première partie : les bases\n- un point sans point final\n---\nDernière phrase."
        assertEquals(
            listOf("Titre du cours", "Première partie : les bases", "- un point sans point final", "Dernière phrase."),
            split(text)
        )
    }

    @Test
    fun `les plages correspondent exactement au texte`() {
        val text = "  Un.   Deux !\nTrois ?  "
        Sentences.split(text).forEach { assertTrue(text[it.first].isLetter() && !text[it.last].isWhitespace()) }
        assertEquals(3, Sentences.split(text).size)
    }

    @Test
    fun `une tres longue phrase est coupee aux virgules`() {
        val long = (1..120).joinToString(", ") { "élément numéro $it" } + "."
        val parts = split(long)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= Sentences.MAX_LENGTH })
        assertTrue(parts.dropLast(1).all { it.endsWith(",") })
        assertEquals(long.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }
}
