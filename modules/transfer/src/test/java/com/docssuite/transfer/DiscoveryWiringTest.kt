package com.docssuite.transfer

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ces classes s'appuient sur des services système (NSD, Wi-Fi Direct) que
 * Robolectric ne simule que partiellement : point de vérifier ici qu'un pair
 * est réellement trouvé (cela suppose un vrai réseau), mais que le câblage —
 * obtenir le service, enregistrer/désenregistrer sans lever d'exception,
 * démarrer puis arrêter proprement — tient.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiscoveryWiringTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `l annonce NSD demarre puis s arrete sans exception`() {
        val advertiser = NsdAdvertiser(context)
        advertiser.advertise("Téléphone de Loris", 12345)
        advertiser.stop()
        // Un second arrêt (déjà arrêté) ne doit pas non plus lever d'exception.
        advertiser.stop()
    }

    @Test
    fun `la decouverte NSD demarre puis s arrete sans exception`() {
        val discovery = NsdPeerDiscovery(context)
        discovery.start(onPeerFound = {}, onPeerLost = {})
        discovery.stop()
        discovery.stop()
    }

    @Test
    fun `wifi direct signale son indisponibilite plutot que de planter`() {
        val peers = WifiDirectPeers(context)
        // Robolectric ne fournit pas de service Wi-Fi Direct par défaut :
        // le câblage doit se dégrader proprement plutôt que planter.
        var failureMessage: String? = null
        peers.startDiscovery(onPeersChanged = {}, onFailure = { failureMessage = it })
        peers.stopDiscovery()
        if (!peers.isSupported) {
            assert(failureMessage != null) { "Un échec de découverte doit être signalé quand Wi-Fi Direct est indisponible" }
        }
    }
}
