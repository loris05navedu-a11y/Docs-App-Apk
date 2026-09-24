package com.docssuite.pdftools.ui

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.docssuite.pdftools.PdfFile
import com.docssuite.pdftools.PdfTestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfToolsStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newState() = PdfToolsState(context, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    private fun uri(name: String): Uri {
        val file = File(context.filesDir, "docs/$name").apply { parentFile!!.mkdirs(); writeBytes(PdfTestFiles.bytes(name)) }
        return Uri.fromFile(file)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("Délai dépassé : $what")
            Thread.sleep(10)
        }
    }

    private fun opened(vararg names: String): PdfToolsState {
        val state = newState()
        state.import(names.map(::uri))
        waitFor("ouverture") { state.busyMessage == null && state.documents.size + (state.message?.let { 1 } ?: 0) >= names.size }
        return state
    }

    /** Le texte de repérage de chaque page : son contenu d'origine. */
    private fun contents(pdf: ByteArray): List<ByteArray> {
        val file = PdfFile.parse(pdf)
        return file.pages.indices.map { PdfTestFiles.content(file, it) }
    }

    private fun source(name: String, index: Int) = PdfTestFiles.content(PdfTestFiles.open(name), index)

    @Test
    fun `deux pdf ouverts donnent une seule liste de pages`() {
        val state = opened("alpha-simple.pdf", "beta-objstm.pdf")
        assertEquals(2, state.documents.size)
        assertEquals(7, state.pages.size)
        assertEquals(listOf("alpha-simple.pdf", "beta-objstm.pdf"), state.documents.map { it.name })
    }

    @Test
    fun `un pdf protege est signale sans bloquer les autres`() {
        val state = opened("secret-encrypted.pdf", "alpha-simple.pdf")
        assertEquals(3, state.pages.size)
        assertTrue(state.message!!, state.message!!.contains("mot de passe"))
    }

    @Test
    fun `les pages selectionnees se deplacent en bloc`() {
        val state = opened("beta-objstm.pdf")
        state.toggle(state.pages[2])
        state.toggle(state.pages[3])
        state.moveSelected(-1)
        state.moveSelected(-1)
        state.moveSelected(-1) // déjà en tête : ne bouge plus
        assertEquals(listOf(2, 3, 0, 1), state.pages.map { it.pageIndex })
        state.moveSelected(1)
        assertEquals(listOf(0, 2, 3, 1), state.pages.map { it.pageIndex })
    }

    @Test
    fun `une suppression s annule`() {
        val state = opened("alpha-simple.pdf")
        state.toggle(state.pages[1])
        state.deleteSelected()
        assertEquals(2, state.pages.size)
        assertEquals(1, state.deletedCount)
        state.undoDelete()
        assertEquals(listOf(0, 1, 2), state.pages.map { it.pageIndex })
    }

    @Test
    fun `sans selection la rotation vaut pour tout le document`() {
        val state = opened("alpha-simple.pdf")
        state.rotateSelected(1)
        assertTrue(state.pages.all { it.quarterTurns == 1 })
        state.toggle(state.pages[0])
        state.rotateSelected(-1)
        assertEquals(listOf(0, 1, 1), state.pages.map { it.quarterTurns })
    }

    @Test
    fun `l export suit l ordre et la selection affiches`() {
        val state = opened("alpha-simple.pdf", "beta-objstm.pdf")
        // Beta 1 déplacée en tête, alpha 2 supprimée.
        state.toggle(state.pages[3])
        repeat(3) { state.moveSelected(-1) }
        state.selectAll(false)
        state.toggle(state.pages.first { it.document.name == "alpha-simple.pdf" && it.pageIndex == 1 })
        state.deleteSelected()

        val all = contents(state.assemble(onlySelection = false, title = "Dossier"))
        val expected = listOf(
            source("beta-objstm.pdf", 0), source("alpha-simple.pdf", 0), source("alpha-simple.pdf", 2),
            source("beta-objstm.pdf", 1), source("beta-objstm.pdf", 2), source("beta-objstm.pdf", 3)
        )
        assertEquals(expected.size, all.size)
        expected.zip(all).forEach { (want, got) -> assertArrayEquals(want, got) }

        state.selectAll(false)
        state.toggle(state.pages[4])
        state.toggle(state.pages[0])
        val extract = contents(state.assemble(onlySelection = true, title = "Extrait"))
        assertEquals(2, extract.size)
        assertArrayEquals(source("beta-objstm.pdf", 0), extract[0])
        assertArrayEquals(source("beta-objstm.pdf", 2), extract[1])
    }

    @Test
    fun `une vignette impossible a dessiner ne fait pas planter`() {
        val state = opened("alpha-simple.pdf")
        // Robolectric n'a pas le moteur PDF natif : la vignette manque, la page reste là.
        runBlocking { state.thumbnail(state.pages[0], 120) }
        assertEquals(3, state.pages.size)
    }
}
