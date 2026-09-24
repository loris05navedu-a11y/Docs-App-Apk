package com.docssuite.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGeometryTest {

    private val tilted = Quad(Pt(120f, 40f), Pt(520f, 90f), Pt(480f, 610f), Pt(60f, 560f))

    @Test
    fun `des coins donnes dans le desordre sont remis dans l ordre`() {
        val shuffled = listOf(tilted.bottomRight, tilted.topLeft, tilted.bottomLeft, tilted.topRight)
        assertEquals(tilted, orderCorners(shuffled))
    }

    @Test
    fun `une feuille presque a 45 degres garde quatre coins distincts`() {
        val diamond = listOf(Pt(300f, 10f), Pt(590f, 290f), Pt(310f, 590f), Pt(12f, 300f))
        val quad = orderCorners(diamond.reversed())
        assertEquals(4, quad.points.toSet().size)
        assertTrue(quad.isConvex())
    }

    @Test
    fun `un quadrilatere croise est refuse`() {
        assertTrue(tilted.isConvex())
        val crossed = tilted.copy(topRight = tilted.bottomRight, bottomRight = tilted.topRight)
        assertFalse(crossed.isConvex())
    }

    @Test
    fun `la taille de sortie suit les cotes les plus longs`() {
        val rect = Quad.inset(400f, 300f)
        assertEquals(400 to 300, outputSize(rect))
    }

    @Test
    fun `une tres grande feuille est ramenee sous la limite en gardant ses proportions`() {
        val huge = Quad.inset(5000f, 2500f)
        assertEquals(2480 to 1240, outputSize(huge, maxLongSide = 2480))
    }

    @Test
    fun `un coin deplace hors de l image y est ramene`() {
        val moved = Quad.inset(100f, 80f).withCorner(2, Pt(150f, -20f)).clampedTo(100f, 80f)
        assertEquals(Pt(100f, 0f), moved.bottomRight)
    }

    @Test
    fun `l aire d un rectangle est largeur fois hauteur`() {
        assertEquals(1200f, Quad.inset(40f, 30f).area(), 0.01f)
    }
}
