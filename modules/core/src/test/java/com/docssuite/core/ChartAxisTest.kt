package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAxisTest {

    @Test
    fun `l'axe part de zero et couvre le maximum`() {
        val axis = niceAxis(0.0, 100.0)
        assertEquals(0.0, axis.min, 0.001)
        assertTrue("l'axe doit contenir la valeur max", axis.max >= 100.0)
        assertTrue("le pas doit être positif", axis.step > 0)
    }

    @Test
    fun `des valeurs negatives etendent l'axe des deux cotes`() {
        val axis = niceAxis(-30.0, 70.0)
        assertTrue(axis.min <= -30.0)
        assertTrue(axis.max >= 70.0)
        assertTrue("zéro doit rester sur l'axe", axis.min <= 0.0 && axis.max >= 0.0)
    }

    @Test
    fun `une serie entierement nulle donne un axe utilisable`() {
        val axis = niceAxis(0.0, 0.0)
        assertTrue(axis.max > axis.min)
        assertTrue(axis.step > 0)
    }

    @Test
    fun `une serie plate ne produit pas un axe degenere`() {
        val axis = niceAxis(0.0, 42.0)
        assertTrue(axis.max > axis.min)
        assertTrue(axis.step > 0)
    }

    @Test
    fun `les bornes restent finies sur de tres grandes valeurs`() {
        val axis = niceAxis(0.0, 9_876_543_210.0)
        assertTrue(axis.max.isFinite())
        assertTrue(axis.step.isFinite())
        assertTrue(axis.max >= 9_876_543_210.0)
    }

    @Test
    fun `les bornes restent finies sur de tres petites valeurs`() {
        val axis = niceAxis(0.0, 0.004)
        assertTrue(axis.max.isFinite())
        assertTrue(axis.step > 0)
        assertTrue(axis.max >= 0.004)
    }

    @Test
    fun `le nombre de graduations reste raisonnable`() {
        listOf(1.0, 7.0, 55.0, 230.0, 1500.0, 99000.0).forEach { max ->
            val axis = niceAxis(0.0, max)
            val ticks = ((axis.max - axis.min) / axis.step).toInt() + 1
            assertTrue("$max produit $ticks graduations", ticks in 2..12)
        }
    }
}
