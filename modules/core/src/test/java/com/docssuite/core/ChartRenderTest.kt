package com.docssuite.core

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rend réellement chaque type de graphique : c'est le seul moyen d'attraper
 * les plantages de dessin (plage vide, valeurs identiques, négatives…).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChartRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /** Un seul setContent par test : on fait ensuite varier le type via l'état. */
    private fun renderEveryType(entries: List<ChartEntry>) {
        var type by mutableStateOf(ChartType.values().first())
        compose.setContent {
            MaterialTheme {
                ChartView(
                    type = type,
                    title = "Test",
                    entries = entries,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        compose.waitForIdle()
        ChartType.values().forEach { next ->
            compose.runOnIdle { type = next }
            compose.waitForIdle()
        }
    }

    @Test
    fun `aucune donnee ne fait pas planter`() {
        renderEveryType(emptyList())
    }

    @Test
    fun `une seule valeur ne fait pas planter`() {
        renderEveryType(listOf(ChartEntry("Seule", 42.0)))
    }

    @Test
    fun `des valeurs toutes nulles ne font pas planter`() {
        renderEveryType(listOf(ChartEntry("A", 0.0), ChartEntry("B", 0.0)))
    }

    @Test
    fun `des valeurs negatives ne font pas planter`() {
        renderEveryType(listOf(ChartEntry("A", -10.0), ChartEntry("B", 25.0), ChartEntry("C", -3.0)))
    }

    @Test
    fun `des valeurs identiques ne font pas planter`() {
        renderEveryType(listOf(ChartEntry("A", 7.0), ChartEntry("B", 7.0), ChartEntry("C", 7.0)))
    }

    @Test
    fun `beaucoup de categories ne font pas planter`() {
        renderEveryType((1..40).map { ChartEntry("Cat $it", it.toDouble()) })
    }

    @Test
    fun `des valeurs minuscules ne bloquent pas le calcul des graduations`() {
        renderEveryType(listOf(ChartEntry("A", 1e-9), ChartEntry("B", 2e-9)))
    }

    @Test
    fun `des valeurs enormes ne bloquent pas le calcul des graduations`() {
        renderEveryType(listOf(ChartEntry("A", 1e12), ChartEntry("B", 5e12)))
    }

    @Test
    fun `un libelle vide ou tres long ne fait pas planter`() {
        renderEveryType(
            listOf(
                ChartEntry("", 3.0),
                ChartEntry("Un libellé vraiment très long qui dépasse largement", 9.0)
            )
        )
    }
}
