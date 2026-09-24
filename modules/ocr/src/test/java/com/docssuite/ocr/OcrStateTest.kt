package com.docssuite.ocr

import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcrStateTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun state(engine: OcrEngine) = OcrState(context, CoroutineScope(SupervisorJob() + Dispatchers.Main), engine)

    private fun photo(name: String) = Uri.fromFile(writeSheetPhoto(File(context.filesDir, "galerie/$name.jpg")))

    private fun waitIdle(state: OcrState, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!(condition() && state.busyMessage == null)) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "Délai dépassé" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `la feuille est redressee avant la lecture et le texte remis en forme`() {
        val engine = FakeOcrEngine(ArrayDeque(listOf(listOf(block("Madame, Monsieur,"), block("Je vous prie de bien vou-", "loir trouver ci-joint mon dossier.")))))
        val s = state(engine)
        s.importImages(listOf(photo("lettre")))
        waitIdle(s) { s.images.size == 1 }

        assertEquals("Madame, Monsieur,\n\nJe vous prie de bien vouloir trouver ci-joint mon dossier.", s.text)
        // Le moteur a reçu la feuille recadrée (portrait, ≈ 360 × 610), pas la photo 600 × 800 entière.
        val (w, h) = engine.received.single()
        assertTrue("reçu ${w}x$h", w in 330..400 && h in 580..650)
        assertEquals(12, s.wordCount)
        assertEquals("Madame, Monsieur", s.suggestedTitle())
    }

    @Test
    fun `une page ajoutee vient a la suite sans ecraser les retouches`() {
        val engine = FakeOcrEngine(ArrayDeque(listOf(listOf(block("Page un.")), listOf(block("Page deux.")))))
        val s = state(engine)
        s.importImages(listOf(photo("un")))
        waitIdle(s) { s.images.size == 1 }
        s.text = "Page un, corrigée."
        s.importImages(listOf(photo("deux")))
        waitIdle(s) { s.images.size == 2 }
        assertEquals("Page un, corrigée.\n\nPage deux.", s.text)
    }

    @Test
    fun `une photo sans texte le dit clairement`() {
        val s = state(FakeOcrEngine(ArrayDeque(listOf(emptyList()))))
        s.importImages(listOf(photo("vide")))
        waitIdle(s) { s.images.size == 1 }
        assertTrue(s.message!!, s.message!!.startsWith("Aucun texte trouvé"))
        assertEquals("", s.text)
    }

    @Test
    fun `une erreur du moteur devient un message sans planter`() {
        val engine = FakeOcrEngine(ArrayDeque()).apply { failWith = IllegalStateException("modèle indisponible") }
        val s = state(engine)
        s.importImages(listOf(photo("x")))
        waitIdle(s) { s.message != null }
        assertTrue(s.message!!.contains("modèle indisponible"))
        assertTrue(s.images.isEmpty())
    }
}
