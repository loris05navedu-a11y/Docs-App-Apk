package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormulaEngineTest {

    private val cells = mapOf(
        "A1" to "10",
        "A2" to "20",
        "A3" to "30",
        "B1" to "5",
        "B2" to "=A1*2",
        "C1" to "texte"
    )

    private fun eval(formula: String): String =
        FormulaEngine.displayValue("Z1", cells + ("Z1" to formula))

    @Test
    fun `valeur brute renvoyee telle quelle`() {
        assertEquals("texte", FormulaEngine.displayValue("C1", cells))
    }

    @Test
    fun `arithmetique et priorite des operateurs`() {
        assertEquals("14", eval("=2+3*4"))
        assertEquals("20", eval("=(2+3)*4"))
        assertEquals("8", eval("=2^3"))
        assertEquals("2.5", eval("=5/2"))
    }

    @Test
    fun `references de cellules`() {
        assertEquals("15", eval("=A1+B1"))
        assertEquals("20", eval("=B2"))
    }

    @Test
    fun `plages et fonctions`() {
        assertEquals("60", eval("=SOMME(A1:A3)"))
        assertEquals("20", eval("=MOYENNE(A1:A3)"))
        assertEquals("10", eval("=MIN(A1:A3)"))
        assertEquals("30", eval("=MAX(A1:A3)"))
        assertEquals("3", eval("=NB(A1:A3)"))
        assertEquals("65", eval("=SOMME(A1:A3;B1)"))
    }

    @Test
    fun `fonctions mathematiques`() {
        assertEquals("4", eval("=RACINE(16)"))
        assertEquals("7", eval("=ABS(0-7)"))
        assertEquals("3.14", eval("=ARRONDI(3.14159;2)"))
        assertEquals("8", eval("=PUISSANCE(2;3)"))
    }

    @Test
    fun `condition si`() {
        assertEquals("1", eval("=SI(A1>5;1;0)"))
        assertEquals("0", eval("=SI(A1>500;1;0)"))
    }

    @Test
    fun `erreurs et cycles`() {
        assertEquals("#ERREUR", eval("=SOMME("))
        assertEquals("#ERREUR", eval("=1/0"))
        assertEquals("#CYCLE", FormulaEngine.displayValue("Z1", mapOf("Z1" to "=Z1+1")))
    }

    @Test
    fun `etiquettes de colonnes`() {
        assertEquals("A", FormulaEngine.columnLabel(0))
        assertEquals("Z", FormulaEngine.columnLabel(25))
        assertEquals("AA", FormulaEngine.columnLabel(26))
        assertEquals(0, FormulaEngine.columnIndex("A"))
        assertEquals(26, FormulaEngine.columnIndex("AA"))
        assertEquals("B3", FormulaEngine.cellKey(2, 1))
    }
}
