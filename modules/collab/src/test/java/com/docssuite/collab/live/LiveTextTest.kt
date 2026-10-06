package com.docssuite.collab.live

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.docssuite.collab.ot.Revision
import com.docssuite.collab.ot.RevisionLog
import com.docssuite.collab.ot.TextOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LiveTextTest {

    private class Log : RevisionLog {
        val appends = ArrayList<Pair<Int, Revision>>()
        override fun append(index: Int, revision: Revision) {
            appends += index to revision
        }

        override fun saveCheckpoint(nextIndex: Int, text: String) {}
    }

    private fun remote(op: TextOperation, who: String = "autre") = Revision(who, "uid-$who", op)

    @Test
    fun `une frappe s'affiche tout de suite et part au journal`() {
        val log = Log()
        val live = LiveText("moi", "uid", log, 0, "Bonjour")
        live.onValueChange(TextFieldValue("Bonjour!", TextRange(8)))
        assertEquals("Bonjour!", live.value.text)
        assertEquals(TextRange(8), live.value.selection)
        val (index, revision) = log.appends.single()
        assertEquals(0, index)
        assertEquals(TextOperation().retain(7).insert("!"), revision.operation)
        assertFalse(live.synced)
        live.receive(0, revision)
        assertTrue(live.synced)
    }

    @Test
    fun `une frappe sur l'ancien texte n'efface pas ce qui vient d'arriver`() {
        val log = Log()
        val live = LiveText("moi", "uid", log, 0, "abc")
        live.shown(live.value)
        live.receive(0, remote(TextOperation().insert("X").retain(3)))
        assertEquals("Xabc", live.value.text)
        // Le champ n'est pas encore redessiné : le clavier tape « d » au bout de « abc ».
        live.onValueChange(TextFieldValue("abcd", TextRange(4)))
        assertEquals("Xabcd", live.value.text)
        assertEquals(TextRange(5), live.value.selection)
        assertEquals(TextOperation().retain(4).insert("d"), log.appends.single().second.operation)
        // Une autre frappe, toujours sur l'ancien affichage.
        live.onValueChange(TextFieldValue("abcde", TextRange(5)))
        assertEquals("Xabcde", live.value.text)
        // Le champ se redessine ; les frappes suivantes partent du bon texte.
        live.shown(live.value)
        live.onValueChange(TextFieldValue("Xabcdef", TextRange(7)))
        assertEquals("Xabcdef", live.value.text)
    }

    @Test
    fun `mon curseur suit les modifications des autres`() {
        val live = LiveText("moi", "uid", Log(), 0, "Bonjour monde")
        live.shown(live.value)
        live.onValueChange(TextFieldValue("Bonjour monde", TextRange(8)))
        live.receive(0, remote(TextOperation().insert(">> ").retain(13)))
        assertEquals(TextRange(11), live.value.selection)
        // Une sélection aussi.
        live.shown(live.value)
        live.onValueChange(TextFieldValue(live.value.text, TextRange(3, 10)))
        live.receive(1, remote(TextOperation().retain(5).delete(3).retain(8)))
        assertEquals(">> Bour monde", live.value.text)
        assertEquals(TextRange(3, 7), live.value.selection)
    }

    @Test
    fun `les curseurs des autres suivent le texte, sauf celui de l'auteur`() {
        val live = LiveText("moi", "uid", Log(), 0, "0123456789")
        live.setCursor("paul", 5, 5)
        live.setCursor("nina", 8, 8)
        // Paul insère « ab » à sa place : il annonce lui-même son nouveau curseur.
        live.receive(0, remote(TextOperation().retain(5).insert("ab").retain(5), who = "paul"))
        assertEquals(RemoteCursor(5, 5), live.cursors["paul"])
        assertEquals(RemoteCursor(10, 10), live.cursors["nina"])
        // Ma frappe au début décale tout le monde.
        live.shown(live.value)
        live.onValueChange(TextFieldValue("_" + live.value.text, TextRange(1)))
        assertEquals(RemoteCursor(6, 6), live.cursors["paul"])
        assertEquals(RemoteCursor(11, 11), live.cursors["nina"])
        // Un curseur annoncé trop loin est ramené au texte.
        live.setCursor("omar", 500, 3)
        assertEquals(RemoteCursor(live.value.text.length, 3), live.cursors["omar"])
    }

    @Test
    fun `en lecture seule, une frappe ne change rien`() {
        val log = Log()
        val live = LiveText("moi", "uid", log, 0, "Texte")
        live.shown(live.value)
        live.onValueChange(TextFieldValue("Texte modifié", TextRange(13)), editable = false)
        assertEquals("Texte", live.value.text)
        assertTrue(log.appends.isEmpty())
        // On peut toujours sélectionner.
        live.shown(live.value)
        live.onValueChange(TextFieldValue("Texte", TextRange(1, 4)), editable = false)
        assertEquals(TextRange(1, 4), live.value.selection)
    }

    @Test
    fun `oublier ses frappes revient au texte du journal`() {
        val log = Log()
        val live = LiveText("moi", "uid", log, 0, "Texte")
        live.shown(live.value)
        live.onValueChange(TextFieldValue("Texte en trop", TextRange(13)))
        live.discardLocal()
        assertEquals("Texte", live.value.text)
        assertTrue(live.synced)
        // La suite du journal s'applique normalement.
        live.receive(0, remote(TextOperation().retain(5).insert(".")))
        assertEquals("Texte.", live.value.text)
    }

    /**
     * Deux à quatre téléphones, chacun avec son champ dont l'affichage est en
     * retard au hasard sur le texte réel : tout le monde finit sur le même
     * texte, et aucune lettre tapée ne se perd.
     */
    @Test
    fun `des champs en retard d'affichage convergent sans perdre une lettre`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val slots = ArrayList<Revision>()
            val writes = ArrayList<Pair<Int, Revision>>()
            class Phone(val name: String) {
                val delivered = ArrayList<Int>()
                val live = LiveText(name, "uid-$name", object : RevisionLog {
                    override fun append(index: Int, revision: Revision) {
                        writes += index to revision
                    }

                    override fun saveCheckpoint(nextIndex: Int, text: String) {}
                }, 0, "")
                var field = live.value
                var typed = 0

                fun redraw() {
                    field = live.value
                    live.shown(field)
                }

                fun type() {
                    val at = random.nextInt(field.text.length + 1)
                    val letter = name.first().toString()
                    field = TextFieldValue(field.text.substring(0, at) + letter + field.text.substring(at), TextRange(at + 1))
                    typed++
                    live.onValueChange(field)
                }
            }
            val phones = List(2 + random.nextInt(3)) { Phone("abcd"[it].toString()) }

            fun process() {
                val (index, revision) = writes.removeAt(random.nextInt(writes.size))
                if (index == slots.size) slots += revision
            }

            fun deliver(phone: Phone) {
                val next = phone.delivered.size
                if (next < slots.size) {
                    phone.live.receive(next, slots[next])
                    phone.delivered += next
                }
            }

            repeat(300) {
                val phone = phones.random(random)
                when (random.nextInt(6)) {
                    0, 1 -> phone.type()
                    2 -> phone.redraw()
                    3 -> if (writes.isNotEmpty()) process()
                    else -> deliver(phone)
                }
            }
            var guard = 0
            while (writes.isNotEmpty() || phones.any { it.delivered.size < slots.size }) {
                check(guard++ < 100_000)
                if (writes.isNotEmpty() && random.nextBoolean()) process() else deliver(phones.random(random))
            }
            phones.forEach { it.redraw() }

            val expected = phones.first().live.value.text
            phones.forEach { phone ->
                assertTrue("graine $seed", phone.live.synced)
                assertEquals("graine $seed : ${phone.name}", expected, phone.live.value.text)
                assertEquals("graine $seed : ${phone.name}, lettres", phone.typed, expected.count { it == phone.name.first() })
            }
        }
    }
}
