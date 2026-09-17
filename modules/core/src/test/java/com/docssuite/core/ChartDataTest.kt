package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartDataTest {

    private val cells = mapOf(
        "A1" to "Pommes", "B1" to "12",
        "A2" to "Poires", "B2" to "8",
        "A3" to "Cerises", "B3" to "=2*5",
        "A4" to "Prunes", "B4" to "vide"
    )

    @Test
    fun `expansion d'une plage sur une colonne`() {
        assertEquals(listOf("A1", "A2", "A3"), FormulaEngine.expandRange("A1:A3"))
    }

    @Test
    fun `expansion d'une plage rectangulaire ligne par ligne`() {
        assertEquals(
            listOf("A1", "B1", "A2", "B2"),
            FormulaEngine.expandRange("A1:B2")
        )
    }

    @Test
    fun `plage inversee et minuscules acceptees`() {
        assertEquals(listOf("A1", "A2", "A3"), FormulaEngine.expandRange("a3:a1"))
    }

    @Test
    fun `plage invalide renvoie une liste vide`() {
        assertEquals(emptyList<String>(), FormulaEngine.expandRange("bonjour"))
        assertEquals(emptyList<String>(), FormulaEngine.expandRange("A:B"))
    }

    @Test
    fun `les formules sont evaluees et les cellules non numeriques ignorees`() {
        val entries = buildChartEntries("A1:A4", "B1:B4", cells)
        assertEquals(3, entries.size)
        assertEquals(ChartEntry("Pommes", 12.0), entries[0])
        assertEquals(ChartEntry("Cerises", 10.0), entries[2])
    }

    @Test
    fun `le libelle retombe sur la reference si la cellule est vide`() {
        val entries = buildChartEntries("Z1:Z3", "B1:B3", cells)
        assertEquals("B1", entries[0].label)
    }

    @Test
    fun `la queue de distribution est regroupee au lieu de recycler les couleurs`() {
        val many = (1..10).map { ChartEntry("Cat $it", it.toDouble()) }
        val folded = foldEntries(many, 6)
        assertEquals(6, folded.size)
        assertEquals("Autres", folded.last().label)
        // 6 + 7 + 8 + 9 + 10
        assertEquals(40.0, folded.last().value, 0.001)
    }

    @Test
    fun `une liste plus courte que la limite est inchangee`() {
        val few = listOf(ChartEntry("A", 1.0), ChartEntry("B", 2.0))
        assertEquals(few, foldEntries(few, 6))
    }
}
