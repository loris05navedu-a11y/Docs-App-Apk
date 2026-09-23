package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Références absolues et références à une autre feuille. */
class SheetReferenceTest {

    /** La feuille affichée en clés courtes, toutes les feuilles en clés qualifiées. */
    private val cells = mapOf(
        "A1" to "10",
        "A2" to "=Ventes!B2*2",
        "A3" to "=SOMME(Ventes!B1:B3)",
        "A4" to "='Synthèse 2024'!A1+1",
        "A5" to "=\$A\$1+A\$1",
        "A6" to "=\"coût \$\"&A1",
        "FEUILLE1!A1" to "10",
        "VENTES!B1" to "1",
        "VENTES!B2" to "=B1+4",
        "VENTES!B3" to "=Feuille1!A1",
        "SYNTHÈSE 2024!A1" to "=Ventes!B2"
    )

    @Test
    fun `une reference absolue vaut la reference simple`() {
        assertEquals("20", FormulaEngine.displayValue("A5", cells))
    }

    @Test
    fun `le dollar reste dans un texte entre guillemets`() {
        assertEquals("coût \$10", FormulaEngine.displayValue("A6", cells))
    }

    @Test
    fun `une formule d une autre feuille calcule dans sa feuille`() {
        // Ventes!B2 = Ventes!B1 + 4 = 5, et non A1 de la feuille affichée.
        assertEquals("10", FormulaEngine.displayValue("A2", cells))
    }

    @Test
    fun `une plage d une autre feuille se somme`() {
        assertEquals("16", FormulaEngine.displayValue("A3", cells))
    }

    @Test
    fun `un nom de feuille entre apostrophes est reconnu`() {
        assertEquals("6", FormulaEngine.displayValue("A4", cells))
    }

    @Test
    fun `une reference circulaire entre feuilles est signalee`() {
        val loop = mapOf("A1" to "=AUTRE!A1", "AUTRE!A1" to "=Feuille1!A1", "FEUILLE1!A1" to "=AUTRE!A1")
        assertEquals("#CYCLE", FormulaEngine.displayValue("A1", loop))
    }

    @Test
    fun `une feuille inexistante donne une cellule vide`() {
        assertEquals("0", FormulaEngine.displayValue("A1", mapOf("A1" to "=Absente!A1+0")))
    }
}
