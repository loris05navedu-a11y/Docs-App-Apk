package com.docssuite.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/**
 * Le rendu réel avec le moteur graphique natif : une feuille inclinée,
 * posée sur une table sombre, avec une ligne de « texte » à 30 % de sa
 * hauteur. Redressée, elle doit remplir toute la page, table disparue, et
 * sa ligne doit être horizontale à la bonne hauteur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanImagingTest {

    private val sheet = SyntheticPhoto.sheet

    private fun photo(): Bitmap = SyntheticPhoto.bitmap()

    private fun lum(bitmap: Bitmap, x: Int, y: Int) = luminance(bitmap.getPixel(x, y))

    @Test
    fun `la feuille est detectee sur la photo`() {
        val found = ScanImaging.detect(photo())
        assertNotNull(found)
        sheet.points.zip(found!!.points).forEach { (truth, got) ->
            assertTrue("attendu $truth, trouvé $got", hypot(truth.x - got.x, truth.y - got.y) < 10f)
        }
    }

    @Test
    fun `le redressement fait disparaitre la table et remet la ligne a l horizontale`() {
        val page = ScanImaging.render(photo(), sheet, 0, ScanFilter.ORIGINAL)
        val w = page.width
        val h = page.height
        val inset = (minOf(w, h) * 0.04f).toInt()
        listOf(inset to inset, w - inset to inset, w - inset to h - inset, inset to h - inset).forEach { (x, y) ->
            assertTrue("coin ($x, $y) encore sur la table", lum(page, x, y) > 200)
        }
        val lineY = (h * 0.3f).toInt()
        listOf(0.25f, 0.5f, 0.75f).forEach { fx ->
            assertTrue("ligne absente à x=$fx", lum(page, (w * fx).toInt(), lineY) < 90)
        }
        assertTrue("papier sous la ligne", lum(page, w / 2, (h * 0.6f).toInt()) > 200)
    }

    @Test
    fun `aucun liseré de table ne reste sur les bords de la page`() {
        val page = ScanImaging.render(photo(), sheet, 0, ScanFilter.DOCUMENT)
        val w = page.width
        val h = page.height
        for (x in 0 until w step 7) {
            assertEquals("haut x=$x", Color.WHITE, page.getPixel(x, 0))
            assertEquals("bas x=$x", Color.WHITE, page.getPixel(x, h - 1))
        }
        for (y in 0 until h step 7) {
            assertEquals("gauche y=$y", Color.WHITE, page.getPixel(0, y))
            assertEquals("droite y=$y", Color.WHITE, page.getPixel(w - 1, y))
        }
    }

    @Test
    fun `le filtre noir et blanc ne laisse que du noir et du blanc`() {
        val page = ScanImaging.render(photo(), sheet, 0, ScanFilter.DOCUMENT)
        val pixels = IntArray(page.width * page.height)
        page.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
        assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
        assertEquals(Color.BLACK, page.getPixel(page.width / 2, (page.height * 0.3f).toInt()))
    }

    @Test
    fun `un quart de tour echange largeur et hauteur`() {
        val upright = ScanImaging.render(photo(), sheet, 0, ScanFilter.ORIGINAL)
        val turned = ScanImaging.render(photo(), sheet, 1, ScanFilter.ORIGINAL)
        assertEquals(upright.width, turned.height)
        assertEquals(upright.height, turned.width)
    }

    @Test
    fun `des coins croises sont refuses avec un message clair`() {
        val crossed = sheet.copy(topRight = sheet.bottomRight, bottomRight = sheet.topRight)
        assertThrows(IllegalArgumentException::class.java) {
            ScanImaging.render(photo(), crossed, 0, ScanFilter.ORIGINAL)
        }
    }

    @Test
    fun `une photo marquee a tourner de 90 degres est remise droite`() {
        val file = File.createTempFile("couchee", ".jpg")
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.writeBytes(ScanImaging.encodeJpeg(bitmap))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val decoded = ScanImaging.decodeSource(file)
        assertEquals(100, decoded.width)
        assertEquals(200, decoded.height)
    }

    @Test
    fun `une grande photo est sous-echantillonnee au decodage`() {
        val file = File.createTempFile("grande", ".jpg")
        file.writeBytes(ScanImaging.encodeJpeg(Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)))
        val decoded = ScanImaging.decodeSource(file, maxSide = 1000)
        assertEquals(800, decoded.width)
    }

    @Test
    fun `de la photo au pdf les pages gardent leurs dimensions`() {
        val pages = listOf(
            ScanImaging.render(photo(), sheet, 0, ScanFilter.ENHANCED),
            ScanImaging.render(photo(), sheet, 1, ScanFilter.DOCUMENT)
        )
        val jpegs = pages.map { ScanImaging.encodeJpeg(it) }
        jpegs.zip(pages).forEach { (jpeg, page) ->
            val info = readJpegInfo(jpeg)
            assertEquals(page.width, info.width)
            assertEquals(page.height, info.height)
        }
        val pdf = buildPdf(jpegs, PageFormat.A4, "Scan de test", "20260924120000")
        // Laissé dans build/ pour une vérification avec un lecteur PDF externe.
        File("build/scan-test.pdf").writeBytes(pdf)
        assertTrue(String(pdf, Charsets.ISO_8859_1).contains("/Count 2"))
    }
}
