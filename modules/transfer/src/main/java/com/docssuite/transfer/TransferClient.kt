package com.docssuite.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Côté appareil qui reçoit. Se connecte à l'appareil qui envoie, échange le
 * code, obtient le manifeste, puis — une fois que l'utilisateur a choisi quoi
 * garder — reçoit les fichiers.
 *
 * La connexion reste ouverte entre [connect] et [receiveSelected] : c'est ce
 * qui laisse le temps à l'utilisateur de cocher ses fichiers sans que
 * l'appareil qui envoie ne referme la session.
 */
class TransferClient {
    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    @Volatile private var cancelled = false

    /** Se connecte et envoie le code ; renvoie ce que l'autre appareil propose. */
    fun connect(host: String, port: Int, deviceName: String, pin: String): TransferProtocol.HandshakeResponse {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = SOCKET_TIMEOUT_MS
        socket = s
        input = DataInputStream(BufferedInputStream(s.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        TransferProtocol.writeHandshake(output, TransferProtocol.Handshake(deviceName, pin))
        return TransferProtocol.readHandshakeResponse(input)
    }

    /**
     * Envoie la sélection puis reçoit les fichiers choisis, dans l'ordre du
     * manifeste. [sinkFor] n'est appelé que pour les fichiers effectivement
     * demandés, juste avant de les recevoir.
     */
    fun receiveSelected(
        files: List<TransferFile>,
        selected: List<Boolean>,
        sinkFor: (TransferFile) -> FileSink,
        onState: (ReceiveState) -> Unit
    ): List<TransferFile> {
        TransferProtocol.writeSelection(output, selected)
        val toReceive = files.filterIndexed { index, _ -> selected.getOrElse(index) { false } }
        val received = ArrayList<TransferFile>(toReceive.size)

        toReceive.forEachIndexed { index, file ->
            onState(ReceiveState.Receiving(index, file.name, toReceive.size, 0, file.size))
            sinkFor(file).open().use { dst ->
                TransferProtocol.readFileBody(input, dst, file.size) { receivedSoFar ->
                    onState(ReceiveState.Receiving(index, file.name, toReceive.size, receivedSoFar, file.size))
                }
            }
            received.add(file)
        }
        return received
    }

    /** Referme la connexion, que le transfert soit fini ou non. */
    fun close() {
        runCatching { socket.close() }
    }

    /** Annule une opération bloquante en cours depuis un autre fil. */
    fun cancel() {
        cancelled = true
        close()
    }

    val wasCancelled: Boolean get() = cancelled

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}

/**
 * Le déroulement complet côté réception, jusqu'au manifeste : à appeler
 * depuis un fil dédié. Traduit les erreurs réseau et de protocole en
 * [ReceiveState] plutôt que de laisser échapper une exception.
 */
fun TransferClient.connectSafely(
    host: String,
    port: Int,
    deviceName: String,
    pin: String,
    onState: (ReceiveState) -> Unit
): ReceiveState {
    onState(ReceiveState.Connecting)
    val response = try {
        onState(ReceiveState.Verifying)
        connect(host, port, deviceName, pin)
    } catch (io: IOException) {
        val state = if (wasCancelled) ReceiveState.Cancelled
        else ReceiveState.Failed(io.message ?: "Connexion impossible")
        onState(state)
        return state
    } catch (protocol: ProtocolException) {
        val state = ReceiveState.Failed(protocol.message ?: "Cet appareil ne parle pas le même protocole")
        onState(state)
        return state
    }
    if (!response.accepted) {
        val state = ReceiveState.Failed(response.reason.ifBlank { "Code incorrect" })
        onState(state)
        return state
    }
    val state = ReceiveState.ReviewingManifest(response.senderName, response.files)
    onState(state)
    return state
}

/**
 * Reçoit les fichiers choisis, en traduisant toute interruption réseau en
 * [ReceiveState] plutôt que de laisser échapper une exception.
 */
fun TransferClient.receiveSelectedSafely(
    files: List<TransferFile>,
    selected: List<Boolean>,
    sinkFor: (TransferFile) -> FileSink,
    onState: (ReceiveState) -> Unit
): ReceiveState {
    val received = try {
        receiveSelected(files, selected, sinkFor, onState)
    } catch (io: IOException) {
        val state = if (wasCancelled) ReceiveState.Cancelled
        else ReceiveState.Failed(io.message ?: "Connexion interrompue")
        onState(state)
        return state
    } catch (protocol: ProtocolException) {
        val state = ReceiveState.Failed(protocol.message ?: "Réponse inattendue de l'autre appareil")
        onState(state)
        return state
    }
    val state = ReceiveState.Completed(received)
    onState(state)
    return state
}
