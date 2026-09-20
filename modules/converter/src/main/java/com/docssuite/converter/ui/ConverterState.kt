package com.docssuite.converter.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.docssuite.converter.data.ConverterSettings
import com.docssuite.converter.data.HistoryStore
import com.docssuite.converter.data.OutputStore
import com.docssuite.converter.engine.ConversionCancelled
import com.docssuite.converter.engine.ConversionEngine
import com.docssuite.converter.engine.MediaProbe
import com.docssuite.converter.model.ConversionOptions
import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.HistoryEntry
import com.docssuite.converter.model.ItemState
import com.docssuite.converter.model.OutputFile
import com.docssuite.converter.model.QueueItem
import com.docssuite.converter.model.SourceFile
import com.docssuite.converter.model.TargetFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConverterTab { HOME, HISTORY, SETTINGS }

/** Étape du parcours de conversion. */
enum class Stage { PICK, CONFIGURE, RUNNING, RESULT }

/**
 * État de l'application, partagé par tous les écrans. Il garde la sélection en
 * cours, les réglages, la file de conversion et ses résultats ; les traitements
 * lourds partent sur un fil d'arrière-plan et rendent la main immédiatement.
 */
class ConverterState(
    private val context: Context,
    private val scope: CoroutineScope,
    val settings: ConverterSettings,
    private val history: HistoryStore
) {
    var tab by mutableStateOf(ConverterTab.HOME)
        private set
    var stage by mutableStateOf(Stage.PICK)
        private set
    var themeMode by mutableStateOf(settings.themeMode)
        private set

    val selection = mutableStateListOf<SourceFile>()
    var target by mutableStateOf<TargetFormat?>(null)
        private set
    var options by mutableStateOf(ConversionOptions(quality = settings.defaultQuality))
        private set

    val queue = mutableStateListOf<QueueItem>()
    var results = mutableStateListOf<OutputFile>()
        private set

    var busyMessage by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var sourceWarning by mutableStateOf<String?>(null)
        private set
    var estimatedSize by mutableStateOf<Long?>(null)
        private set
    var estimating by mutableStateOf(false)
        private set

    var historyEntries = mutableStateListOf<HistoryEntry>()
        private set

    private var conversionJob: Job? = null

    init {
        // Le nettoyage lit un fichier : il ne doit pas empêcher l'écran de
        // s'ouvrir s'il est abîmé.
        runCatching {
            history.prune(settings.retention)
            refreshHistory()
        }
    }

    /**
     * Lance un traitement sans jamais laisser une erreur remonter.
     *
     * Le scope vient de la composition : une exception qui s'en échappe
     * n'affiche pas une erreur, elle emporte l'activité entière. Tout ce que
     * cette classe démarre passe donc par ici, et le moindre incident devient
     * un message affiché à l'utilisateur.
     */
    private fun launchGuarded(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            busyMessage = null
            message = readableMessage(error)
        }
    }

    private fun readableMessage(error: Throwable): String = when (error) {
        is OutOfMemoryError -> "Ce fichier est trop volumineux pour cet appareil"
        is SecurityException -> "L'accès à ce fichier a été refusé par le système"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Ce fichier n'a pas pu être traité"
    }

    val singleSource: SourceFile? get() = selection.firstOrNull().takeIf { selection.size == 1 }

    /** Cibles communes à toute la sélection : une conversion par lot doit valoir pour chaque fichier. */
    val availableTargets: List<TargetFormat>
        get() = when {
            selection.isEmpty() -> emptyList()
            selection.size == 1 -> selection.first().targets
            else -> selection.map { it.targets.toSet() }
                .reduce { shared, next -> shared intersect next }
                .toList()
                .sortedBy { format -> TargetFormat.values().indexOf(format) }
        }

    val canMergeToPdf: Boolean
        get() = selection.size > 1 && selection.all { it.kind == FileKind.IMAGE }

    fun showTab(next: ConverterTab) {
        tab = next
        if (next == ConverterTab.HISTORY) refreshHistory()
    }

    fun dismissMessage() {
        message = null
    }

    /**
     * Cible souhaitée d'avance par un raccourci de l'accueil. Elle n'est
     * retenue que si le fichier réellement choisi la permet.
     */
    private var preferredTarget: TargetFormat? = null

    fun prefer(format: TargetFormat?) {
        preferredTarget = format
    }

    /** Charge les fichiers choisis, puis établit ce qu'on peut réellement en faire. */
    fun select(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchGuarded {
            busyMessage = if (uris.size == 1) "Lecture du fichier…" else "Lecture des fichiers…"
            // La raison du premier échec est conservée : dire « illisible »
            // sans expliquer pourquoi n'aide personne.
            var failure: String? = null
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val source = OutputStore.cacheSource(context, uri)
                        val probe = MediaProbe.inspect(source.cached, source.kind, source.extension)
                        source.copy(targets = probe.targets, detail = probe.detail) to probe.warning
                    }
                        .onFailure { error -> if (failure == null) failure = readableMessage(error) }
                        .getOrNull()
                }
            }
            busyMessage = null

            if (loaded.isEmpty()) {
                message = failure ?: "Ce fichier n'a pas pu être lu"
                return@launchGuarded
            }
            selection.clear()
            selection.addAll(loaded.map { it.first })
            sourceWarning = loaded.firstNotNullOfOrNull { it.second }
            results.clear()
            queue.clear()
            val possible = availableTargets
            target = preferredTarget?.takeIf { it in possible } ?: possible.firstOrNull()
            if (preferredTarget != null && target != preferredTarget) {
                message = "Ce fichier ne peut pas être converti en ${preferredTarget?.label}"
            }
            preferredTarget = null
            options = options.copy(quality = settings.defaultQuality)
            stage = Stage.CONFIGURE
            tab = ConverterTab.HOME
            recomputeEstimate()
        }
    }

    /** Raccourci de l'accueil : on ouvre le sélecteur puis on présélectionne la cible. */
    fun chooseTarget(format: TargetFormat) {
        if (format !in availableTargets) return
        target = format
        recomputeEstimate()
    }

    fun updateOptions(transform: (ConversionOptions) -> ConversionOptions) {
        options = transform(options)
        recomputeEstimate()
    }

    private fun recomputeEstimate() {
        val source = singleSource
        val format = target
        if (source == null || format == null) {
            estimatedSize = null
            return
        }
        launchGuarded {
            estimating = true
            try {
                estimatedSize = ConversionEngine.estimateOutputSize(source, format, options)
            } finally {
                estimating = false
            }
        }
    }

    fun start() {
        val format = target ?: return
        if (stage == Stage.RUNNING) return
        conversionJob?.cancel()

        results.clear()
        queue.clear()
        queue.addAll(selection.map { QueueItem(it) })
        stage = Stage.RUNNING

        conversionJob = launchGuarded {
            try {
                if (canMergeToPdf && format == TargetFormat.PDF && options.mergeIntoSinglePdf) {
                    runMerged()
                } else {
                    runQueue(format)
                }
            } finally {
                // L'écran de résultat doit apparaître même si la conversion
                // s'est interrompue : sans cela l'utilisateur reste bloqué sur
                // une progression figée.
                stage = Stage.RESULT
                runCatching { refreshHistory() }
            }
        }
    }

    private suspend fun runQueue(format: TargetFormat) {
        selection.forEachIndexed { index, source ->
            updateItem(index, ItemState.Running(0f))
            val outcome = runCatching {
                ConversionEngine.convert(context, source, format, options) { fraction ->
                    updateItem(index, ItemState.Running(fraction))
                }
            }
            outcome
                .onSuccess { output ->
                    updateItem(index, ItemState.Done(output))
                    results.add(output)
                    record(source, output, null)
                }
                .onFailure { error ->
                    if (error is ConversionCancelled || error is kotlinx.coroutines.CancellationException) {
                        updateItem(index, ItemState.Cancelled)
                    } else {
                        val text = readableMessage(error)
                        updateItem(index, ItemState.Failed(text))
                        record(source, null, text)
                    }
                }
        }
    }

    private suspend fun runMerged() {
        selection.indices.forEach { updateItem(it, ItemState.Running(0f)) }
        runCatching {
            ConversionEngine.mergeImagesToPdf(context, selection.toList(), options) { fraction ->
                selection.indices.forEach { updateItem(it, ItemState.Running(fraction)) }
            }
        }
            .onSuccess { output ->
                selection.indices.forEach { updateItem(it, ItemState.Done(output)) }
                results.add(output)
                selection.firstOrNull()?.let { record(it, output, null) }
            }
            .onFailure { error ->
                if (error is ConversionCancelled || error is kotlinx.coroutines.CancellationException) {
                    selection.indices.forEach { updateItem(it, ItemState.Cancelled) }
                } else {
                    val text = readableMessage(error)
                    selection.indices.forEach { updateItem(it, ItemState.Failed(text)) }
                    selection.firstOrNull()?.let { record(it, null, text) }
                }
            }
    }

    private fun updateItem(index: Int, state: ItemState) {
        if (index in queue.indices) queue[index] = queue[index].copy(state = state)
    }

    private fun record(source: SourceFile, output: OutputFile?, failure: String?) = runCatching {
        history.record(
            HistoryEntry(
                id = history.newId(),
                sourceName = source.displayName,
                sourceExtension = source.extension,
                targetExtension = output?.format?.extension ?: target?.extension.orEmpty(),
                sourceSize = source.sizeBytes,
                outputSize = output?.sizeBytes ?: 0,
                timestamp = System.currentTimeMillis(),
                outputPath = output?.file?.absolutePath,
                succeeded = failure == null,
                message = failure
            )
        )
    }

    fun cancel() {
        conversionJob?.cancel()
        conversionJob = null
        stage = Stage.RESULT
        message = "Conversion annulée"
    }

    /** Revient à l'accueil et libère les copies de travail. */
    fun reset() {
        conversionJob?.cancel()
        conversionJob = null
        selection.clear()
        queue.clear()
        results.clear()
        target = null
        estimatedSize = null
        sourceWarning = null
        stage = Stage.PICK
        tab = ConverterTab.HOME
        launchGuarded { withContext(Dispatchers.IO) { OutputStore.clearSourceCache(context) } }
    }

    fun backToConfigure() {
        stage = Stage.CONFIGURE
    }

    fun refreshHistory() {
        val entries = runCatching { history.list() }.getOrDefault(emptyList())
        historyEntries.clear()
        historyEntries.addAll(entries)
    }

    fun deleteHistory(id: String) {
        history.delete(id)
        refreshHistory()
    }

    fun clearHistory() {
        history.clear()
        refreshHistory()
        message = "Historique effacé"
    }

    fun chooseTheme(mode: com.docssuite.converter.data.ThemeMode) {
        settings.themeMode = mode
        themeMode = mode
    }

    fun chooseDefaultQuality(value: Int) {
        settings.defaultQuality = value
        updateOptions { it.copy(quality = value) }
    }

    fun chooseRetention(retention: com.docssuite.converter.data.HistoryRetention) {
        settings.retention = retention
        history.prune(retention)
        refreshHistory()
    }

    fun report(text: String) {
        message = text
    }
}

@Composable
fun rememberConverterState(): ConverterState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        ConverterState(
            context = context.applicationContext,
            scope = scope,
            settings = ConverterSettings(context.applicationContext),
            history = HistoryStore(context.applicationContext)
        )
    }
}
