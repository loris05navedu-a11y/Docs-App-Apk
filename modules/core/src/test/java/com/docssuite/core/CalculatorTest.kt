package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {

    private val cells = mapOf("A1" to "10", "A2" to "4", "B1" to "=A1*3")

    @Test
    fun `calcul simple`() {
        assertEquals(7.0, FormulaEngine.evaluateExpression("3+4")!!, 0.001)
        assertEquals(14.0, FormulaEngine.evaluateExpression("2+3*4")!!, 0.001)
        assertEquals(2.5, FormulaEngine.evaluateExpression("5/2")!!, 0.001)
    }

    @Test
    fun `le signe egal en tete est accepte`() {
        assertEquals(7.0, FormulaEngine.evaluateExpression("=3+4")!!, 0.001)
    }

    @Test
    fun `les references de cellules sont resolues`() {
        assertEquals(14.0, FormulaEngine.evaluateExpression("A1+A2", cells)!!, 0.001)
        assertEquals(30.0, FormulaEngine.evaluateExpression("B1", cells)!!, 0.001)
    }

    @Test
    fun `les fonctions sont disponibles dans la calculatrice`() {
        assertEquals(4.0, FormulaEngine.evaluateExpression("RACINE(16)")!!, 0.001)
        assertEquals(14.0, FormulaEngine.evaluateExpression("SOMME(A1;A2)", cells)!!, 0.001)
    }

    @Test
    fun `une expression invalide renvoie null au lieu de planter`() {
        assertNull(FormulaEngine.evaluateExpression(""))
        assertNull(FormulaEngine.evaluateExpression("   "))
        assertNull(FormulaEngine.evaluateExpression("3+"))
        assertNull(FormulaEngine.evaluateExpression("SOMME("))
        assertNull(FormulaEngine.evaluateExpression("1/0"))
        assertNull(FormulaEngine.evaluateExpression("((("))
    }

    @Test
    fun `quartile et ecart type`() {
        val data = mapOf(
            "A1" to "2", "A2" to "4", "A3" to "6", "A4" to "8", "A5" to "10"
        )
        assertEquals(4.0, FormulaEngine.evaluateExpression("QUARTILE(A1:A5;1)", data)!!, 0.001)
        assertEquals(6.0, FormulaEngine.evaluateExpression("QUARTILE(A1:A5;2)", data)!!, 0.001)
        assertEquals(8.0, FormulaEngine.evaluateExpression("QUARTILE(A1:A5;3)", data)!!, 0.001)
        assertEquals(3.162, FormulaEngine.evaluateExpression("ECARTYPE(A1:A5)", data)!!, 0.01)
    }
}
