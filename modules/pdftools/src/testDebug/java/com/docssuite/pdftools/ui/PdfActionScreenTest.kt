package com.docssuite.pdftools.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.pdftools.PdfArray
import com.docssuite.pdftools.PdfEncryptedException
import com.docssuite.pdftools.PdfFile
import com.docssuite.pdftools.PdfStream
import com.docssuite.pdftools.PdfTestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.Inflater

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class PdfActionScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val output: String? = System.getProperty("screenshots")
    private var opened: Pair<String, ByteArray>? = null

    private fun shoot(name: String) {
        val dir = output ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(action: PdfAction, fixture: String, name: String = fixture) = show(action, PdfTestFiles.bytes(fixture), name)

    private fun show(action: PdfAction, bytes: ByteArray, name: String) {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                PdfActionScreen(
                    action = action,
                    onBack = {},
                    onOpenPdf = { n, b -> opened = n to b },
                    initialFile = name to bytes
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("État :", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
    }

    /** Attend le résultat, puis l'ouvre pour récupérer le PDF produit. */
    private fun result(): Pair<String, ByteArray> {
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Résultat :", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        scrollTo("Ouvrir")
        compose.onNodeWithText("Ouvrir").performClick()
        return opened!!
    }

    @Test
    fun `proteger un PDF par mot de passe`() {
        show(PdfAction.PROTECT, "plain-bulletin.pdf", "bulletin.pdf")
        compose.onNodeWithContentDescription("État : Sans protection").assertIsDisplayed()
        field("Mot de passe").performTextInput("Lune2026")
        field("confirmer").performTextInput("Lune2025")
        compose.onNodeWithText("Les deux mots de passe ne sont pas identiques.").assertIsDisplayed()
        shoot("pdf-proteger")
    }

    @Test
    fun `proteger produit un PDF chiffre`() {
        show(PdfAction.PROTECT, "plain-bulletin.pdf", "bulletin.pdf")
        field("Mot de passe").performTextInput("Lune2026")
        field("confirmer").performTextInput("Lune2026")
        compose.onNodeWithText("Protéger le PDF").performClick()
        val (name, bytes) = result()
        assertEquals("bulletin (protégé).pdf", name)
        assertThrows(PdfEncryptedException::class.java) { PdfFile.parse(bytes) }
        assertEquals(2, PdfFile.parse(bytes, "Lune2026").pages.size)
        shoot("pdf-protege")
    }

    @Test
    fun `deverrouiller un PDF dont on connait le mot de passe`() {
        show(PdfAction.PROTECT, "enc-aes-256.pdf", "releve.pdf")
        compose.onNodeWithContentDescription("État : Protégé par mot de passe").assertIsDisplayed()
        field("Mot de passe du PDF").performTextInput("soleilx")
        compose.onNodeWithText("Déverrouiller").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Mot de passe incorrect.")).fetchSemanticsNodes().isNotEmpty() }

        field("Mot de passe du PDF").performTextReplacement("lune")
        compose.onNodeWithText("Déverrouiller").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Retirer le mot de passe")).fetchSemanticsNodes().isNotEmpty() }
        shoot("pdf-deverrouiller")
        compose.onNodeWithText("Produire le PDF sans mot de passe").performClick()
        val (name, bytes) = result()
        assertEquals("releve (déverrouillé).pdf", name)
        assertNull(PdfFile.parse(bytes).security)
    }

    @Test
    fun `un PDF restreint se libere sans mot de passe`() {
        show(PdfAction.PROTECT, "enc-owner-only.pdf", "notice.pdf")
        compose.onNode(hasContentDescription("Restreint", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Produire le PDF sans restriction").performClick()
        assertNull(PdfFile.parse(result().second).security)
    }

    @Test
    fun `filigrane et numeros de page`() {
        show(PdfAction.STAMP, "plain-bulletin.pdf", "bulletin.pdf")
        compose.onAllNodes(hasText("CONFIDENTIEL")).onFirst().assertIsDisplayed()
        shoot("pdf-tampon")
        scrollTo("Appliquer au PDF")
        compose.onNodeWithText("Appliquer au PDF").performClick()
        val (name, bytes) = result()
        assertEquals("bulletin (tamponné).pdf", name)
        val file = PdfFile.parse(bytes)
        val text = pageText(file, 1)
        assertTrue(text, text.contains("(CONFIDENTIEL) Tj"))
        assertTrue(text, text.contains("(Page 2 sur 2) Tj"))
    }

    @Test
    fun `compresser annonce le gain`() {
        val photo = com.docssuite.pdftools.PdfTestImages.jpegPdf()
        show(PdfAction.COMPRESS, photo, "chantier.pdf")
        compose.onNodeWithContentDescription("Niveau Forte").performClick()
        compose.onNodeWithText("Compresser").performClick()
        val (name, bytes) = result()
        assertEquals("chantier (compressé).pdf", name)
        assertEquals(1, PdfFile.parse(bytes).pages.size)
        assertTrue(bytes.size < photo.size / 3)
        compose.onNode(hasContentDescription("1 image allégée", substring = true)).assertIsDisplayed()
        shoot("pdf-compresse")
    }

    @Test
    fun `un PDF deja leger le dit plutot que de grossir`() {
        show(PdfAction.COMPRESS, "alpha-simple.pdf", "rapport.pdf")
        compose.onNodeWithText("Compresser").performClick()
        val (_, bytes) = result()
        compose.onNode(hasContentDescription("Déjà aussi léger que possible", substring = true)).assertIsDisplayed()
        assertTrue(bytes.contentEquals(PdfTestFiles.bytes("alpha-simple.pdf")))
    }

    private fun pageText(file: PdfFile, index: Int): String {
        val streams = when (val c = file.resolve(file.pages[index].dict["Contents"])) {
            is PdfStream -> listOf(c)
            is PdfArray -> c.items.map { file.resolve(it) as PdfStream }
            else -> emptyList()
        }
        return streams.joinToString("\n") { s ->
            val raw = if (s.dict.nameOf("Filter") == "FlateDecode") inflate(s.data) else s.data
            String(raw, Charsets.ISO_8859_1)
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater().apply { setInput(data) }
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
