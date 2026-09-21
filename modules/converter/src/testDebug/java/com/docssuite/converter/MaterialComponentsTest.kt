package com.docssuite.converter

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.converter.ui.ConverterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Garde-fou sur l'accord des versions de Compose.
 *
 * Material3 appelle des fonctions internes des bibliothèques d'animation et de
 * fondation. Un jeu de versions dépareillé compile sans une remarque, puis
 * lève un `NoSuchMethodError` au premier rendu du composant concerné : c'est
 * ainsi qu'un indicateur de progression a fait tomber l'application au moment
 * où l'utilisateur choisissait un fichier. Rendre ici les composants dont
 * l'application dépend fait apparaître le désaccord à la construction.
 */
@RunWith(AndroidJUnit4::class)
class MaterialComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `les composants Material employes par l application se rendent`() {
        compose.setContent {
            ConverterTheme {
                Column {
                    // Les deux variantes animées : ce sont elles qui passent
                    // par `keyframes`, là où le désaccord s'est manifesté.
                    CircularProgressIndicator()
                    CircularProgressIndicator(progress = 0.4f)
                    LinearProgressIndicator()
                    LinearProgressIndicator(progress = 0.4f)

                    Switch(checked = true, onCheckedChange = {})
                    Slider(value = 90f, onValueChange = {}, valueRange = 40f..100f, steps = 11)
                    Card { Text("carte") }
                    NavigationBar {
                        NavigationBarItem(
                            selected = true,
                            onClick = {},
                            icon = { Text("A") },
                            label = { Text("Accueil") }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
