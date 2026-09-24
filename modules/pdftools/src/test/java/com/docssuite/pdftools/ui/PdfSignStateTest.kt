package com.docssuite.pdftools.ui

import androidx.compose.ui.geometry.Offset
import com.docssuite.pdftools.PdfFile
import com.docssuite.pdftools.PdfTestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfSignStateTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private val created = ArrayList<PdfSignState>()

    @org.junit.After
    fun closeStates() = created.forEach { it.close() }

    private fun opened(name: String): PdfSignState {
        val state = PdfSignState(app, CoroutineScope(SupervisorJob() + Dispatchers.Main)).also { created.add(it) }
        val bytes = PdfTestFiles.bytes(name)
        state.load(PdfFile.parse(bytes), File(app.cacheDir, name).apply { writeBytes(bytes) }, name)
        return state
    }

    /** Une signature « dessinée » : deux traits ondulés. */
    private fun drawnSignature() = signatureBitmap(
        listOf(
            (0..60).map { Offset(it * 5f, 50 + 25 * sin(it / 6.0).toFloat()) },
            (0..20).map { Offset(40f + it * 8, 95f - it) }
        ),
        strokeWidth = 7f
    )!!

    @Test
    fun `la signature dessinee est recadree au plus pres sur fond transparent et memorisee`() {
        val bitmap = drawnSignature()
        assertTrue("largeur ${bitmap.width}", bitmap.width in 300..330)
        assertEquals(0, bitmap.getPixel(0, 0) ushr 24) // coin transparent
        val state = opened("alpha-simple.pdf")
        state.rememberSignature(bitmap)
        assertNotNull(PdfSignState(app, CoroutineScope(Dispatchers.Main)).savedSignature)
        state.forgetSignature()
    }

    @Test
    fun `signer remplir et produire le pdf`() {
        val state = opened("alpha-simple.pdf")
        assertEquals("alpha-simple", state.name)
        state.rememberSignature(drawnSignature())
        val aspect = state.geometry(0).let { it.displayWidth / it.displayHeight }

        state.armed = SignTool.SIGNATURE
        state.placeAt(0, 0.7f, 0.85f, aspect)
        state.armed = SignTool.TEXT
        state.placeAt(0, 0.1f, 0.8f, aspect)
        state.update(state.selectedId!!) { it.copy(text = "Lu et approuvé — Léa Dupont") }
        state.armed = SignTool.DATE
        state.placeAt(0, 0.1f, 0.85f, aspect)
        state.armed = SignTool.CHECK
        state.placeAt(2, 0.1f, 0.3f, aspect)
        assertEquals(null, state.armed) // un outil sert une fois

        val bytes = state.build()
        File("build/pdftools-out").mkdirs()
        File("build/pdftools-out/signe-par-ecran.pdf").writeBytes(bytes)
        val out = PdfFile.parse(bytes)
        assertFalse(out.repaired)
        assertEquals(3, out.pages.size)
        val page1 = String(PdfTestFiles.content(out, 0), Charsets.ISO_8859_1)
        assertTrue(page1.contains("/DocsIm1 Do"))
        assertTrue(page1.contains("Lu et approuvé"))
        assertTrue(Regex("\\(\\d{2}/\\d{2}/\\d{4}\\) Tj").containsMatchIn(page1))
        assertTrue(String(PdfTestFiles.content(out, 2), Charsets.ISO_8859_1).contains("(4) Tj"))
        assertEquals("alpha-simple (signé)", state.signedTitle())
    }

    @Test
    fun `sans signature memorisee l outil signature ne pose rien`() {
        val state = opened("alpha-simple.pdf")
        state.forgetSignature()
        state.armed = SignTool.SIGNATURE
        state.placeAt(0, 0.5f, 0.5f, 0.7f)
        assertTrue(state.placed.isEmpty())
    }
}
