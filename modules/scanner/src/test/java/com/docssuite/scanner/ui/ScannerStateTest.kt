package com.docssuite.scanner.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.docssuite.scanner.PageFormat
import com.docssuite.scanner.ScanFilter
import com.docssuite.scanner.SyntheticPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/**
 * Le parcours du scanner sans l'interface : importer une photo, laisser
 * trouver la feuille, la retoucher, réordonner, exporter — et retrouver les
 * pages après qu'Android a fermé l'app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScannerStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanSession() {
        File(context.cacheDir, "scans").deleteRecursively()
    }

    private fun newState() = ScannerState(context, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    /** Les traitements passent par un fil d'arrière-plan puis reviennent sur le fil principal. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("Délai dépassé : $what")
            Thread.sleep(10)
        }
    }

    private fun photoUri(name: String): Uri =
        Uri.fromFile(SyntheticPhoto.writeJpeg(File(context.filesDir, "galerie/$name.jpg")))

    private fun importOne(state: ScannerState) {
        state.importImages(listOf(photoUri("une")))
        waitFor("ouverture de l'éditeur") { state.stage == ScannerStage.EDIT && state.busyMessage == null }
    }

    @Test
    fun `une photo importee devient une page recadree sur la feuille`() {
        val state = newState()
        importOne(state)

        assertEquals(1, state.pages.size)
        val page = state.pages[0]
        assertTrue(page.rendered.exists())
        SyntheticPhoto.sheet.points.zip(page.quad.points).forEach { (truth, got) ->
            assertTrue("attendu $truth, trouvé $got", hypot(truth.x - got.x, truth.y - got.y) < 10f)
        }
        val editor = state.editor
        assertNotNull(editor)
        assertTrue(editor!!.isNew)
        assertEquals(ScanFilter.ENHANCED, editor.filter)
    }

    @Test
    fun `tourner et changer de filtre recalcule la page et devient le reglage suivant`() {
        val state = newState()
        importOne(state)
        val before = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(state.pages[0].rendered.path, it) }

        state.editor!!.rotate(1)
        state.editor!!.filter = ScanFilter.DOCUMENT
        state.applyEdit()
        waitFor("fin du recadrage") { state.stage == ScannerStage.PAGES && state.busyMessage == null }

        val page = state.pages[0]
        assertEquals(1, page.version)
        assertEquals(1, page.quarterTurns)
        assertEquals(ScanFilter.DOCUMENT, page.filter)
        val after = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(page.rendered.path, it) }
        assertEquals(before.outWidth, after.outHeight)
        assertEquals(before.outHeight, after.outWidth)
        assertEquals(ScanFilter.DOCUMENT, state.defaultFilter)
    }

    @Test
    fun `plusieurs photos importees s ajoutent sans ouvrir l editeur`() {
        val state = newState()
        state.importImages(listOf(photoUri("a"), photoUri("b"), photoUri("c")))
        waitFor("trois pages") { state.pages.size == 3 && state.busyMessage == null }
        assertEquals(ScannerStage.PAGES, state.stage)
        assertNull(state.editor)
    }

    @Test
    fun `les pages survivent a la fermeture de l app`() {
        val first = newState()
        first.importImages(listOf(photoUri("a"), photoUri("b")))
        waitFor("deux pages") { first.pages.size == 2 && first.busyMessage == null }
        first.move(first.pages[1], -1)
        val order = first.pages.map { it.id }

        // Un nouvel état, comme au redémarrage de l'app après la prise de vue.
        val restored = newState()
        assertEquals(order, restored.pages.map { it.id })
        assertEquals(first.pages[0].quad, restored.pages[0].quad)
    }

    @Test
    fun `supprimer une page efface aussi ses fichiers`() {
        val state = newState()
        state.importImages(listOf(photoUri("a"), photoUri("b")))
        waitFor("deux pages") { state.pages.size == 2 && state.busyMessage == null }
        val gone = state.pages[0]
        state.delete(gone)
        assertEquals(1, state.pages.size)
        assertFalse(gone.rendered.exists())
        assertFalse(gone.source.exists())
        assertEquals(1, newState().pages.size)
    }

    @Test
    fun `le pdf contient toutes les pages dans l ordre`() {
        val state = newState()
        state.importImages(listOf(photoUri("a"), photoUri("b")))
        waitFor("deux pages") { state.pages.size == 2 && state.busyMessage == null }
        val pdf = runBlocking { state.buildPdfBytes(PageFormat.A4, "Contrat") }
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.contains("/Count 2"))
        state.pages.forEach { page ->
            assertTrue(text.contains(String(page.rendered.readBytes(), Charsets.ISO_8859_1)))
        }
    }

    @Test
    fun `des coins croises sont refuses sans perdre la page`() {
        val state = newState()
        importOne(state)
        val editor = state.editor!!
        val q = editor.quad
        editor.moveCorner(1, q.bottomRight)
        editor.moveCorner(2, q.topRight)
        state.applyEdit()
        assertEquals(ScannerStage.EDIT, state.stage)
        assertNotNull(state.message)
        assertEquals(1, state.pages.size)
    }

    @Test
    fun `nouveau scan repart de zero`() {
        val state = newState()
        importOne(state)
        state.clearAll()
        assertTrue(state.pages.isEmpty())
        assertTrue(newState().pages.isEmpty())
    }
}
