package com.docssuite.collab.ot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextOperationTest {

    // ------------------------------------------------------------ construction

    @Test
    fun `garder, inserer, supprimer`() {
        val op = TextOperation().retain(5).insert(" à tous").retain(1).delete(3)
        assertEquals(9, op.baseLength)
        assertEquals(13, op.targetLength)
        assertEquals("Salut à tous!", op.apply("Salut!xyz"))
    }

    @Test
    fun `la forme est canonique, insertion avant suppression`() {
        val a = TextOperation().retain(2).delete(3).insert("ab")
        val b = TextOperation().retain(2).insert("ab").delete(3)
        assertEquals(a, b)
        assertEquals("[retain(2), insert(\"ab\"), delete(3)]", a.toString())
        assertEquals(TextOperation().retain(3), TextOperation().retain(1).retain(2))
        assertEquals(TextOperation().insert("abc"), TextOperation().insert("a").insert("bc"))
    }

    @Test
    fun `ne rien changer se reconnait`() {
        assertTrue(TextOperation().isNoop)
        assertTrue(TextOperation.identity(10).isNoop)
        assertFalse(TextOperation().retain(3).insert("x").isNoop)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `appliquer a un texte de mauvaise longueur est refuse`() {
        TextOperation().retain(3).apply("ab")
    }

    // ------------------------------------------------------------ enregistrement

    @Test
    fun `aller-retour par l'enregistrement`() {
        val op = TextOperation().retain(4).insert("é😀").delete(2).retain(1)
        assertEquals(listOf(4, "é😀", -2, 1), op.toJson())
        // Firebase relit les nombres en Long.
        assertEquals(op, TextOperation.fromJson(listOf(4L, "é😀", -2L, 1L)))
    }

    @Test
    fun `une donnee abimee donne null, pas un plantage`() {
        assertNull(TextOperation.fromJson(null))
        assertNull(TextOperation.fromJson(listOf(0L)))
        assertNull(TextOperation.fromJson(listOf("")))
        assertNull(TextOperation.fromJson(listOf(true)))
        assertNull(TextOperation.fromJson(listOf(2.5)))
        assertNull(TextOperation.fromJson(listOf(Long.MAX_VALUE)))
    }

    // ------------------------------------------------------------ positions

    @Test
    fun `un curseur suit le texte insere ou supprime avant lui`() {
        val insert = TextOperation().retain(2).insert("abc").retain(5)
        assertEquals(1, insert.transformIndex(1))
        assertEquals(5, insert.transformIndex(2)) // pile à l'endroit : repoussé après
        assertEquals(10, insert.transformIndex(7))
        val delete = TextOperation().retain(2).delete(3).retain(5)
        assertEquals(2, delete.transformIndex(4)) // dans la partie supprimée : ramené au bord
        assertEquals(4, delete.transformIndex(7))
    }

    // ------------------------------------------------------------ propriétés, au hasard

    private val alphabet = "abcdeéàç ,.\n"

    private fun randomText(random: Random, length: Int) =
        buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    /** Une opération quelconque sur [text]. */
    private fun randomOperation(random: Random, text: String): TextOperation {
        val op = TextOperation()
        while (op.baseLength < text.length) {
            val n = 1 + random.nextInt(minOf(text.length - op.baseLength, 6))
            when (random.nextInt(3)) {
                0 -> op.retain(n)
                1 -> op.insert(randomText(random, 1 + random.nextInt(4)))
                else -> op.delete(n)
            }
        }
        if (random.nextBoolean()) op.insert(randomText(random, 1 + random.nextInt(3)))
        return op
    }

    @Test
    fun `apply respecte les longueurs annoncees`() {
        val random = Random(1)
        repeat(2000) {
            val text = randomText(random, random.nextInt(30))
            val op = randomOperation(random, text)
            assertEquals(text.length, op.baseLength)
            assertEquals(op.targetLength, op.apply(text).length)
        }
    }

    @Test
    fun `compose equivaut a appliquer l'une puis l'autre`() {
        val random = Random(2)
        repeat(2000) {
            val text = randomText(random, random.nextInt(30))
            val a = randomOperation(random, text)
            val afterA = a.apply(text)
            val b = randomOperation(random, afterA)
            assertEquals(b.apply(afterA), a.compose(b).apply(text))
        }
    }

    @Test
    fun `transform fait converger deux modifications simultanees`() {
        val random = Random(3)
        repeat(5000) {
            val text = randomText(random, random.nextInt(30))
            val a = randomOperation(random, text)
            val b = randomOperation(random, text)
            val (aPrime, bPrime) = TextOperation.transform(a, b)
            assertEquals(bPrime.apply(a.apply(text)), aPrime.apply(b.apply(text)))
        }
    }

    @Test
    fun `a la meme position, l'insertion du premier passe devant`() {
        val a = TextOperation().retain(1).insert("A").retain(1)
        val b = TextOperation().retain(1).insert("B").retain(1)
        val (aPrime, bPrime) = TextOperation.transform(a, b)
        assertEquals("xABy", bPrime.apply(a.apply("xy")))
        assertEquals("xABy", aPrime.apply(b.apply("xy")))
    }

    @Test
    fun `deux suppressions du meme passage ne suppriment qu'une fois`() {
        val a = TextOperation().retain(1).delete(3).retain(1)
        val b = TextOperation().retain(2).delete(3)
        val (aPrime, bPrime) = TextOperation.transform(a, b)
        assertEquals("a", bPrime.apply(a.apply("abcde")))
        assertEquals("a", aPrime.apply(b.apply("abcde")))
    }
}
