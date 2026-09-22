package com.docssuite.spreadsheet

import org.junit.Assert.assertEquals
import org.junit.Test

/** Insertion, suppression et tri, y compris l'effet sur les formules. */
class SheetOpsTest {

    private val cells = mapOf(
        "A1" to "10", "B1" to "Alpha",
        "A2" to "30", "B2" to "Charlie",
        "A3" to "20", "B3" to "Bravo",
        "C1" to "=SOMME(A1:A3)"
    )

    private fun ops(map: Map<String, String>) = SheetOps.insertRow(map, emptyMap<String, Int>(), 1)

    @Test
    fun `inserer une ligne decale le contenu du dessous`() {
        val result = ops(cells)
        assertEquals("10", result.cells["A1"])
        assertEquals(null, result.cells["A2"])
        assertEquals("30", result.cells["A3"])
        assertEquals("20", result.cells["A4"])
    }

    @Test
    fun `inserer une ligne suit les references des formules`() {
        val result = ops(cells)
        // La plage doit s'étendre : les données occupent maintenant A1 à A4.
        assertEquals("=SOMME(A1:A4)", result.cells["C1"])
    }

    @Test
    fun `supprimer une ligne remonte le contenu et signale les references perdues`() {
        val result = SheetOps.deleteRow(cells, emptyMap<String, Int>(), 1)
        assertEquals("10", result.cells["A1"])
        assertEquals("20", result.cells["A2"])
        assertEquals("=SOMME(A1:A2)", result.cells["C1"])

        val pointing = mapOf("A2" to "5", "B1" to "=A2*2")
        val removed = SheetOps.deleteRow(pointing, emptyMap<String, Int>(), 1)
        assertEquals("=#REF!*2", removed.cells["B1"])
    }

    @Test
    fun `inserer et supprimer une colonne deplacent les cellules`() {
        val inserted = SheetOps.insertColumn(cells, emptyMap<String, Int>(), 1)
        assertEquals("10", inserted.cells["A1"])
        assertEquals("Alpha", inserted.cells["C1"])
        assertEquals("=SOMME(A1:A3)", inserted.cells["D1"])

        // La colonne B disparaît : l'ancienne C prend sa place, et sa formule
        // ne vise que la colonne A, donc elle ne bouge pas.
        val deleted = SheetOps.deleteColumn(cells, emptyMap<String, Int>(), 1)
        assertEquals("10", deleted.cells["A1"])
        assertEquals("=SOMME(A1:A3)", deleted.cells["B1"])
        assertEquals(null, deleted.cells["C1"])
    }

    @Test
    fun `le texte entre guillemets n est pas pris pour une reference`() {
        val adjusted = SheetOps.adjustFormula("""=SI(A2>1;"A2 est grand";A3)""", 1, 1, -1, 0)
        assertEquals("""=SI(A3>1;"A2 est grand";A4)""", adjusted)
    }

    @Test
    fun `un nom de fonction n est pas confondu avec une reference`() {
        assertEquals("=SOMME(A2:A4)", SheetOps.adjustFormula("=SOMME(A1:A3)", 0, 1, -1, 0))
        assertEquals("=LOG10(A2)", SheetOps.adjustFormula("=LOG10(A1)", 0, 1, -1, 0))
    }

    @Test
    fun `le tri classe les nombres puis le texte puis les vides`() {
        val sorted = SheetOps.sortByColumn(
            cells, emptyMap<String, Int>(),
            column = 0, firstRow = 0, lastRow = 2, columns = 2, ascending = true
        )
        assertEquals("10", sorted.cells["A1"])
        assertEquals("20", sorted.cells["A2"])
        assertEquals("30", sorted.cells["A3"])
        // Chaque ligne emporte ses voisines de droite.
        assertEquals("Alpha", sorted.cells["B1"])
        assertEquals("Bravo", sorted.cells["B2"])
        assertEquals("Charlie", sorted.cells["B3"])
    }

    @Test
    fun `le tri descendant inverse l ordre`() {
        val sorted = SheetOps.sortByColumn(
            cells, emptyMap<String, Int>(),
            column = 0, firstRow = 0, lastRow = 2, columns = 2, ascending = false
        )
        assertEquals("30", sorted.cells["A1"])
        assertEquals("10", sorted.cells["A3"])
    }

    @Test
    fun `le tri place les cases vides en dernier`() {
        val sparse = mapOf("A1" to "b", "A2" to "", "A3" to "a")
        val sorted = SheetOps.sortByColumn(
            sparse, emptyMap<String, Int>(),
            column = 0, firstRow = 0, lastRow = 2, columns = 1, ascending = true
        )
        assertEquals("a", sorted.cells["A1"])
        assertEquals("b", sorted.cells["A2"])
        assertEquals("", sorted.cells["A3"])
    }

    @Test
    fun `les mises en forme suivent les cellules`() {
        val formats = mapOf("A3" to 7)
        val result = SheetOps.insertRow(cells, formats, 1)
        assertEquals(7, result.formats["A4"])
        assertEquals(null, result.formats["A3"])
    }
}
