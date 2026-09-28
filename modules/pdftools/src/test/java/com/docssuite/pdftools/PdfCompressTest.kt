package com.docssuite.pdftools

import android.graphics.BitmapFactory
import com.docssuite.pdftools.PdfTestFiles.bytes
import org.junit.Assert.assertArrayEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class PdfCompressTest {

    private fun keep(name: String, bytes: ByteArray) {
        val dir = System.getProperty("pdfout") ?: return
        File(dir).mkdirs()
        File(dir, name).writeBytes(bytes)
    }

    private fun image(file: PdfFile): PdfStream {
        val resources = file.resolve(file.pages[0].attribute("Resources")) as PdfDict
        val xobjects = file.resolve(resources["XObject"]) as PdfDict
        return file.resolve(xobjects["Im1"]) as PdfStream
    }

    private fun int(file: PdfFile, dict: PdfDict, key: String) = (file.resolve(dict[key]) as PdfNumber).intValue

    @Test
    fun `une grosse photo JPEG est reduite et recompressee`() {
        val source = PdfTestImages.jpegPdf()
        val result = PdfCompress.compress(source, CompressionLevel.BALANCED)
        keep("compress-jpeg.pdf", result.bytes)
        assertEquals(1, result.imagesReduced)
        assertTrue("${result.after} < ${result.before}", result.after < result.before / 2)
        val file = PdfFile.parse(result.bytes)
        val im = image(file)
        assertEquals(1600, int(file, im.dict, "Width"))
        assertEquals(1200, int(file, im.dict, "Height"))
        assertEquals("DCTDecode", im.dict.nameOf("Filter"))
        // L'image recompressée est un JPEG valide.
        val decoded = BitmapFactory.decodeByteArray(im.data, 0, im.data.size)
        assertEquals(1600, decoded.width)
    }

    @Test
    fun `une photo non compressee devient un JPEG`() {
        val width = 1400
        val height = 1000
        val pixels = PdfTestImages.photo(width, height)
        val rgb = ByteArray(width * height * 3)
        pixels.forEachIndexed { i, p -> rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte() }
        val source = PdfTestImages.pdfWithImage("/Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode", PdfTestImages.deflate(rgb))
        val result = PdfCompress.compress(source, CompressionLevel.LIGHT)
        keep("compress-flate.pdf", result.bytes)
        assertEquals(1, result.imagesReduced)
        assertTrue(result.saved > 0.5f)
        val im = image(PdfFile.parse(result.bytes))
        assertEquals("DCTDecode", im.dict.nameOf("Filter"))
        // Déjà sous la taille maximale du niveau léger : pas redimensionnée.
        assertEquals(width, (im.dict["Width"] as PdfNumber).intValue)
    }

    @Test
    fun `un scan en gris reste lisible`() {
        val width = 1700
        val height = 2300
        val gray = PdfTestImages.photo(width, height, gray = true).map { (it and 0xFF).toByte() }.toByteArray()
        val source = PdfTestImages.pdfWithImage("/Width $width /Height $height /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", PdfTestImages.deflate(gray))
        val result = PdfCompress.compress(source, CompressionLevel.STRONG)
        keep("compress-gray.pdf", result.bytes)
        assertEquals(1, result.imagesReduced)
        val file = PdfFile.parse(result.bytes)
        val im = image(file)
        assertEquals(1100, int(file, im.dict, "Height"))
        assertEquals("DeviceRGB", im.dict.nameOf("ColorSpace"))
        assertNotNull(BitmapFactory.decodeByteArray(im.data, 0, im.data.size))
    }

    @Test
    fun `plus le niveau est fort, plus le fichier est leger`() {
        val source = PdfTestImages.jpegPdf()
        val light = PdfCompress.compress(source, CompressionLevel.LIGHT).after
        val balanced = PdfCompress.compress(source, CompressionLevel.BALANCED).after
        val strong = PdfCompress.compress(source, CompressionLevel.STRONG).after
        assertTrue("$light > $balanced > $strong", light > balanced && balanced > strong)
    }

    @Test
    fun `une petite image n'est pas touchee`() {
        val source = PdfTestImages.jpegPdf(120, 90)
        val result = PdfCompress.compress(source, CompressionLevel.STRONG)
        assertEquals(0, result.imagesReduced)
    }

    @Test
    fun `un document sans image ne grossit jamais`() {
        val source = bytes("plain-bulletin.pdf")
        val result = PdfCompress.compress(source, CompressionLevel.STRONG)
        assertTrue(result.after <= result.before)
        assertEquals(0, result.imagesReduced)
        assertEquals(2, PdfFile.parse(result.bytes).pages.size)
    }

    @Test
    fun `le texte et la structure ne changent pas`() {
        val source = PdfTestImages.jpegPdf()
        val result = PdfCompress.compress(source, CompressionLevel.BALANCED)
        val before = PdfFile.parse(source)
        val after = PdfFile.parse(result.bytes)
        assertArrayEquals(PdfTestFiles.content(before, 0), PdfTestFiles.content(after, 0))
    }

    @Test
    fun `des flux laisses en clair sont compresses`() {
        // alpha-simple : contenus de pages non compressés.
        val source = bytes("alpha-simple.pdf")
        val result = PdfCompress.compress(source, CompressionLevel.LIGHT)
        val file = PdfFile.parse(result.bytes)
        assertEquals(3, file.pages.size)
        fun decoded(pdf: PdfFile, index: Int): String {
            val streams = when (val contents = pdf.resolve(pdf.pages[index].dict["Contents"])) {
                is PdfStream -> listOf(contents)
                is PdfArray -> contents.items.map { pdf.resolve(it) as PdfStream }
                else -> emptyList()
            }
            return streams.joinToString("") { String(PdfFilters.decode(it, pdf::resolve), Charsets.ISO_8859_1) }
        }
        val original = PdfFile.parse(source)
        for (i in 0..2) assertEquals(decoded(original, i), decoded(file, i))
    }

    @Test
    fun `un PDF protege le reste apres compression`() {
        val protected = PdfLock.protect(PdfTestImages.jpegPdf(), "lune")
        val result = PdfCompress.compress(protected, CompressionLevel.BALANCED, password = "lune")
        keep("compress-protected.pdf", result.bytes)
        assertEquals(1, result.imagesReduced)
        assertThrows(PdfEncryptedException::class.java) { PdfFile.parse(result.bytes) }
        assertEquals(1600, (image(PdfFile.parse(result.bytes, "lune")).dict["Width"] as PdfNumber).intValue)
    }
}
