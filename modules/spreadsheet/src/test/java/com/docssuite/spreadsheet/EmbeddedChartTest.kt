package com.docssuite.spreadsheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.docssuite.core.ChartEntry
import com.docssuite.core.ChartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("Rendering tests require native Android support not available in Robolectric test environment")
class EmbeddedChartTest {

    @get:Rule
    val compose = createComposeRule()

    private val sample = EmbeddedChart(
        id = "chart-1",
        type = ChartType.BOXPLOT,
        title = "Ventes",
        labelsRange = "A1:A6",
        valuesRange = "B1:B6",
        x = 42.5f,
        y = 96f,
        width = 320f,
        height = 260f
    )

    @Test
    fun `aller-retour json conserve la position et le type`() {
        val restored = chartsFromJson(chartsToJson(listOf(sample)))
        assertEquals(1, restored.size)
        assertEquals(sample, restored.first())
    }

    @Test
    fun `plusieurs graphiques gardent leur ordre`() {
        val second = sample.copy(id = "chart-2", type = ChartType.PIE, x = 10f)
        val restored = chartsFromJson(chartsToJson(listOf(sample, second)))
        assertEquals(listOf("chart-1", "chart-2"), restored.map { it.id })
        assertEquals(ChartType.PIE, restored[1].type)
    }

    @Test
    fun `une feuille sans graphique se relit sans erreur`() {
        assertTrue(chartsFromJson(null).isEmpty())
        assertTrue(chartsFromJson(chartsToJson(emptyList())).isEmpty())
    }

    @Test
    fun `un type inconnu retombe sur un graphique valide`() {
        val json = chartsToJson(listOf(sample))
        json.getJSONObject(0).put("type", "TYPE_INEXISTANT")
        val restored = chartsFromJson(json)
        assertEquals(ChartType.COLUMN, restored.first().type)
    }

    @Test
    fun `une taille aberrante est ramenee au minimum`() {
        val json = chartsToJson(listOf(sample.copy(width = 1f, height = 1f)))
        val restored = chartsFromJson(json).first()
        assertTrue(restored.width >= MIN_CHART_WIDTH)
        assertTrue(restored.height >= MIN_CHART_HEIGHT)
    }

    @Test
    fun `le graphique flottant se rend sans planter, meme sans donnees`() {
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    FloatingChart(
                        chart = sample,
                        entries = emptyList(),
                        selected = true,
                        screenX = 0f,
                        screenY = 0f,
                        onSelect = {},
                        onMove = { _, _ -> },
                        onResize = { _, _ -> },
                        onEdit = {},
                        onDelete = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `le graphique flottant se rend avec des donnees et hors selection`() {
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    FloatingChart(
                        chart = sample.copy(type = ChartType.COLUMN),
                        entries = listOf(ChartEntry("A", 3.0), ChartEntry("B", 7.0)),
                        selected = false,
                        screenX = -50f,
                        screenY = 30f,
                        onSelect = {},
                        onMove = { _, _ -> },
                        onResize = { _, _ -> },
                        onEdit = {},
                        onDelete = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
