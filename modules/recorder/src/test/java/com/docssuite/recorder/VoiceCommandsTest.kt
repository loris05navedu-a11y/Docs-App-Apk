package com.docssuite.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun `la ponctuation dictee devient de la ponctuation`() {
        assertEquals(
            "Bonjour, je vous écris au sujet de la réunion.",
            VoiceCommands.apply("bonjour virgule je vous écris au sujet de la réunion point")
        )
    }

    @Test
    fun `point au milieu d une phrase reste un mot`() {
        assertEquals("Mon point de vue est clair.", VoiceCommands.apply("mon point de vue est clair point"))
    }

    @Test
    fun `questions et deux points suivent la typographie francaise`() {
        assertEquals("Tu viens ? Voici la liste : pain, lait.", VoiceCommands.apply("tu viens point d'interrogation voici la liste deux points pain virgule lait point"))
    }

    @Test
    fun `retours a la ligne et paragraphes remettent une majuscule`() {
        assertEquals(
            "Première ligne.\nSeconde ligne\n\nSuite du texte",
            VoiceCommands.apply("première ligne point à la ligne seconde ligne nouveau paragraphe suite du texte")
        )
    }

    @Test
    fun `guillemets a la francaise`() {
        assertEquals("Il a dit « oui ».", VoiceCommands.apply("il a dit ouvrez les guillemets oui fermez les guillemets point"))
    }

    @Test
    fun `une commande dans un mot n est pas remplacee`() {
        assertEquals("Des virgules partout", VoiceCommands.apply("des virgules partout"))
    }

    @Test
    fun `les dictees successives se suivent avec la bonne espace et la bonne casse`() {
        var text = VoiceCommands.append("", "bonjour à tous virgule")
        assertEquals("Bonjour à tous,", text)
        text = VoiceCommands.append(text, "je commence la séance point")
        assertEquals("Bonjour à tous, je commence la séance.", text)
        text = VoiceCommands.append(text, "premier point à l'ordre du jour")
        assertEquals("Bonjour à tous, je commence la séance. Premier point à l'ordre du jour", text)
        text = VoiceCommands.append(text, "Paris et Lyon point")
        assertEquals("Bonjour à tous, je commence la séance. Premier point à l'ordre du jour Paris et Lyon.", text)
        text = VoiceCommands.append(text, "à la ligne suite")
        assertEquals("Bonjour à tous, je commence la séance. Premier point à l'ordre du jour Paris et Lyon.\nSuite", text)
    }
}
