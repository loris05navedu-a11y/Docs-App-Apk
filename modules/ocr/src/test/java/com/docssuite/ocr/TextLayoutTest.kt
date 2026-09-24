package com.docssuite.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class TextLayoutTest {

    /** Une ligne de [width] pixels commençant en [left], à la hauteur [row]. */
    private fun line(text: String, row: Int, left: Int = 100, width: Int = 800) =
        OcrLine(text, Box(left, row * 40, left + width, row * 40 + 30))

    private fun block(vararg lines: OcrLine): OcrBlock = OcrBlock(
        lines.toList(),
        Box(lines.minOf { it.box.left }, lines.minOf { it.box.top }, lines.maxOf { it.box.right }, lines.maxOf { it.box.bottom })
    )

    @Test
    fun `un paragraphe coupe par la largeur de la page redevient une seule ligne`() {
        val b = block(
            line("La réunion du conseil municipal", 0),
            line("aura lieu mardi prochain à la", 1),
            line("salle des fêtes.", 2, width = 300)
        )
        assertEquals("La réunion du conseil municipal aura lieu mardi prochain à la salle des fêtes.", TextLayout.blockText(b))
    }

    @Test
    fun `un mot coupe en fin de ligne est recolle`() {
        val b = block(line("Merci de nous faire parvenir un exem-", 0), line("plaire signé.", 1, width = 300))
        assertEquals("Merci de nous faire parvenir un exemplaire signé.", TextLayout.blockText(b))
    }

    @Test
    fun `un vrai trait d union devant une majuscule est garde`() {
        val b = block(line("Rendez-vous à Aix-", 0), line("En-Provence demain", 1))
        assertEquals("Rendez-vous à Aix- En-Provence demain", TextLayout.blockText(b))
    }

    @Test
    fun `listes et lignes courtes gardent leurs retours a la ligne`() {
        val b = block(
            line("Liste des courses :", 0, width = 400),
            line("• 2 kg de farine", 1, width = 300),
            line("• du beurre", 2, width = 250),
            line("Jean Dupont", 3, width = 200),
            line("12 rue des Lilas", 4, width = 250)
        )
        assertEquals("Liste des courses :\n• 2 kg de farine\n• du beurre\nJean Dupont\n12 rue des Lilas", TextLayout.blockText(b))
    }

    @Test
    fun `deux colonnes se lisent l une apres l autre sous leur titre`() {
        val title = block(line("GRAND TITRE", 0, left = 100, width = 1000))
        // Colonnes décalées en hauteur, comme dans un vrai journal.
        val left1 = block(line("Colonne gauche un", 2, left = 100, width = 450), line("suite gauche un", 3, left = 100, width = 450))
        val right1 = block(line("Colonne droite un", 3, left = 650, width = 450), line("suite droite un", 4, left = 650, width = 450))
        val left2 = block(line("Colonne gauche deux", 5, left = 100, width = 450))
        val right2 = block(line("Colonne droite deux", 6, left = 650, width = 450))
        val shuffled = listOf(right2, left1, title, right1, left2)
        assertEquals(
            listOf(title, left1, left2, right1, right2),
            TextLayout.readingOrder(shuffled)
        )
    }

    @Test
    fun `un titre et un pied de page encadrent les deux colonnes`() {
        val title = block(line("TITRE", 0, left = 100, width = 1000))
        val left = block(line("Gauche un", 2, left = 100, width = 450), line("gauche deux", 3, left = 100, width = 450))
        val right = block(line("Droite un", 2, left = 650, width = 450), line("droite deux", 3, left = 650, width = 450))
        val footer = block(line("Page 1 sur 3", 6, left = 100, width = 1000))
        assertEquals(listOf(title, left, right, footer), TextLayout.readingOrder(listOf(footer, right, title, left)))
    }

    @Test
    fun `un formulaire se lit ligne par ligne et non colonne par colonne`() {
        val nomLabel = block(line("Nom :", 0, left = 100, width = 150))
        val nom = block(line("Dupont", 0, left = 400, width = 200))
        val prenomLabel = block(line("Prénom :", 2, left = 100, width = 180))
        val prenom = block(line("Jean", 2, left = 400, width = 120))
        assertEquals(
            listOf(nomLabel, nom, prenomLabel, prenom),
            TextLayout.readingOrder(listOf(prenom, nomLabel, prenomLabel, nom))
        )
    }

    @Test
    fun `des paragraphes successifs restent dans l ordre du haut vers le bas`() {
        val p1 = block(line("Premier", 0))
        val p2 = block(line("Deuxième", 2))
        val p3 = block(line("Troisième", 4))
        assertEquals(listOf(p1, p2, p3), TextLayout.readingOrder(listOf(p3, p1, p2)))
    }

    @Test
    fun `les pages sont separees par une ligne vide et les pages vides ignorees`() {
        val page1 = OcrPage(listOf(block(line("Page un.", 0))), 1000, 1400)
        val empty = OcrPage(emptyList(), 1000, 1400)
        val page2 = OcrPage(listOf(block(line("Page deux.", 0))), 1000, 1400)
        assertEquals("Page un.\n\nPage deux.", TextLayout.documentText(listOf(page1, empty, page2)))
    }
}
