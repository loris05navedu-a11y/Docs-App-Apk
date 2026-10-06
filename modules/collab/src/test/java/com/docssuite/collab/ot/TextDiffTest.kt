package com.docssuite.collab.ot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextDiffTest {

    @Test
    fun `une lettre tapee devient une insertion a sa place`() {
        assertEquals(TextOperation().retain(5).insert("!").retain(6), TextDiff.between("Salut monde", "Salut! monde", caret = 6))
    }

    @Test
    fun `une lettre effacee devient une suppression a sa place`() {
        assertEquals(TextOperation().retain(4).delete(1).retain(6), TextDiff.between("Salut monde", "Salu monde", caret = 4))
    }

    @Test
    fun `le curseur dit ou une lettre repetee a ete tapee`() {
        // Un « a » tapé entre les deux « a » de « aa ».
        assertEquals(TextOperation().retain(1).insert("a").retain(1), TextDiff.between("aa", "aaa", caret = 2))
        // … et tapé tout au début.
        assertEquals(TextOperation().insert("a").retain(2), TextDiff.between("aa", "aaa", caret = 1))
        // Effacé au milieu de « aaa ».
        assertEquals(TextOperation().retain(1).delete(1).retain(1), TextDiff.between("aaa", "aa", caret = 1))
    }

    @Test
    fun `remplacer une selection`() {
        assertEquals(TextOperation().retain(1).insert("x").delete(1).retain(1), TextDiff.between("abc", "axc", caret = 2))
    }

    @Test
    fun `rien de change, rien a envoyer`() {
        assertTrue(TextDiff.between("pareil", "pareil").isNoop)
    }

    @Test
    fun `un emoji n'est jamais coupe en deux`() {
        // 😀 et 😁 partagent leur première moitié UTF-16.
        val op = TextDiff.between("a😀b", "a😁b", caret = 3)
        assertEquals(TextOperation().retain(1).insert("😁").delete(2).retain(1), op)
        // Ajouter un emoji juste avant un autre qui commence pareil.
        val before = "x😀"
        val after = "x😁😀"
        val added = TextDiff.between(before, after, caret = 3)
        assertEquals(after, added.apply(before))
        added.toJson().filterIsInstance<String>().forEach { inserted ->
            assertTrue(!Character.isLowSurrogate(inserted.first()) && !Character.isHighSurrogate(inserted.last()))
        }
    }

    @Test
    fun `l'operation transforme toujours exactement l'ancien texte en le nouveau`() {
        val random = Random(4)
        val alphabet = "aab é😀\n"
        repeat(5000) {
            fun text() = buildString { repeat(random.nextInt(12)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val before = text()
            val after = text()
            val caret = random.nextInt(after.length + 1)
            assertEquals(after, TextDiff.between(before, after, caret).apply(before))
        }
    }
}
