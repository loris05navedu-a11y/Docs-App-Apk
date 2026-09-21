package com.docssuite.converter

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.converter.data.ConverterSettings
import com.docssuite.converter.data.HistoryStore
import com.docssuite.converter.ui.ConverterScaffold
import com.docssuite.converter.ui.ConverterState
import com.docssuite.converter.ui.ConverterTab
import com.docssuite.converter.ui.ConverterTheme
import com.docssuite.converter.ui.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * Le parcours réel, écran complet : barre supérieure, navigation, voile
 * d'attente et contenu. Le test précédent ne rendait que l'écran de
 * configuration seul, ce qui laissait toute l'ossature hors de portée.
 */
@RunWith(AndroidJUnit4::class)
class ConverterScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newState() = ConverterState(
        context = context,
        scope = CoroutineScope(Dispatchers.Unconfined),
        settings = ConverterSettings(context),
        history = HistoryStore(context)
    )

    private fun samplePng(): File =
        File(context.cacheDir, "photo.png").apply {
            writeBytes(
                Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8" +
                        "BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
                )
            )
        }

    private fun waitFor(describe: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            compose.waitForIdle()
            Thread.sleep(20)
        }
        throw AssertionError("Condition jamais atteinte — ${describe()}")
    }

    @Test
    fun `choisir un fichier traverse l accueil le voile d attente et la configuration`() {
        val state = newState()
        compose.setContent {
            ConverterTheme(state.themeMode) { ConverterScaffold(state) {} }
        }
        compose.waitForIdle()

        state.select(listOf(Uri.fromFile(samplePng())))

        waitFor(describe = { "message = ${state.message}, étape = ${state.stage}" }) {
            state.stage == Stage.CONFIGURE
        }
        compose.waitForIdle()
    }

    @Test
    fun `les trois onglets se rendent sans erreur`() {
        val state = newState()
        compose.setContent {
            ConverterTheme(state.themeMode) { ConverterScaffold(state) {} }
        }

        ConverterTab.values().forEach { tab ->
            state.showTab(tab)
            compose.waitForIdle()
        }
        assertEquals(ConverterTab.SETTINGS, state.tab)
    }
}
