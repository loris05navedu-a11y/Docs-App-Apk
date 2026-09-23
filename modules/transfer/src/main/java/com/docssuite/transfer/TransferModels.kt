package com.docssuite.transfer

import java.io.InputStream
import java.io.OutputStream

/**
 * Modèle et protocole du transfert d'appareil à appareil. Cette partie ne
 * dépend d'aucune classe Android : elle se teste avec de vraies sockets sur
 * `127.0.0.1`, sans émulateur ni Robolectric.
 */

/** Un fichier tel qu'il apparaît dans le manifeste échangé entre les deux appareils. */
data class TransferFile(val name: String, val size: Long, val mimeType: String)

/**
 * D'où viennent les octets d'un fichier envoyé. Sur l'appareil, c'est une
 * `content://` ouverte via le résolveur de contenu ; dans les tests, un
 * simple tableau d'octets.
 */
fun interface FileSource {
    fun open(): InputStream
}

/** Où vont les octets d'un fichier reçu. */
fun interface FileSink {
    fun open(): OutputStream
}

/** Un fichier à proposer, avec la source qui sait en fournir les octets. */
data class OfferedFile(val meta: TransferFile, val source: FileSource)

/** Étape en cours côté appareil qui envoie. */
sealed interface SendState {
    /** Le serveur écoute ; [port] et [pin] sont à montrer à l'utilisateur. */
    data class Listening(val port: Int, val pin: String) : SendState
    data class PeerConnecting(val port: Int, val pin: String) : SendState
    data class Rejected(val port: Int, val pin: String, val reason: String) : SendState
    data class Sending(val fileIndex: Int, val fileName: String, val fileCount: Int, val bytesSent: Long, val fileSize: Long) : SendState
    data object Completed : SendState
    data class Failed(val message: String) : SendState
    data object Cancelled : SendState
    data object Stopped : SendState
}

/** Étape en cours côté appareil qui reçoit. */
sealed interface ReceiveState {
    data object Connecting : ReceiveState
    /** Attend que le code saisi soit vérifié par l'appareil qui envoie. */
    data object Verifying : ReceiveState
    data class WrongPin(val attemptsLeft: Int) : ReceiveState
    /** Manifeste reçu : l'utilisateur choisit ce qu'il veut. */
    data class ReviewingManifest(val senderName: String, val files: List<TransferFile>) : ReceiveState
    data class Receiving(val fileIndex: Int, val fileName: String, val fileCount: Int, val bytesReceived: Long, val fileSize: Long) : ReceiveState
    data class Completed(val received: List<TransferFile>) : ReceiveState
    data class Failed(val message: String) : ReceiveState
    data object Cancelled : ReceiveState
}

/** Un appareil découvert sur le réseau, prêt à recevoir une connexion. */
data class DiscoveredPeer(val name: String, val host: String, val port: Int)

/** Génère un code à 4 chiffres, affiché sur l'appareil qui envoie. */
fun generatePin(): String = (1000..9999).random().toString()

internal const val PROTOCOL_MAGIC = "DAST1"

/**
 * Port essayé en premier par [TransferServer.bind]. Quand la découverte
 * automatique (NSD) n'a pas lieu — câble en tethering USB, Wi-Fi Direct,
 * adresse saisie à la main — l'autre appareil n'a que l'adresse IP à
 * connaître si le port est toujours celui-ci ; sinon [TransferServer.bind]
 * revient sans bruit à un port libre quelconque.
 */
const val WELL_KNOWN_PORT = 57123

/** Longueur maximale d'un nom d'appareil ou de fichier dans le protocole. */
internal const val MAX_NAME_LENGTH = 512

class ProtocolException(message: String) : Exception(message)
