package com.docssuite.converter

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.converter.data.ConverterSettings
import com.docssuite.converter.data.HistoryStore
import com.docssuite.converter.data.OutputStore
import com.docssuite.converter.engine.MediaProbe
import com.docssuite.converter.model.kindForExtension
import com.docssuite.converter.ui.ConverterState
import com.docssuite.converter.ui.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * Le parcours complet « choisir un fichier » sans interface : copie dans le
 * cache, analyse du contenu, puis passage à l'écran de configuration.
 */
@RunWith(AndroidJUnit4::class)
class SelectionFlowTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** PNG 1×1 complet et valide, sommes de contrôle comprises. */
    private fun samplePng(): File {
        val file = File(context.cacheDir, "exemple.png")
        file.writeBytes(
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8" +
                    "BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
            )
        )
        return file
    }

    @Test
    fun `la copie de travail conserve le nom et l extension`() {
        val source = samplePng()
        val picked = OutputStore.cacheSource(context, Uri.fromFile(source))

        assertEquals("exemple.png", picked.displayName)
        assertEquals("png", picked.extension)
        assertTrue(picked.cached.exists())
        assertTrue(picked.sizeBytes > 0)
    }

    @Test
    fun `l analyse d un fichier inconnu ne propose que l archive`() {
        val file = File(context.cacheDir, "donnees.xyz").apply { writeBytes(ByteArray(32)) }
        val probe = MediaProbe.inspect(file, kindForExtension("xyz"), "xyz")

        assertEquals(1, probe.targets.size)
        assertEquals("zip", probe.targets.first().extension)
    }

    private fun newState() = ConverterState(
        context = context,
        scope = CoroutineScope(Dispatchers.Unconfined),
        settings = ConverterSettings(context),
        history = HistoryStore(context)
    )

    /** La lecture passe par un fil d'arrière-plan : on attend son résultat. */
    private fun waitFor(describe: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Condition jamais atteinte — ${describe()}")
    }

    @Test
    fun `selectionner un fichier amene a l ecran de configuration`() {
        val state = newState()

        state.select(listOf(Uri.fromFile(samplePng())))

        waitFor(describe = { "message = ${state.message}, étape = ${state.stage}" }) {
            state.stage == Stage.CONFIGURE
        }
        assertEquals(1, state.selection.size)
        assertTrue(state.availableTargets.isNotEmpty())
    }

    @Test
    fun `un fichier illisible affiche un message au lieu de faire tomber l application`() {
        val state = newState()
        val missing = Uri.fromFile(File(context.cacheDir, "absent-${System.nanoTime()}.png"))

        state.select(listOf(missing))

        waitFor { state.message != null }
        assertEquals(Stage.PICK, state.stage)
        assertTrue(state.selection.isEmpty())
    }
}
