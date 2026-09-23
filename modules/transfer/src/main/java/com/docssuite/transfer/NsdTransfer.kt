package com.docssuite.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

/**
 * Découverte automatique sur un même réseau Wi-Fi (mDNS/NSD) : les deux
 * appareils se voient dans une liste, sans qu'on ait à se dire une adresse
 * IP. Sur un lien sans ce mécanisme (tethering USB, Wi-Fi Direct), la saisie
 * manuelle de l'adresse reste le repli — voir [WELL_KNOWN_PORT].
 */
internal const val NSD_SERVICE_TYPE = "_docssuitetransfer._tcp."

/** Rend cet appareil visible pendant qu'il attend une connexion. */
class NsdAdvertiser(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun advertise(deviceName: String, port: Int, onFailure: (String) -> Unit = {}) {
        stop()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName.take(60).ifBlank { "Appareil" }
            serviceType = NSD_SERVICE_TYPE
            setPort(port)
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                onFailure("Visibilité réseau indisponible (code $errorCode)")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = registrationListener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
            .onFailure { onFailure(it.message ?: "Visibilité réseau indisponible") }
    }

    /** Referme la visibilité ; à appeler quand l'écoute s'arrête ou se termine. */
    fun stop() {
        listener?.let { runCatching { nsdManager.unregisterService(it) } }
        listener = null
    }
}

/**
 * Cherche les autres appareils visibles sur le réseau. Les résolutions
 * (adresse IP + port de chaque service trouvé) sont mises en file : l'API
 * Android refuse une deuxième résolution tant que la précédente n'a pas
 * répondu.
 */
class NsdPeerDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveLock = Any()
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start(
        onPeerFound: (DiscoveredPeer) -> Unit,
        onPeerLost: (String) -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        stop()
        multicastLock = runCatching {
            wifiManager?.createMulticastLock("docssuite-transfer")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.trimEnd('.').equals(NSD_SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) return
                queueResolve(service, onPeerFound)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                onPeerLost(service.serviceName)
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onFailure("Découverte réseau indisponible (code $errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onFailure(it.message ?: "Découverte réseau indisponible") }
    }

    private fun queueResolve(service: NsdServiceInfo, onPeerFound: (DiscoveredPeer) -> Unit) {
        val shouldStart = synchronized(resolveLock) {
            pendingResolves.add(service)
            if (resolving) false else { resolving = true; true }
        }
        if (shouldStart) resolveNext(onPeerFound)
    }

    private fun resolveNext(onPeerFound: (DiscoveredPeer) -> Unit) {
        val next = synchronized(resolveLock) {
            if (pendingResolves.isEmpty()) {
                resolving = false
                null
            } else {
                pendingResolves.poll()
            }
        } ?: return

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolveNext(onPeerFound)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null) onPeerFound(DiscoveredPeer(info.serviceName, host, info.port))
                resolveNext(onPeerFound)
            }
        }
        runCatching { nsdManager.resolveService(next, resolveListener) }
            .onFailure { resolveNext(onPeerFound) }
    }

    /** Arrête la recherche ; à appeler quand l'écran de découverte se ferme. */
    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        synchronized(resolveLock) {
            pendingResolves.clear()
            resolving = false
        }
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
    }
}
