package com.docssuite.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Le transfert entier, de bout en bout, sur de vraies sockets TCP en boucle
 * locale (127.0.0.1) — la même API `java.net.Socket` que sur un téléphone,
 * sans rien d'Android à simuler.
 */
class LoopbackTransferTest {

    private val executor = Executors.newCachedThreadPool()

    private fun randomBytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed) % 256).toByte() }

    /** Un fichier en mémoire, des deux côtés : source à l'envoi, tampon à la réception. */
    private class MemoryFile(name: String, val bytes: ByteArray, mime: String = "application/octet-stream") {
        val meta = TransferFile(name, bytes.size.toLong(), mime)
        val received = ByteArrayOutputStream()
        fun source() = FileSource { ByteArrayInputStream(bytes) }
        fun sink() = FileSink { received }
    }

    @Test
    fun `trois fichiers de tailles differentes arrivent identiques`() {
        val files = listOf(
            MemoryFile("vide.txt", ByteArray(0)),
            MemoryFile("court.txt", randomBytes(200, 1)),
            MemoryFile("gros.bin", randomBytes(TransferProtocol.CHUNK_SIZE * 3 + 500, 2))
        )
        val server = TransferServer("Téléphone A", "1234", files.map { OfferedFile(it.meta, it.source()) })
        val port = server.bind()

        val serverStates = CopyOnWriteArrayList<SendState>()
        val serverDone = executor.submit(java.util.concurrent.Callable { server.serve { serverStates.add(it) } })

        val client = TransferClient()
        val manifestState = client.connectSafely("127.0.0.1", port, "Téléphone B", "1234") {}
        val manifest = (manifestState as ReceiveState.ReviewingManifest).files
        assertEquals(files.map { it.meta }, manifest)
        assertEquals("Téléphone A", manifestState.senderName)

        val clientStates = CopyOnWriteArrayList<ReceiveState>()
        val outcome = client.receiveSelectedSafely(
            manifest, manifest.map { true },
            sinkFor = { meta -> files.first { it.meta.name == meta.name }.sink() }
        ) { clientStates.add(it) }
        client.close()

        assertTrue(outcome is ReceiveState.Completed)
        assertEquals(3, (outcome as ReceiveState.Completed).received.size)
        files.forEach { assertArrayEquals("${it.meta.name}", it.bytes, it.received.toByteArray()) }

        assertEquals(SendState.Completed, serverDone.get(5, TimeUnit.SECONDS))
        assertTrue(serverStates.any { it is SendState.Sending })
        assertTrue(clientStates.any { it is ReceiveState.Receiving })
    }

    @Test
    fun `un mauvais code est refuse puis le bon code passe sur une nouvelle connexion`() {
        val file = MemoryFile("secret.txt", randomBytes(64, 9))
        val server = TransferServer("Hôte", "7777", listOf(OfferedFile(file.meta, file.source())))
        val port = server.bind()
        val serverDone = executor.submit(java.util.concurrent.Callable { server.serve {} })

        val wrongClient = TransferClient()
        val rejected = wrongClient.connectSafely("127.0.0.1", port, "Intrus", "0000") {}
        assertTrue(rejected is ReceiveState.Failed)
        wrongClient.close()

        val goodClient = TransferClient()
        val accepted = goodClient.connectSafely("127.0.0.1", port, "Ami", "7777") {}
        assertTrue(accepted is ReceiveState.ReviewingManifest)
        val manifest = (accepted as ReceiveState.ReviewingManifest).files
        val outcome = goodClient.receiveSelectedSafely(manifest, listOf(true), { file.sink() }) {}
        goodClient.close()

        assertTrue(outcome is ReceiveState.Completed)
        assertArrayEquals(file.bytes, file.received.toByteArray())
        assertEquals(SendState.Completed, serverDone.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `au dela du nombre maximal de tentatives le serveur abandonne`() {
        val file = MemoryFile("x.txt", randomBytes(4, 1))
        val server = TransferServer("Hôte", "1111", listOf(OfferedFile(file.meta, file.source())), maxAttempts = 2)
        val port = server.bind()
        val serverDone = executor.submit(java.util.concurrent.Callable { server.serve {} })

        repeat(2) {
            val client = TransferClient()
            client.connectSafely("127.0.0.1", port, "Intrus", "9999") {}
            client.close()
        }

        val finalState = serverDone.get(5, TimeUnit.SECONDS)
        assertTrue(finalState is SendState.Failed)
    }

    @Test
    fun `seuls les fichiers coches sont recus`() {
        val files = listOf(
            MemoryFile("garde.txt", randomBytes(50, 3)),
            MemoryFile("ignore.txt", randomBytes(50, 4)),
            MemoryFile("garde2.txt", randomBytes(50, 5))
        )
        val server = TransferServer("Hôte", "5555", files.map { OfferedFile(it.meta, it.source()) })
        val port = server.bind()
        val serverStates = CopyOnWriteArrayList<SendState>()
        val serverDone = executor.submit(java.util.concurrent.Callable { server.serve { serverStates.add(it) } })

        val client = TransferClient()
        val manifestState = client.connectSafely("127.0.0.1", port, "Invité", "5555") {} as ReceiveState.ReviewingManifest
        val selection = manifestState.files.map { it.name != "ignore.txt" }
        val outcome = client.receiveSelectedSafely(
            manifestState.files, selection,
            sinkFor = { meta -> files.first { it.meta.name == meta.name }.sink() }
        ) {} as ReceiveState.Completed
        client.close()

        assertEquals(setOf("garde.txt", "garde2.txt"), outcome.received.map { it.name }.toSet())
        assertArrayEquals(files[0].bytes, files[0].received.toByteArray())
        assertArrayEquals(files[2].bytes, files[2].received.toByteArray())
        assertEquals(0, files[1].received.size())

        assertEquals(SendState.Completed, serverDone.get(5, TimeUnit.SECONDS))
        // Le fichier écarté n'a jamais été ouvert côté envoi.
        assertTrue(serverStates.filterIsInstance<SendState.Sending>().none { it.fileName == "ignore.txt" })
    }

    @Test
    fun `annuler en cours de reception interrompt proprement les deux cotes`() {
        val big = MemoryFile("gros.bin", randomBytes(TransferProtocol.CHUNK_SIZE * 20, 7))
        val server = TransferServer("Hôte", "2468", listOf(OfferedFile(big.meta, big.source())))
        val port = server.bind()
        val serverDone = executor.submit(java.util.concurrent.Callable { server.serve {} })

        val client = TransferClient()
        val manifestState = client.connectSafely("127.0.0.1", port, "Invité", "2468") {} as ReceiveState.ReviewingManifest

        val outcome = client.receiveSelectedSafely(manifestState.files, listOf(true), { big.sink() }) { state ->
            // Dès le premier progrès réel, on annule : le transfert est
            // forcément incomplet, ce qui rend le test fiable sans minuterie.
            if (state is ReceiveState.Receiving && state.bytesReceived > 0) {
                client.cancel()
            }
        }
        assertTrue(outcome is ReceiveState.Cancelled)

        val finalServerState = serverDone.get(5, TimeUnit.SECONDS)
        assertTrue(finalServerState is SendState.Failed || finalServerState is SendState.Cancelled)
        assertTrue(big.received.size() < big.bytes.size)
    }

    @Test
    fun `une connexion qui n est pas ce protocole ne fait pas planter le serveur`() {
        val file = MemoryFile("a.txt", randomBytes(4, 1))
        val server = TransferServer("Hôte", "3333", listOf(OfferedFile(file.meta, file.source())))
        val port = server.bind()
        val serverStates = CopyOnWriteArrayList<SendState>()
        val serverDone = executor.submit(java.util.concurrent.Callable { server.serve { serverStates.add(it) } })

        java.net.Socket("127.0.0.1", port).use { garbage ->
            // Une trame bien formée mais qui n'est pas notre protocole : le
            // serveur doit la reconnaître comme telle, sans bloquer sur un
            // flux qui n'enverra jamais les octets attendus.
            java.io.DataOutputStream(garbage.getOutputStream()).writeUTF("bonjour")
        }

        // Le serveur signale l'échec de cette connexion sans lever d'exception
        // qui remonterait jusqu'au fil appelant.
        val finalState = serverDone.get(5, TimeUnit.SECONDS)
        assertTrue(finalState is SendState.Failed || finalState is SendState.Stopped)
        server.cancel()
    }
}
