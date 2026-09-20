package com.docssuite.converter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.docssuite.converter.data.OutputStore
import com.docssuite.converter.model.OutputFile
import com.docssuite.converter.model.PICKER_MIME_TYPES
import com.docssuite.converter.model.TargetFormat
import java.io.File

/**
 * Le type MIME dépend du fichier produit, alors que `CreateDocument` le fige à
 * la construction du contrat : d'où cette variante qui le reçoit au lancement.
 */
private class CreateNamedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

/** Largeur à partir de laquelle la navigation passe sur le côté. */
private val WIDE_LAYOUT_BREAKPOINT = 720.dp

@Composable
fun FileConverterScreen(onBack: () -> Unit) {
    val state = rememberConverterState()
    ConverterTheme(state.themeMode) {
        ConverterScaffold(state, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConverterScaffold(state: ConverterState, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingSave by remember { mutableStateOf<OutputFile?>(null) }

    val pickSingle = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { state.select(listOf(it)) } }

    val pickMultiple = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> state.select(uris) }

    val saveLauncher = rememberLauncherForActivityResult(CreateNamedDocument()) { uri ->
        val output = pendingSave
        pendingSave = null
        if (uri != null && output != null) {
            runCatching { OutputStore.copyTo(context, output.file, uri) }
                .onSuccess { state.report("Fichier enregistré") }
                .onFailure { state.report(it.message ?: "Enregistrement impossible") }
        }
    }

    val chooseFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            state.settings.rememberOutputTree(uri)
            state.report("Dossier par défaut enregistré")
        }
    }

    fun save(output: OutputFile) {
        val saved = state.settings.saveToDefaultFolder(
            output.file, output.displayName, output.format.mime
        )
        if (saved != null) {
            state.report("Enregistré dans $saved")
        } else {
            pendingSave = output
            saveLauncher.launch(output.displayName to output.format.mime)
        }
    }

    val actions = remember(state) {
        ConverterActions(
            pickSingle = { mimeTypes, preferred ->
                state.prefer(preferred)
                runCatching { pickSingle.launch(mimeTypes) }
                    .onFailure { state.report("Aucune application de fichiers n'est disponible") }
            },
            pickMultiple = {
                state.prefer(null)
                runCatching { pickMultiple.launch(PICKER_MIME_TYPES) }
                    .onFailure { state.report("Aucune application de fichiers n'est disponible") }
            },
            open = { file, mime -> OutputStore.open(context, file, mime) { state.report(it) } },
            share = { file, mime -> OutputStore.share(context, file, mime) { state.report(it) } },
            save = ::save,
            chooseOutputFolder = {
                runCatching { chooseFolder.launch(null) }
                    .onFailure { state.report("Aucun explorateur de fichiers n'est disponible") }
            }
        )
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        state.dismissMessage()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT_BREAKPOINT

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { ConverterTopBar(state, onBack) },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        NavigationDestination.values().forEach { destination ->
                            NavigationBarItem(
                                selected = state.tab == destination.tab,
                                onClick = { state.showTab(destination.tab) },
                                icon = { Icon(destination.icon, null) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        header = { Spacer(Modifier.height(Spacing.small)) }
                    ) {
                        NavigationDestination.values().forEach { destination ->
                            NavigationRailItem(
                                selected = state.tab == destination.tab,
                                onClick = { state.showTab(destination.tab) },
                                icon = { Icon(destination.icon, null) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    // Sur tablette l'interface ne s'étire pas : elle reste dans
                    // une colonne lisible, centrée dans l'espace disponible.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (wide) Spacing.xlarge else 0.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(modifier = Modifier.widthIn(max = 780.dp).fillMaxSize()) {
                            ConverterContent(state, actions, wide)
                        }
                    }
                    BusyOverlay(state.busyMessage)
                }
            }
        }
    }
}

@Composable
private fun ConverterContent(state: ConverterState, actions: ConverterActions, wide: Boolean) {
    when (state.tab) {
        ConverterTab.HOME -> when (state.stage) {
            Stage.PICK -> HomeScreen(state, actions, wide)
            Stage.CONFIGURE -> ConfigureScreen(state, actions)
            Stage.RUNNING -> RunningScreen(state)
            Stage.RESULT -> ResultScreen(state, actions)
        }
        ConverterTab.HISTORY -> HistoryScreen(state, actions)
        ConverterTab.SETTINGS -> SettingsScreen(state, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConverterTopBar(state: ConverterState, onBack: () -> Unit) {
    val title = when {
        state.tab == ConverterTab.HISTORY -> "Historique"
        state.tab == ConverterTab.SETTINGS -> "Paramètres"
        state.stage == Stage.CONFIGURE -> "Convertir"
        state.stage == Stage.RUNNING -> "Conversion en cours"
        state.stage == Stage.RESULT -> "Résultat"
        else -> "File Converter"
    }
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(
                onClick = {
                    when {
                        state.tab != ConverterTab.HOME -> state.showTab(ConverterTab.HOME)
                        state.stage == Stage.CONFIGURE -> state.reset()
                        state.stage == Stage.RESULT -> state.reset()
                        else -> onBack()
                    }
                }
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
private fun BusyOverlay(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 6 },
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(Spacing.screen),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Radii.pill),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.compact),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        message.orEmpty(),
                        modifier = Modifier.padding(start = Spacing.compact),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private enum class NavigationDestination(
    val tab: ConverterTab,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    HOME(ConverterTab.HOME, "Accueil", Icons.Outlined.Home),
    HISTORY(ConverterTab.HISTORY, "Historique", Icons.Outlined.History),
    SETTINGS(ConverterTab.SETTINGS, "Paramètres", Icons.Outlined.Settings)
}

/** Actions qui sortent de l'application : sélecteur, partage, enregistrement. */
class ConverterActions(
    val pickSingle: (Array<String>, TargetFormat?) -> Unit,
    val pickMultiple: () -> Unit,
    val open: (File, String) -> Unit,
    val share: (File, String) -> Unit,
    val save: (OutputFile) -> Unit,
    val chooseOutputFolder: () -> Unit
)
