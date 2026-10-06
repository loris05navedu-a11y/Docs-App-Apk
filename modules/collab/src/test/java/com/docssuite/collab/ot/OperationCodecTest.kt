package com.docssuite.collab.ot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class OperationCodecTest {

    @Test
    fun `une operation s'ecrit en JSON compact`() {
        val op = TextOperation().retain(5).insert("abc").delete(2)
        assertEquals("""[5,"abc",-2]""", OperationCodec.encode(op))
        assertEquals("[]", OperationCodec.encode(TextOperation()))
    }

    @Test
    fun `les caracteres speciaux font l'aller-retour`() {
        val text = "guillemet \" barre \\ ligne\nretour\rtab\t nul\u0000 é 😀 \u2028 fin"
        val op = TextOperation().insert(text)
        val json = OperationCodec.encode(op)
        assertEquals(op, OperationCodec.decode(json))
        // Ce que d'autres outils écriraient aussi.
        assertEquals(TextOperation().retain(1).insert("é/😀"), OperationCodec.decode(""" [ 1 , "\u00e9\/\ud83d\ude00" ] """))
    }

    @Test
    fun `l'operation vide fait l'aller-retour`() {
        assertEquals(TextOperation(), OperationCodec.decode("[]"))
    }

    @Test
    fun `ce qui n'est pas une operation est refuse`() {
        for (bad in listOf(
            null, "", "[", "]", "{}", "[1,]", "[,1]", "[1 2]", "[1.5]", "[0]", "[\"\"]", "[true]", "[null]",
            "[\"x]", "[\"\\q\"]", "[\"\\u12\"]", "[1] x", "[99999999999999]", "[\"a\nb\"]"
        )) {
            assertNull("accepté : $bad", OperationCodec.decode(bad))
        }
    }

    @Test
    fun `des operations au hasard font l'aller-retour`() {
        val random = Random(3)
        val alphabet = "ab \"\\\n\té😀\u0001"
        repeat(2000) {
            val op = TextOperation()
            repeat(random.nextInt(6)) {
                when (random.nextInt(3)) {
                    0 -> op.retain(1 + random.nextInt(50))
                    1 -> op.insert(buildString { repeat(1 + random.nextInt(5)) { append(alphabet[random.nextInt(alphabet.length)]) } })
                    else -> op.delete(1 + random.nextInt(50))
                }
            }
            assertEquals(op, OperationCodec.decode(OperationCodec.encode(op)))
        }
    }
}
