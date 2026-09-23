package com.docssuite.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager

/**
 * Connexion directe entre deux appareils, sans routeur ni réseau Wi-Fi
 * partagé : Android forme un petit groupe rien qu'entre les deux. L'un
 * devient l'« hôte » du groupe ; une fois cette adresse connue (via
 * [requestConnectionInfo]), le même protocole de transfert tourne dessus
 * exactement comme sur un Wi-Fi classique, sur [WELL_KNOWN_PORT].
 */
class WifiDirectPeers(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(appContext, appContext.mainLooper, null)
    private var receiver: BroadcastReceiver? = null

    val isSupported: Boolean get() = manager != null && channel != null

    /**
     * Écoute les appareils Wi-Fi Direct à proximité. [onPeersChanged] est
     * rappelé avec la liste complète à chaque changement, tant que
     * [stopDiscovery] n'a pas été appelé. [onConnected] est rappelé dès
     * qu'un groupe se forme (que ce soit après [connect] ou parce que
     * l'autre appareil a accepté la demande) avec qui en est l'hôte.
     */
    fun startDiscovery(
        onPeersChanged: (List<WifiP2pDevice>) -> Unit,
        onConnected: (isGroupOwner: Boolean, groupOwnerAddress: String?) -> Unit = { _, _ -> },
        onFailure: (String) -> Unit = {}
    ) {
        val mgr = manager ?: return onFailure("Wi-Fi Direct indisponible sur cet appareil")
        val ch = channel ?: return onFailure("Wi-Fi Direct indisponible sur cet appareil")

        stopDiscovery()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> runCatching {
                        mgr.requestPeers(ch) { list: WifiP2pDeviceList -> onPeersChanged(list.deviceList.toList()) }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> runCatching {
                        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                            if (info.groupFormed) onConnected(info.isGroupOwner, info.groupOwnerAddress?.hostAddress)
                        }
                    }
                }
            }
        }
        receiver = br
        appContext.registerReceiver(br, filter)

        // Un appareil sans matériel Wi-Fi Direct actif peut lever une
        // exception ici plutôt que d'appeler onFailure : ce n'est pas un
        // bug de l'appelant, juste une fonctionnalité absente.
        runCatching {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    onFailure("Découverte Wi-Fi Direct impossible (code $reason)")
                }
            })
        }.onFailure { onFailure(it.message ?: "Wi-Fi Direct indisponible sur cet appareil") }
    }

    /** Demande la connexion à un appareil trouvé par [startDiscovery]. */
    fun connect(device: WifiP2pDevice, onResult: (Boolean, String?) -> Unit) {
        val mgr = manager ?: return onResult(false, "Wi-Fi Direct indisponible sur cet appareil")
        val ch = channel ?: return onResult(false, "Wi-Fi Direct indisponible sur cet appareil")
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        runCatching {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onResult(true, null)
                override fun onFailure(reason: Int) = onResult(false, "Connexion Wi-Fi Direct refusée (code $reason)")
            })
        }.onFailure { onResult(false, it.message ?: "Wi-Fi Direct indisponible sur cet appareil") }
    }

    /**
     * Une fois la connexion établie (l'appelant écoute
     * `WIFI_P2P_CONNECTION_CHANGED_ACTION` pour le savoir) : qui est l'hôte
     * du groupe, et son adresse — celle à laquelle l'autre appareil doit se
     * connecter.
     */
    fun requestConnectionInfo(onInfo: (isGroupOwner: Boolean, groupOwnerAddress: String?) -> Unit) {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                onInfo(info.groupFormed && info.isGroupOwner, info.groupOwnerAddress?.hostAddress)
            }
        }
    }

    /** Arrête la recherche de pairs ; n'annule pas une connexion déjà établie. */
    fun stopDiscovery() {
        manager?.let { mgr -> channel?.let { ch -> runCatching { mgr.stopPeerDiscovery(ch, null) } } }
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    /** Referme le groupe formé, une fois le transfert terminé. */
    fun disconnect() {
        manager?.let { mgr -> channel?.let { ch -> runCatching { mgr.removeGroup(ch, null) } } }
    }
}
