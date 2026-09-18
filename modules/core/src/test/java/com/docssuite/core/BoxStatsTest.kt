package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxStatsTest {

    @Test
    fun `resume a cinq nombres sur une serie simple`() {
        val stats = computeBoxStats("Notes", listOf(2.0, 4.0, 6.0, 8.0, 10.0))!!
        assertEquals(5, stats.count)
        assertEquals(2.0, stats.min, 0.001)
        assertEquals(4.0, stats.q1, 0.001)
        assertEquals(6.0, stats.median, 0.001)
        assertEquals(8.0, stats.q3, 0.001)
        assertEquals(10.0, stats.max, 0.001)
        assertTrue(stats.outliers.isEmpty())
    }

    @Test
    fun `mediane interpolee sur un effectif pair`() {
        val stats = computeBoxStats("x", listOf(1.0, 2.0, 3.0, 4.0))!!
        assertEquals(2.5, stats.median, 0.001)
        assertEquals(1.75, stats.q1, 0.001)
        assertEquals(3.25, stats.q3, 0.001)
    }

    @Test
    fun `les valeurs aberrantes sortent des moustaches`() {
        val stats = computeBoxStats("x", listOf(1.0, 2.0, 3.0, 4.0, 5.0, 100.0))!!
        assertTrue("100 doit être aberrante", stats.outliers.contains(100.0))
        assertTrue("la moustache haute exclut l'aberrante", stats.upperWhisker < 100.0)
        assertEquals(100.0, stats.max, 0.001)
    }

    @Test
    fun `une seule valeur donne une boite plate`() {
        val stats = computeBoxStats("x", listOf(7.0))!!
        assertEquals(7.0, stats.min, 0.001)
        assertEquals(7.0, stats.median, 0.001)
        assertEquals(7.0, stats.max, 0.001)
        assertEquals(0.0, stats.interquartileRange, 0.001)
        assertTrue(stats.outliers.isEmpty())
    }

    @Test
    fun `une serie vide ne produit pas de boite`() {
        assertNull(computeBoxStats("x", emptyList()))
        assertNull(computeBoxStats("x", listOf(Double.NaN)))
    }

    @Test
    fun `les libelles repetes produisent une boite par groupe`() {
        val entries = listOf(
            ChartEntry("Classe A", 10.0), ChartEntry("Classe A", 12.0), ChartEntry("Classe A", 14.0),
            ChartEntry("Classe B", 5.0), ChartEntry("Classe B", 7.0)
        )
        val boxes = buildBoxStats(entries)
        assertEquals(2, boxes.size)
        assertEquals("Classe A", boxes[0].label)
        assertEquals(3, boxes[0].count)
        assertEquals(2, boxes[1].count)
    }

    @Test
    fun `des libelles tous distincts donnent une seule boite`() {
        val entries = (1..6).map { ChartEntry("Ligne $it", it.toDouble()) }
        val boxes = buildBoxStats(entries)
        assertEquals(1, boxes.size)
        assertEquals(6, boxes[0].count)
    }

    @Test
    fun `le quantile est borne aux extremites`() {
        val sorted = listOf(1.0, 2.0, 3.0)
        assertEquals(1.0, quantile(sorted, 0.0), 0.001)
        assertEquals(3.0, quantile(sorted, 1.0), 0.001)
        assertEquals(1.0, quantile(sorted, -5.0), 0.001)
        assertEquals(0.0, quantile(emptyList(), 0.5), 0.001)
    }
}
