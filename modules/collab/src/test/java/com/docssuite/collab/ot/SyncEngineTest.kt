package com.docssuite.collab.ot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * La co-édition simulée de bout en bout : un journal qui se comporte comme
 * Firebase (une place ne se remplit qu'une fois, la première écriture gagne),
 * des appareils qui tapent en même temps, et un réseau capricieux — écritures
 * traitées dans le désordre, confirmations perdues (on réécrit alors à la même
 * place), révisions livrées en retard, dans le désordre ou en double.
 *
 * À la fin, une fois tout livré, chaque appareil doit afficher exactement le
 * texte du journal.
 */
class SyncEngineTest {

    private class Server(val random: Random, val checkpointEvery: Int) {
        val slots = ArrayList<Revision>()
        val pendingWrites = ArrayList<Pair<Int, Revision>>()
        val clients = ArrayList<Device>()
        var checkpoint: Pair<Int, String>? = null
        /** Écritures refusées parce que la place était prise : les conflits. */
        var conflicts = 0

        fun process(write: Pair<Int, Revision>) {
            val (index, revision) = write
            if (index == slots.size) {
                slots.add(revision)
                clients.forEach { it.undelivered.add(index) }
            } else if (slots.getOrNull(index)?.clientId != revision.clientId) {
                conflicts++
            }
            // Confirmation perdue : l'appareil ne sait pas si c'est passé et
            // réécrit à la même place (Firebase rejoue la transaction).
            if (random.nextInt(10) == 0) pendingWrites.add(write)
        }

        fun replay(): String {
            var text = ""
            for (revision in slots) {
                val op = revision.operation.takeIf { it.baseLength == text.length } ?: TextOperation.identity(text.length)
                text = op.apply(text)
            }
            return text
        }
    }

    private class Device(val name: String, val server: Server, startIndex: Int = 0, startText: String = "") {
        var text = startText
        var caret = startText.length
        val undelivered = ArrayList<Int>()
        val engine = SyncEngine(
            clientId = name,
            uid = "uid-$name",
            log = object : RevisionLog {
                override fun append(index: Int, revision: Revision) {
                    server.pendingWrites.add(index to revision)
                }

                override fun saveCheckpoint(nextIndex: Int, text: String) {
                    val current = server.checkpoint
                    if (current == null || current.first < nextIndex) server.checkpoint = nextIndex to text
                }
            },
            startIndex = startIndex,
            startText = startText,
            onRemote = { op, author ->
                check(author != name) { "sa propre frappe lui revient comme une frappe d'ailleurs" }
                text = op.apply(text)
                caret = op.transformIndex(caret).coerceIn(0, text.length)
            },
            checkpointEvery = server.checkpointEvery
        )

        fun type(random: Random) {
            val before = text
            val at = random.nextInt(text.length + 1)
            val after = when (random.nextInt(5)) {
                0, 1 -> before.substring(0, at) + pick(random) + before.substring(at)
                2 -> if (before.isEmpty()) "x" else {
                    val end = minOf(before.length, at + 1 + random.nextInt(4))
                    val start = minOf(at, end - 1)
                    before.substring(0, start) + before.substring(end)
                }
                3 -> before.substring(0, at) + "mot " + before.substring(at)
                else -> if (before.isEmpty()) "a" else {
                    // Remplacer une sélection, comme un collage.
                    val end = minOf(before.length, at + random.nextInt(5))
                    before.substring(0, at) + pick(random) + pick(random) + before.substring(end)
                }
            }
            val newCaret = (after.length - (before.length - at)).coerceIn(0, after.length)
            text = after
            caret = newCaret
            engine.local(TextDiff.between(before, after, newCaret))
        }

        private fun pick(random: Random): String = listOf("a", "b", "é", "z", " ", "\n", "😀", "ç")[random.nextInt(8)]

        fun deliver(random: Random) {
            if (undelivered.isEmpty()) return
            // Le plus souvent dans l'ordre, parfois dans le désordre, parfois en double.
            val index = if (random.nextInt(4) == 0) undelivered[random.nextInt(undelivered.size)] else undelivered.min()
            if (random.nextInt(8) != 0) undelivered.remove(index)
            engine.receive(index, server.slots[index])
        }
    }

    private fun simulate(seed: Int, devices: Int, steps: Int, checkpointEvery: Int = 100): Server {
        val random = Random(seed)
        val server = Server(random, checkpointEvery)
        repeat(devices) { server.clients.add(Device("d$it", server)) }
        repeat(steps) {
            when (random.nextInt(10)) {
                0, 1, 2, 3 -> server.clients.random(random).type(random)
                4, 5, 6 -> if (server.pendingWrites.isNotEmpty()) {
                    server.process(server.pendingWrites.removeAt(random.nextInt(server.pendingWrites.size)))
                }
                else -> server.clients.random(random).deliver(random)
            }
        }
        settle(server, random)
        return server
    }

    /** Plus personne ne tape : on laisse le réseau tout livrer. */
    private fun settle(server: Server, random: Random) {
        var guard = 0
        while (server.pendingWrites.isNotEmpty() || server.clients.any { it.undelivered.isNotEmpty() }) {
            check(guard++ < 1_000_000) { "La synchronisation ne s'arrête pas" }
            if (server.pendingWrites.isNotEmpty() && (random.nextBoolean() || server.clients.all { it.undelivered.isEmpty() })) {
                server.process(server.pendingWrites.removeAt(random.nextInt(server.pendingWrites.size)))
            } else {
                server.clients.filter { it.undelivered.isNotEmpty() }.random(random).deliver(random)
            }
        }
    }

    private fun assertConverged(server: Server, label: String) {
        val expected = server.replay()
        server.clients.forEach { device ->
            assertTrue("$label : ${device.name} a encore des frappes en vol", device.engine.synced)
            assertEquals("$label : ${device.name} ne voit pas le texte du journal", expected, device.text)
            assertEquals("$label : ${device.name}, texte du serveur", expected, device.engine.serverText)
        }
    }

    @Test
    fun `deux personnes qui tapent en meme temps voient le meme texte`() {
        var conflicts = 0
        for (seed in 1..300) {
            val server = simulate(seed, devices = 2, steps = 200)
            assertConverged(server, "graine $seed")
            conflicts += server.conflicts
        }
        // La simulation doit vraiment faire se croiser les frappes.
        assertTrue("seulement $conflicts conflits", conflicts > 1000)
    }

    @Test
    fun `cinq personnes, beaucoup de frappes`() {
        var conflicts = 0
        for (seed in 1..60) {
            val server = simulate(1000 + seed, devices = 5, steps = 1200)
            assertConverged(server, "graine $seed")
            conflicts += server.conflicts
            assertTrue(server.replay().length > 50)
        }
        println("Cinq appareils : $conflicts conflits résolus")
        assertTrue("seulement $conflicts conflits", conflicts > 1000)
    }

    @Test
    fun `aucune frappe n'est perdue quand on tape seul`() {
        val random = Random(7)
        val server = Server(random, 100)
        val device = Device("seul", server)
        server.clients.add(device)
        repeat(300) { device.type(random) }
        settle(server, random)
        assertConverged(server, "seul")
        assertEquals(device.text, server.replay())
    }

    @Test
    fun `une frappe locale reste affichee meme si le reseau ne repond pas`() {
        val random = Random(8)
        val server = Server(random, 100)
        val device = Device("hors-ligne", server)
        server.clients.add(device)
        repeat(20) { device.type(random) }
        // Rien n'a encore été écrit dans le journal, mais tout est à l'écran.
        assertTrue(server.slots.isEmpty())
        assertTrue(device.text.isNotEmpty())
        settle(server, random)
        assertConverged(server, "reconnexion")
    }

    @Test
    fun `un nouvel arrivant part de l'instantane et rattrape le reste`() {
        val random = Random(9)
        val server = simulate(9, devices = 3, steps = 1500, checkpointEvery = 20)
        val checkpoint = checkNotNull(server.checkpoint) { "aucun instantané écrit" }
        assertTrue(checkpoint.first > 0)

        val late = Device("tard", server, startIndex = checkpoint.first, startText = checkpoint.second)
        server.clients.add(late)
        late.undelivered.addAll(checkpoint.first until server.slots.size)
        repeat(300) {
            when (random.nextInt(3)) {
                0 -> server.clients.random(random).type(random)
                1 -> if (server.pendingWrites.isNotEmpty()) server.process(server.pendingWrites.removeAt(0))
                else -> server.clients.random(random).deliver(random)
            }
        }
        settle(server, random)
        assertConverged(server, "nouvel arrivant")
    }

    @Test
    fun `une revision illisible ne casse rien et compte pour tout le monde pareil`() {
        val random = Random(10)
        val server = Server(random, 100)
        val a = Device("a", server)
        val b = Device("b", server)
        server.clients.addAll(listOf(a, b))
        a.type(random)
        settle(server, random)
        // Une donnée abîmée prend la place suivante.
        val junk = Revision("pirate", "x", TextOperation().retain(999).insert("?"))
        server.slots.add(junk)
        server.clients.forEach { it.undelivered.add(server.slots.size - 1) }
        b.type(random)
        a.type(random)
        settle(server, random)
        assertConverged(server, "révision illisible")
    }

    @Test
    fun `sans droit d'ecrire, on oublie ses frappes et on suit le journal`() {
        val random = Random(12)
        val server = Server(random, 100)
        val a = Device("a", server)
        val b = Device("b", server)
        server.clients.addAll(listOf(a, b))
        a.type(random)
        settle(server, random)
        // b tape, mais ses écritures sont refusées : il n'est plus éditeur.
        repeat(5) { b.type(random) }
        server.pendingWrites.removeAll { it.second.clientId == "b" }
        b.text = b.engine.discardLocal()
        assertTrue(b.engine.synced)
        repeat(20) { a.type(random) }
        settle(server, random)
        assertConverged(server, "lecture seule")
    }

    @Test
    fun `l'ordre du journal fait foi pour deux insertions au meme endroit`() {
        val random = Random(11)
        val server = Server(random, 100)
        val a = Device("a", server, startText = "")
        val b = Device("b", server, startText = "")
        server.clients.addAll(listOf(a, b))
        a.text = "A"; a.engine.local(TextOperation().insert("A"))
        b.text = "B"; b.engine.local(TextOperation().insert("B"))
        // L'écriture de b arrive la première : sa place est la 0.
        server.process(server.pendingWrites.removeAt(1))
        server.process(server.pendingWrites.removeAt(0))
        settle(server, random)
        assertConverged(server, "même endroit")
        assertEquals(2, a.text.length)
    }
}
