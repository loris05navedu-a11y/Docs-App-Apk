package com.docssuite.transfer.ui

import android.content.Context
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.docssuite.transfer.DiscoveredPeer
import com.docssuite.transfer.NsdAdvertiser
import com.docssuite.transfer.NsdPeerDiscovery
import com.docssuite.transfer.OfferedFile
import com.docssuite.transfer.ReceiveState
import com.docssuite.transfer.SendState
import com.docssuite.transfer.TransferClient
import com.docssuite.transfer.TransferFile
import com.docssuite.transfer.TransferServer
import com.docssuite.transfer.WELL_KNOWN_PORT
import com.docssuite.transfer.WifiDirectPeers
import com.docssuite.transfer.connectSafely
import com.docssuite.transfer.generatePin
import com.docssuite.transfer.receiveSelectedSafely
import com.docssuite.transfer.sinksInTree
import com.docssuite.transfer.urisToOfferedFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/** Écran affiché dans le module de transfert. */
enum class TransferMode { HOME, SEND, RECEIVE }

/** Comment le côté réception trouve l'appareil qui envoie. */
enum class DiscoveryMethod { SAME_WIFI, WIFI_DIRECT, MANUAL }

/**
 * État et logique du module de transfert, partagés par tous ses écrans.
 * Chaque opération réseau tourne sur un fil d'arrière-plan ; les rappels de
 * progression écrivent directement dans les propriétés `mutableStateOf`, ce
 * qui est sûr depuis n'importe quel fil et déclenche la recomposition.
 */
class TransferState(private val context: Context, private val scope: CoroutineScope) {

    var mode by mutableStateOf(TransferMode.HOME)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun report(text: String?) {
        message = text
    }

    fun dismissMessage() {
        message = null
    }

    private val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Appareil" }

    // -------------------------------------------------------------- envoi

    val filesToSend = mutableStateListOf<OfferedFile>()
    var sendState by mutableStateOf<SendState?>(null)
        private set
    var wifiDirectVisible by mutableStateOf(false)
        private set

    private var server: TransferServer? = null
    private val sendAdvertiser = NsdAdvertiser(context)
    private val sendWifiDirect = WifiDirectPeers(context)

    fun goToSend() {
        reset()
        mode = TransferMode.SEND
    }

    /** Fichiers choisis par l'utilisateur : la liste devient le manifeste proposé. */
    fun pickFilesForSend(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val offered = runCatching { urisToOfferedFiles(context, uris) }
                .getOrElse {
                    report(it.message ?: "Certains fichiers n'ont pas pu être lus")
                    return@launch
                }
            filesToSend.clear()
            filesToSend.addAll(offered)
            startServing()
        }
    }

    private fun startServing() {
        val srv = TransferServer(deviceName, generatePin(), filesToSend.toList())
        server = srv
        scope.launch(Dispatchers.IO) {
            val port = srv.bind()
            runCatching { sendAdvertiser.advertise(deviceName, port) { report(it) } }
            srv.serve { state -> sendState = state }
            sendAdvertiser.stop()
            sendWifiDirect.stopDiscovery()
        }
    }

    /**
     * Rend cet appareil visible en Wi-Fi Direct aussi : utile sans routeur, ou
     * quand les deux appareils ne sont pas sur le même réseau Wi-Fi.
     */
    fun makeVisibleOnWifiDirect() {
        wifiDirectVisible = true
        sendWifiDirect.startDiscovery(onPeersChanged = {}, onFailure = { report(it) })
    }

    fun cancelSend() {
        server?.cancel()
        sendAdvertiser.stop()
        sendWifiDirect.stopDiscovery()
    }

    // ------------------------------------------------------------ réception

    var discoveryMethod by mutableStateOf<DiscoveryMethod?>(null)
        private set
    val discoveredPeers = mutableStateListOf<DiscoveredPeer>()
    val wifiDirectDevices = mutableStateListOf<WifiP2pDevice>()
    var manualHost by mutableStateOf("")
    var manualPort by mutableStateOf(WELL_KNOWN_PORT.toString())
    var pendingPeer by mutableStateOf<DiscoveredPeer?>(null)
        private set
    var pinInput by mutableStateOf("")
    var receiveState by mutableStateOf<ReceiveState?>(null)
        private set
    var manifest by mutableStateOf<List<TransferFile>>(emptyList())
        private set
    val selected = mutableStateListOf<Boolean>()
    var senderName by mutableStateOf("")
        private set

    private val nsdDiscovery = NsdPeerDiscovery(context)
    private val receiveWifiDirect = WifiDirectPeers(context)
    private var client: TransferClient? = null

    fun goToReceive() {
        reset()
        mode = TransferMode.RECEIVE
    }

    fun chooseDiscovery(method: DiscoveryMethod) {
        stopDiscovery()
        discoveredPeers.clear()
        wifiDirectDevices.clear()
        discoveryMethod = method
        when (method) {
            DiscoveryMethod.SAME_WIFI -> nsdDiscovery.start(
                onPeerFound = { peer ->
                    if (discoveredPeers.none { it.host == peer.host && it.port == peer.port }) {
                        discoveredPeers.add(peer)
                    }
                },
                onPeerLost = { name -> discoveredPeers.removeAll { it.name == name } },
                onFailure = { report(it) }
            )
            DiscoveryMethod.WIFI_DIRECT -> receiveWifiDirect.startDiscovery(
                onPeersChanged = { devices ->
                    wifiDirectDevices.clear()
                    wifiDirectDevices.addAll(devices)
                },
                onConnected = { isGroupOwner, address ->
                    if (!isGroupOwner && address != null) {
                        pendingPeer = DiscoveredPeer("Appareil Wi-Fi Direct", address, WELL_KNOWN_PORT)
                    }
                },
                onFailure = { report(it) }
            )
            DiscoveryMethod.MANUAL -> {}
        }
    }

    fun selectDiscoveredPeer(peer: DiscoveredPeer) {
        pinInput = ""
        pendingPeer = peer
    }

    fun selectWifiDirectDevice(device: WifiP2pDevice) {
        receiveWifiDirect.connect(device) { ok, error -> if (!ok) report(error) }
    }

    fun connectManually() {
        val host = manualHost.trim()
        if (host.isEmpty()) {
            report("Adresse IP manquante")
            return
        }
        val port = manualPort.trim().toIntOrNull() ?: WELL_KNOWN_PORT
        pinInput = ""
        pendingPeer = DiscoveredPeer(host, host, port)
    }

    fun cancelPendingPeer() {
        pendingPeer = null
        pinInput = ""
    }

    fun confirmPin() {
        val peer = pendingPeer ?: return
        stopDiscovery()
        scope.launch(Dispatchers.IO) {
            val c = TransferClient()
            client = c
            val outcome = c.connectSafely(peer.host, peer.port, deviceName, pinInput) { state -> receiveState = state }
            if (outcome is ReceiveState.ReviewingManifest) {
                senderName = outcome.senderName
                manifest = outcome.files
                selected.clear()
                selected.addAll(outcome.files.map { true })
            }
        }
    }

    fun toggleSelected(index: Int) {
        if (index in selected.indices) selected[index] = !selected[index]
    }

    fun selectAll(value: Boolean) {
        for (i in selected.indices) selected[i] = value
    }

    /** Le dossier choisi pour la réception ; lance la réception des fichiers cochés. */
    fun receiveInto(treeUri: Uri) {
        val c = client ?: return
        val files = manifest
        val choices = selected.toList()
        scope.launch(Dispatchers.IO) {
            val toReceive = files.filterIndexed { index, _ -> choices.getOrElse(index) { false } }
            val sinks = runCatching { sinksInTree(context, treeUri, toReceive) }
                .getOrElse {
                    receiveState = ReceiveState.Failed(it.message ?: "Dossier de destination inaccessible")
                    return@launch
                }
            c.receiveSelectedSafely(
                files, choices,
                sinkFor = { file -> sinks[file.name] ?: throw IOException("« ${file.name} » n'a pas été préparé") }
            ) { state -> receiveState = state }
            c.close()
        }
    }

    fun cancelReceive() {
        client?.cancel()
        stopDiscovery()
    }

    private fun stopDiscovery() {
        nsdDiscovery.stop()
        receiveWifiDirect.stopDiscovery()
    }

    // --------------------------------------------------------------- commun

    /** Revient à l'accueil du module et libère tout ce qui tourne encore. */
    fun reset() {
        server?.cancel()
        sendAdvertiser.stop()
        sendWifiDirect.stopDiscovery()
        client?.close()
        client = null
        stopDiscovery()

        mode = TransferMode.HOME
        filesToSend.clear()
        sendState = null
        wifiDirectVisible = false
        discoveryMethod = null
        discoveredPeers.clear()
        wifiDirectDevices.clear()
        manualHost = ""
        manualPort = WELL_KNOWN_PORT.toString()
        pendingPeer = null
        pinInput = ""
        receiveState = null
        manifest = emptyList()
        selected.clear()
        senderName = ""
        message = null
    }

    /** À appeler quand l'écran se ferme, pour ne rien laisser tourner en arrière-plan. */
    fun dispose() {
        server?.cancel()
        sendAdvertiser.stop()
        sendWifiDirect.stopDiscovery()
        client?.close()
        stopDiscovery()
    }
}

@Composable
fun rememberTransferState(): TransferState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { TransferState(context.applicationContext, scope) }
}
