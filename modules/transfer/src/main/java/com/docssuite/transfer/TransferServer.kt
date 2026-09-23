package com.docssuite.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Côté appareil qui envoie. Ouvre un port, attend qu'un appareil s'y
 * connecte avec le bon code, puis transmet les fichiers choisis.
 *
 * Un seul transfert à la fois : après un code refusé, on réécoute pour
 * laisser une nouvelle tentative (faute de frappe fréquente), jusqu'à
 * [maxAttempts] — au-delà, mieux vaut refaire un code que continuer
 * d'accepter des essais.
 */
class TransferServer(
    private val deviceName: String,
    private val pin: String,
    private val files: List<OfferedFile>,
    private val maxAttempts: Int = 5
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var cancelled = false

    /**
     * Ouvre le port d'écoute et le renvoie ; à appeler avant [serve]. Essaie
     * d'abord [WELL_KNOWN_PORT], pour qu'une connexion sans découverte
     * automatique (adresse saisie à la main, Wi-Fi Direct) n'ait besoin que
     * de l'adresse IP ; s'il est déjà pris, un port libre quelconque suffit,
     * puisque la découverte réseau le communique de toute façon.
     */
    fun bind(): Int {
        val socket = runCatching { ServerSocket(WELL_KNOWN_PORT) }.getOrElse { ServerSocket(0) }
        serverSocket = socket
        return socket.localPort
    }

    /**
     * Boucle bloquante : à lancer sur un fil dédié (ou `Dispatchers.IO`
     * depuis une coroutine). Revient dès que le transfert est terminé,
     * a échoué, ou a été annulé.
     */
    fun serve(onState: (SendState) -> Unit): SendState {
        val server = serverSocket ?: throw IllegalStateException("bind() n'a pas été appelé")
        val port = server.localPort
        var attempts = 0

        while (true) {
            onState(SendState.Listening(port, pin))
            val socket = try {
                server.accept()
            } catch (io: IOException) {
                val state = if (cancelled) SendState.Cancelled else SendState.Stopped
                onState(state)
                return state
            }
            activeSocket = socket
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val outcome = runCatching { handleConnection(socket, port, onState) }
                .getOrElse { error ->
                    when (error) {
                        is ProtocolException -> SendState.Failed(error.message ?: "Protocole invalide")
                        is IOException -> if (cancelled) SendState.Cancelled else SendState.Failed(error.message ?: "Connexion interrompue")
                        else -> throw error
                    }
                }
            runCatching { socket.close() }

            if (outcome is SendState.Rejected) {
                attempts++
                onState(outcome)
                if (cancelled) {
                    onState(SendState.Cancelled)
                    return SendState.Cancelled
                }
                if (attempts >= maxAttempts) {
                    val failed = SendState.Failed("Trop de tentatives avec un mauvais code : générez un nouveau code.")
                    onState(failed)
                    return failed
                }
                continue
            }

            onState(outcome)
            return outcome
        }
    }

    /** Une connexion menée à son terme : code vérifié puis fichiers envoyés, ou refus. */
    private fun handleConnection(socket: Socket, port: Int, onState: (SendState) -> Unit): SendState {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        onState(SendState.PeerConnecting(port, pin))
        val handshake = TransferProtocol.readHandshake(input)
        if (handshake.pin != pin) {
            TransferProtocol.writeRejected(output, "Code incorrect")
            return SendState.Rejected(port, pin, "« ${handshake.deviceName} » a saisi un code incorrect.")
        }

        val manifest = files.map { it.meta }
        TransferProtocol.writeAccepted(output, deviceName, manifest)

        val selection = TransferProtocol.readSelection(input, files.size)
        val toSend = files.filterIndexed { index, _ -> selection.getOrElse(index) { false } }

        toSend.forEachIndexed { index, offered ->
            onState(SendState.Sending(index, offered.meta.name, toSend.size, 0, offered.meta.size))
            offered.source.open().use { src ->
                TransferProtocol.writeFileBody(output, src, offered.meta.size) { sentSoFar ->
                    onState(SendState.Sending(index, offered.meta.name, toSend.size, sentSoFar, offered.meta.size))
                }
            }
        }
        return SendState.Completed
    }

    /** Interrompt une écoute ou une connexion en cours. */
    fun cancel() {
        cancelled = true
        runCatching { activeSocket?.close() }
        runCatching { serverSocket?.close() }
    }

    /** Referme le port sans marquer la session comme annulée (fin normale). */
    fun stop() {
        runCatching { serverSocket?.close() }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
