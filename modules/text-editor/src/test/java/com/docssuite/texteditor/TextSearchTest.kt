package com.docssuite.texteditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSearchTest {

    private val text = "Le chat dort. Le CHAT mange. Le chien aboie."

    private fun styles(source: String, bold: IntRange? = null): List<CharStyle> =
        source.indices.map { CharStyle(bold = bold != null && it in bold) }

    @Test
    fun `la recherche avance puis repart du debut`() {
        assertEquals(3 until 7, TextSearch.findNext(text, "chat", 0, matchCase = true))
        // La seconde occurrence est en majuscules : ignorée si la casse compte.
        assertEquals(3 until 7, TextSearch.findNext(text, "chat", 8, matchCase = true))
        assertEquals(17 until 21, TextSearch.findNext(text, "chat", 8, matchCase = false))
    }

    @Test
    fun `la recherche arriere remonte puis repart de la fin`() {
        assertEquals(3 until 7, TextSearch.findPrevious(text, "chat", 17, matchCase = false))
        assertEquals(17 until 21, TextSearch.findPrevious(text, "chat", 0, matchCase = false))
    }

    @Test
    fun `un motif absent ne trouve rien`() {
        assertNull(TextSearch.findNext(text, "girafe", 0, matchCase = false))
        assertNull(TextSearch.findNext(text, "", 0, matchCase = false))
    }

    @Test
    fun `le comptage distingue la casse`() {
        assertEquals(1, TextSearch.count(text, "chat", matchCase = true))
        assertEquals(2, TextSearch.count(text, "chat", matchCase = false))
    }

    @Test
    fun `tout remplacer traite chaque occurrence`() {
        val result = TextSearch.replaceAll(text, styles(text), "Le", "Un", matchCase = true)
        assertEquals(3, result.count)
        assertEquals("Un chat dort. Un CHAT mange. Un chien aboie.", result.text)
        assertEquals(result.text.length, result.styles.size)
    }

    @Test
    fun `un remplacement plus long conserve les styles autour`() {
        val source = "abcdef"
        // « cd » est en gras.
        val initial = styles(source, bold = 2..3)
        val result = TextSearch.replaceAll(source, initial, "cd", "XYZ", matchCase = true)

        assertEquals("abXYZef", result.text)
        assertEquals(result.text.length, result.styles.size)
        // Le texte inséré reprend le gras de ce qu'il remplace…
        assertEquals(listOf(true, true, true), result.styles.subList(2, 5).map { it.bold })
        // …et ce qui l'entoure reste inchangé.
        assertEquals(listOf(false, false), result.styles.subList(0, 2).map { it.bold })
        assertEquals(listOf(false, false), result.styles.subList(5, 7).map { it.bold })
    }

    @Test
    fun `un remplacement plus court raccourcit les styles d autant`() {
        val source = "abcdef"
        val result = TextSearch.replaceAll(source, styles(source, bold = 2..3), "cd", "Z", matchCase = true)
        assertEquals("abZef", result.text)
        assertEquals(5, result.styles.size)
        assertEquals(true, result.styles[2].bold)
        assertEquals(false, result.styles[3].bold)
    }

    @Test
    fun `remplacer une occurrence precise ne touche pas aux autres`() {
        val source = "chat chat"
        val result = TextSearch.replaceRange(source, styles(source), 0 until 4, "chien")
        assertEquals("chien chat", result.text)
        assertEquals(1, result.count)
        assertEquals(5, result.caret)
        assertEquals(result.text.length, result.styles.size)
    }

    @Test
    fun `un motif introuvable laisse le document intact`() {
        val result = TextSearch.replaceAll(text, styles(text), "girafe", "zèbre", matchCase = false)
        assertEquals(0, result.count)
        assertEquals(text, result.text)
    }

    @Test
    fun `une liste de styles plus courte que le texte est completee`() {
        val source = "abcdef"
        val result = TextSearch.replaceAll(source, listOf(CharStyle(bold = true)), "cd", "Z", matchCase = true)
        assertEquals("abZef", result.text)
        assertEquals(5, result.styles.size)
    }
}
