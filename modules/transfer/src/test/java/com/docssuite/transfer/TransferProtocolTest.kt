package com.docssuite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Le format des messages, vérifié en mémoire, sans passer par une socket. */
class TransferProtocolTest {

    private fun pipe(): Pair<DataOutputStream, () -> DataInputStream> {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        return out to { DataInputStream(ByteArrayInputStream(buffer.toByteArray())) }
    }

    @Test
    fun `une poignee de main se relit telle quelle`() {
        val (out, input) = pipe()
        TransferProtocol.writeHandshake(out, TransferProtocol.Handshake("Téléphone de Loris", "4821"))
        val back = TransferProtocol.readHandshake(input())
        assertEquals("Téléphone de Loris", back.deviceName)
        assertEquals("4821", back.pin)
    }

    @Test
    fun `une reponse acceptee porte le manifeste complet`() {
        val (out, input) = pipe()
        val files = listOf(
            TransferFile("photo.jpg", 12_345L, "image/jpeg"),
            TransferFile("rapport.pdf", 0L, "application/pdf")
        )
        TransferProtocol.writeAccepted(out, "Tablette de Thao", files)
        val back = TransferProtocol.readHandshakeResponse(input())
        assertTrue(back.accepted)
        assertEquals("Tablette de Thao", back.senderName)
        assertEquals(files, back.files)
    }

    @Test
    fun `une reponse refusee porte le motif`() {
        val (out, input) = pipe()
        TransferProtocol.writeRejected(out, "Code incorrect")
        val back = TransferProtocol.readHandshakeResponse(input())
        assertFalse(back.accepted)
        assertEquals("Code incorrect", back.reason)
    }

    @Test
    fun `une selection incomplete par rapport au manifeste est refusee`() {
        val (out, input) = pipe()
        TransferProtocol.writeSelection(out, listOf(true, false))
        assertThrows(ProtocolException::class.java) {
            TransferProtocol.readSelection(input(), expectedCount = 3)
        }
    }

    @Test
    fun `une connexion sans le bon en-tete est rejetee`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeUTF("AUTRE-PROTOCOLE")
        assertThrows(ProtocolException::class.java) {
            TransferProtocol.readHandshake(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
        }
    }

    @Test
    fun `le corps d un fichier se copie exactement, en plusieurs blocs`() {
        val original = ByteArray(TransferProtocol.CHUNK_SIZE * 2 + 137) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        TransferProtocol.writeFileBody(out, ByteArrayInputStream(original), original.size.toLong()) { progress.add(it) }
        assertTrue(progress.size >= 3)
        assertEquals(original.size.toLong(), progress.last())

        val received = ByteArrayOutputStream()
        TransferProtocol.readFileBody(ByteArrayInputStream(out.toByteArray()), received, original.size.toLong()) {}
        assertTrue(original.contentEquals(received.toByteArray()))
    }

    @Test
    fun `un fichier vide se transfere sans lire un seul octet`() {
        val out = ByteArrayOutputStream()
        TransferProtocol.writeFileBody(out, ByteArrayInputStream(ByteArray(0)), 0L) {}
        assertEquals(0, out.size())
    }

    @Test
    fun `une source qui s arrete avant la taille annoncee est signalee`() {
        val out = ByteArrayOutputStream()
        assertThrows(java.io.EOFException::class.java) {
            TransferProtocol.writeFileBody(out, ByteArrayInputStream(ByteArray(10)), 20L) {}
        }
    }
}
