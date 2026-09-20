package com.docssuite.converter

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.converter.data.ConverterSettings
import com.docssuite.converter.data.HistoryStore
import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.SourceFile
import com.docssuite.converter.model.candidateTargets
import com.docssuite.converter.ui.ConfigureScreen
import com.docssuite.converter.ui.ConverterActions
import com.docssuite.converter.ui.ConverterState
import com.docssuite.converter.ui.ConverterTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * L'écran de configuration est celui qui s'affiche juste après le choix d'un
 * fichier. Il doit se composer pour toute combinaison source/cible que le
 * modèle autorise, sinon l'application tombe au moment le plus visible.
 */
@RunWith(AndroidJUnit4::class)
class ConfigureScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun stateWith(kind: FileKind, extension: String): ConverterState {
        val file = File(context.cacheDir, "exemple.$extension").apply { writeBytes(ByteArray(16)) }
        val state = ConverterState(
            context = context,
            scope = CoroutineScope(Dispatchers.Unconfined),
            settings = ConverterSettings(context),
            history = HistoryStore(context)
        )
        state.selection.add(
            SourceFile(
                uri = Uri.fromFile(file),
                displayName = "exemple.$extension",
                extension = extension,
                sizeBytes = 16,
                kind = kind,
                cached = file,
                targets = candidateTargets(kind, extension),
                detail = "détail"
            )
        )
        return state
    }

    private val noActions = ConverterActions(
        pickSingle = { _, _ -> },
        pickMultiple = {},
        open = { _, _ -> },
        share = { _, _ -> },
        save = {},
        chooseOutputFolder = {}
    )

    @Test
    fun `toutes les cibles proposees se composent sans erreur`() {
        val cases = listOf(
            FileKind.IMAGE to "jpg",
            FileKind.PDF to "pdf",
            FileKind.AUDIO to "mp3",
            FileKind.VIDEO to "mkv",
            FileKind.TEXT to "txt",
            FileKind.OTHER to "xyz"
        )

        val current = mutableStateOf(stateWith(FileKind.IMAGE, "jpg"))
        compose.setContent {
            ConverterTheme { ConfigureScreen(current.value, noActions) }
        }

        cases.forEach { (kind, extension) ->
            val state = stateWith(kind, extension)
            state.availableTargets.forEach { target ->
                state.chooseTarget(target)
                current.value = state
                compose.waitForIdle()
            }
        }
    }
}
