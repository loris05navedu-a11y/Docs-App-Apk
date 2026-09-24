package com.docssuite.scanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Images de synthèse : une « photo » est dessinée pixel par pixel, sa vérité connue. */
class ScanFiltersTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun inside(quad: Quad, x: Float, y: Float): Boolean {
        val p = quad.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Une feuille claire, inclinée, posée sur une table sombre et un peu bruitée. */
    private fun photoOf(sheet: Quad, width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width + 0.5f
            val y = i / width + 0.5f
            val noise = (i * 7919 % 13) - 6
            if (inside(sheet, x, y)) gray(225 + noise) else gray(70 + noise)
        }

    private fun close(a: Pt, b: Pt, tolerance: Float) = hypot(a.x - b.x, a.y - b.y) <= tolerance

    @Test
    fun `une feuille inclinee sur un fond sombre est trouvee a quelques pixels pres`() {
        val sheet = Quad(Pt(52f, 30f), Pt(250f, 48f), Pt(236f, 300f), Pt(34f, 282f))
        val found = detectDocument(photoOf(sheet, 300, 330), 300, 330)
        assertNotNull(found)
        sheet.points.zip(found!!.points).forEach { (truth, got) ->
            assertTrue("attendu $truth, trouvé $got", close(truth, got, 3f))
        }
    }

    @Test
    fun `une image uniforme ne propose aucun recadrage`() {
        assertNull(detectDocument(IntArray(200 * 200) { gray(128) }, 200, 200))
    }

    @Test
    fun `une zone claire qui n est pas une feuille est ignoree`() {
        // Un « L » clair : ses points extrêmes dessinent un grand
        // quadrilatère qu'il ne remplit pas du tout.
        val w = 200
        val h = 200
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val inL = (x in 20..70 && y in 20..180) || (x in 20..180 && y in 130..180)
            if (inL) gray(230) else gray(60)
        }
        assertNull(detectDocument(pixels, w, h))
    }

    @Test
    fun `le noir et blanc garde le texte meme dans l ombre`() {
        // Moitié gauche dans l'ombre (papier à 110), moitié droite éclairée
        // (papier à 235) ; des traits d'encre dans les deux moitiés, chacun
        // nettement plus sombre que le papier qui l'entoure.
        val w = 320
        val h = 120
        fun isInk(x: Int, y: Int) = y in 50..55 && (x in 30..130 || x in 190..290)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val paper = if (x < w / 2) 110 else 235
            if (isInk(x, y)) gray(paper - 80) else gray(paper)
        }
        val out = applyFilter(ScanFilter.DOCUMENT, pixels, w, h)
        val black = gray(0)
        val white = gray(255)
        assertEquals("encre dans l'ombre", black, out[52 * w + 80])
        assertEquals("encre en pleine lumière", black, out[52 * w + 240])
        assertEquals("papier dans l'ombre", white, out[20 * w + 80])
        assertEquals("papier en pleine lumière", white, out[20 * w + 240])
    }

    @Test
    fun `les niveaux de gris egalisent les trois canaux`() {
        val out = applyFilter(ScanFilter.GRAY, intArrayOf(rgb(200, 30, 90)), 1, 1)[0]
        val r = (out shr 16) and 0xFF
        assertEquals(r, (out shr 8) and 0xFF)
        assertEquals(r, out and 0xFF)
    }

    @Test
    fun `les couleurs nettes blanchissent le papier sans perdre le rouge d un tampon`() {
        val paper = gray(190)
        val ink = gray(50)
        val stamp = rgb(170, 40, 40)
        val pixels = IntArray(1000) { i -> when { i < 900 -> paper; i < 980 -> ink; else -> stamp } }
        val out = applyFilter(ScanFilter.ENHANCED, pixels, 1000, 1)
        assertEquals(gray(255), out[0])
        assertTrue("l'encre fonce", (out[950] and 0xFF) < 50)
        val r = (out[990] shr 16) and 0xFF
        val g = (out[990] shr 8) and 0xFF
        assertTrue("le tampon reste rouge", r > g + 80)
    }

    @Test
    fun `une page presque vide blanchit aussi`() {
        // 0,5 % d'encre seulement : un reçu, une signature.
        val pixels = IntArray(2000) { i -> if (i < 1990) gray(200) else gray(30) }
        val out = applyFilter(ScanFilter.ENHANCED, pixels, 2000, 1)
        assertEquals(gray(255), out[0])
        assertTrue("l'encre reste sombre", (out[1995] and 0xFF) < 60)
    }

    @Test
    fun `un papier creme sous une lampe jaune redevient blanc`() {
        val cream = rgb(235, 228, 190)
        val pixels = IntArray(1000) { i -> if (i < 950) cream else gray(40) }
        val out = applyFilter(ScanFilter.ENHANCED, pixels, 1000, 1)
        assertEquals(gray(255), out[0])
    }

    @Test
    fun `un filtre ne modifie jamais l image d origine`() {
        val pixels = IntArray(64 * 64) { gray(it % 256) }
        val before = pixels.copyOf()
        ScanFilter.values().forEach { applyFilter(it, pixels, 64, 64) }
        assertArrayEquals(before, pixels)
    }

    @Test
    fun `le seuil d otsu separe deux populations`() {
        val histogram = IntArray(256)
        histogram[40] = 500
        histogram[210] = 500
        val t = otsuThreshold(histogram)
        assertTrue("seuil $t", t in 40 until 210)
    }
}
