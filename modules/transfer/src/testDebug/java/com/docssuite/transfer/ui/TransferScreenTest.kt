package com.docssuite.transfer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Le parcours à l'écran jusqu'à la saisie de l'adresse ; aucune opération
 * réseau réelle n'a lieu avant la validation du code (voir
 * [LoopbackTransferTest] pour le protocole lui-même sur de vraies sockets).
 * La boîte de dialogue du code elle-même — une `AlertDialog`, donc une
 * fenêtre séparée — n'est pas exercée ici : Robolectric ne synchronise pas
 * son horloge de composition avec celle de la fenêtre principale, ce qui
 * bloque indéfiniment l'attente d'inactivité plutôt que de révéler un vrai
 * problème.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class TransferScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `l accueil propose envoyer et recevoir puis revient au clic sur retour`() {
        compose.setContent { MaterialTheme { TransferScreen(onBack = {}) } }

        compose.onNodeWithText("Envoyer").assertIsDisplayed()
        compose.onNodeWithText("Recevoir").assertIsDisplayed()

        compose.onNodeWithText("Envoyer").performClick()
        compose.onNodeWithText("Choisis les fichiers à envoyer").assertIsDisplayed()

        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Recevoir").assertIsDisplayed()
    }

    @Test
    fun `recevoir par adresse manuelle accepte une IP et un port`() {
        compose.setContent { MaterialTheme { TransferScreen(onBack = {}) } }

        compose.onNodeWithText("Recevoir").performClick()
        compose.onNodeWithText("Câble ou adresse manuelle").performClick()

        compose.onNodeWithText("Adresse IP de l'autre appareil").assertIsDisplayed()
        compose.onNodeWithText("Adresse IP de l'autre appareil").performTextInput("192.168.1.42")
        compose.onNodeWithText("192.168.1.42").assertIsDisplayed()
        compose.onNodeWithText("Se connecter").assertIsDisplayed()
    }
}
