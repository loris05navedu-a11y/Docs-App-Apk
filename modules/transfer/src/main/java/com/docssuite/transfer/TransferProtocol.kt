package com.docssuite.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Le format des échanges entre les deux appareils, sur une simple socket TCP.
 * Chaque message est écrit puis immédiatement transmis (`flush`), pour que
 * l'autre bout n'attende jamais un octet resté dans un tampon.
 *
 * Déroulement d'une session :
 *
 * 1. Qui reçoit se connecte et envoie un [Handshake].
 * 2. Qui envoie répond par une [HandshakeResponse] — refusée si le code ne
 *    correspond pas, sinon accompagnée du nom de chaque fichier proposé.
 * 3. Qui reçoit renvoie une [Selection] : quels fichiers du manifeste il veut.
 * 4. Qui envoie transmet, dans l'ordre du manifeste, les octets bruts de
 *    chaque fichier sélectionné — sans les autres.
 * 5. Qui envoie referme la connexion. Une fermeture avant la fin du dernier
 *    fichier annoncé est une interruption, pas une fin normale.
 */
object TransferProtocol {

    const val CHUNK_SIZE = 64 * 1024

    data class Handshake(val deviceName: String, val pin: String)

    data class HandshakeResponse(val accepted: Boolean, val senderName: String, val files: List<TransferFile>, val reason: String)

    // ------------------------------------------------------------ écriture

    fun writeHandshake(out: DataOutputStream, handshake: Handshake) {
        out.writeUTF(PROTOCOL_MAGIC)
        out.writeUTF(handshake.deviceName.take(MAX_NAME_LENGTH))
        out.writeUTF(handshake.pin)
        out.flush()
    }

    fun writeAccepted(out: DataOutputStream, senderName: String, files: List<TransferFile>) {
        out.writeBoolean(true)
        out.writeUTF(senderName.take(MAX_NAME_LENGTH))
        out.writeInt(files.size)
        files.forEach { file ->
            out.writeUTF(file.name.take(MAX_NAME_LENGTH))
            out.writeUTF(file.mimeType.take(128))
            out.writeLong(file.size)
        }
        out.flush()
    }

    fun writeRejected(out: DataOutputStream, reason: String) {
        out.writeBoolean(false)
        out.writeUTF(reason.take(256))
        out.flush()
    }

    fun writeSelection(out: DataOutputStream, selected: List<Boolean>) {
        out.writeInt(selected.size)
        selected.forEach { out.writeBoolean(it) }
        out.flush()
    }

    /**
     * Copie exactement [size] octets de [source] vers [out], en signalant la
     * progression après chaque bloc. `flush` à la fin seulement : un flush par
     * bloc ralentirait un gros fichier pour rien.
     */
    fun writeFileBody(out: OutputStream, source: InputStream, size: Long, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(CHUNK_SIZE)
        var sent = 0L
        while (sent < size) {
            val toRead = minOf(buffer.size.toLong(), size - sent).toInt()
            val read = source.read(buffer, 0, toRead)
            if (read <= 0) throw EOFException("Le fichier source s'est arrêté avant sa taille annoncée")
            out.write(buffer, 0, read)
            sent += read
            onProgress(sent)
        }
        out.flush()
    }

    // ------------------------------------------------------------ lecture

    fun readHandshake(input: DataInputStream): Handshake {
        val magic = input.readUTF()
        if (magic != PROTOCOL_MAGIC) throw ProtocolException("Ce n'est pas une connexion DocsApp Suite")
        val deviceName = input.readUTF()
        val pin = input.readUTF()
        return Handshake(deviceName, pin)
    }

    fun readHandshakeResponse(input: DataInputStream): HandshakeResponse {
        val accepted = input.readBoolean()
        if (!accepted) {
            val reason = input.readUTF()
            return HandshakeResponse(false, "", emptyList(), reason)
        }
        val senderName = input.readUTF()
        val count = input.readInt()
        if (count < 0 || count > 100_000) throw ProtocolException("Manifeste invalide")
        val files = (0 until count).map {
            val name = input.readUTF()
            val mime = input.readUTF()
            val size = input.readLong()
            if (size < 0) throw ProtocolException("Taille de fichier invalide")
            TransferFile(name, size, mime)
        }
        return HandshakeResponse(true, senderName, files, "")
    }

    fun readSelection(input: DataInputStream, expectedCount: Int): List<Boolean> {
        val count = input.readInt()
        if (count != expectedCount) throw ProtocolException("La sélection ne correspond pas au manifeste")
        return (0 until count).map { input.readBoolean() }
    }

    /**
     * Copie exactement [size] octets de [input] vers [sink], en signalant la
     * progression après chaque bloc reçu.
     */
    fun readFileBody(input: InputStream, sink: OutputStream, size: Long, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(CHUNK_SIZE)
        var received = 0L
        while (received < size) {
            val toRead = minOf(buffer.size.toLong(), size - received).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) throw EOFException("Connexion interrompue avant la fin du fichier")
            sink.write(buffer, 0, read)
            received += read
            onProgress(received)
        }
    }
}
