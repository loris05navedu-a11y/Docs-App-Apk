package com.docssuite.pdftools.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.docssuite.pdftools.PageSelection
import com.docssuite.pdftools.PdfAssembler
import com.docssuite.pdftools.PdfEncryptedException
import com.docssuite.pdftools.PdfFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Un PDF ouvert dans l'outil. */
class SourceDocument(val id: String, val name: String, val file: PdfFile, val cacheFile: File, val colorIndex: Int)

/** Une page de la liste de travail : d'où elle vient, sa rotation ajoutée, sa sélection. */
data class WorkPage(
    val id: String,
    val document: SourceDocument,
    val pageIndex: Int,
    val quarterTurns: Int = 0,
    val selected: Boolean = false
)

/**
 * La liste des pages que l'on réorganise, tous fichiers confondus. Rien
 * n'est écrit avant l'export : on ne manipule que des références aux pages
 * d'origine, l'assemblage final se fait en une fois.
 */
class PdfToolsState(private val context: Context, private val scope: CoroutineScope) {

    val documents = mutableStateListOf<SourceDocument>()
    val pages = mutableStateListOf<WorkPage>()

    var busyMessage by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Dernière suppression, pour pouvoir l'annuler. */
    private var beforeDelete: List<WorkPage>? = null
    var deletedCount by mutableStateOf(0)
        private set

    private val directory = File(context.cacheDir, "pdftools").apply {
        // Les copies de la séance précédente n'ont plus de propriétaire.
        deleteRecursively()
        mkdirs()
    }

    val selectedCount: Int get() = pages.count { it.selected }
    val hasSelection: Boolean get() = pages.any { it.selected }

    fun report(text: String) {
        message = text
    }

    fun dismissMessage() {
        message = null
    }

    private fun launchGuarded(busy: String?, block: suspend () -> Unit): Job = scope.launch {
        busyMessage = busy
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            message = "Ce PDF est trop volumineux pour la mémoire du téléphone"
        } catch (error: Throwable) {
            message = error.message?.takeIf { it.isNotBlank() } ?: "Opération impossible"
        } finally {
            busyMessage = null
        }
    }

    // ------------------------------------------------------------ import

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchGuarded(if (uris.size == 1) "Ouverture du PDF…" else "Ouverture de ${uris.size} PDF…") {
            val failures = ArrayList<String>()
            uris.forEach { uri ->
                val name = displayName(uri)
                val opened = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("illisible")
                        if (bytes.size > MAX_BYTES) error("trop volumineux (${bytes.size / (1024 * 1024)} Mo)")
                        val file = PdfFile.parse(bytes)
                        val id = UUID.randomUUID().toString()
                        val cache = File(directory, "$id.pdf").apply { writeBytes(bytes) }
                        SourceDocument(id, name, file, cache, documents.size % DOCUMENT_COLORS)
                    }
                }
                opened
                    .onSuccess { document ->
                        documents.add(document)
                        document.file.pages.indices.forEach { index ->
                            pages.add(WorkPage(UUID.randomUUID().toString(), document, index))
                        }
                    }
                    .onFailure { error ->
                        failures += when (error) {
                            is PdfEncryptedException -> "« $name » est protégé par un mot de passe"
                            is OutOfMemoryError -> "« $name » est trop volumineux"
                            else -> "« $name » : ${error.message ?: "illisible"}"
                        }
                    }
            }
            if (failures.isNotEmpty()) message = failures.joinToString("\n")
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"

    // --------------------------------------------------------- sélection

    fun toggle(page: WorkPage) = update(page.id) { it.copy(selected = !it.selected) }

    fun selectAll(value: Boolean) {
        for (i in pages.indices) if (pages[i].selected != value) pages[i] = pages[i].copy(selected = value)
    }

    private fun update(id: String, transform: (WorkPage) -> WorkPage) {
        val index = pages.indexOfFirst { it.id == id }
        if (index >= 0) pages[index] = transform(pages[index])
    }

    // ----------------------------------------------------- modifications

    /** Tourne la sélection (ou toutes les pages s'il n'y en a pas) d'un quart de tour. */
    fun rotateSelected(delta: Int) {
        val all = !hasSelection
        for (i in pages.indices) {
            if (all || pages[i].selected) {
                pages[i] = pages[i].copy(quarterTurns = Math.floorMod(pages[i].quarterTurns + delta, 4))
            }
        }
    }

    /**
     * Déplace les pages sélectionnées d'un cran, en bloc : chacune saute
     * par-dessus sa voisine non sélectionnée, l'ordre entre elles est gardé.
     */
    fun moveSelected(delta: Int) {
        if (delta < 0) {
            for (i in 1 until pages.size) {
                if (pages[i].selected && !pages[i - 1].selected) swap(i, i - 1)
            }
        } else {
            for (i in pages.size - 2 downTo 0) {
                if (pages[i].selected && !pages[i + 1].selected) swap(i, i + 1)
            }
        }
    }

    private fun swap(a: Int, b: Int) {
        val tmp = pages[a]
        pages[a] = pages[b]
        pages[b] = tmp
    }

    fun deleteSelected() {
        if (!hasSelection) return
        beforeDelete = pages.toList()
        deletedCount = selectedCount
        pages.removeAll { it.selected }
    }

    fun undoDelete() {
        val snapshot = beforeDelete ?: return
        pages.clear()
        pages.addAll(snapshot)
        beforeDelete = null
        deletedCount = 0
    }

    fun forgetUndo() {
        beforeDelete = null
        deletedCount = 0
    }

    /** Recommence : plus aucun document ouvert. */
    fun clear() {
        pages.clear()
        documents.clear()
        beforeDelete = null
        deletedCount = 0
        renderers.values.forEach { runCatching { it.close() } }
        renderers.clear()
        directory.listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------ export

    fun defaultTitle(onlySelection: Boolean): String {
        val base = documents.singleOrNull()?.name?.removeSuffix(".pdf")?.removeSuffix(".PDF")
            ?: if (documents.isEmpty()) "Document" else "Document fusionné"
        return if (onlySelection) "$base (extrait)" else base
    }

    fun assemble(onlySelection: Boolean, title: String): ByteArray {
        val chosen = if (onlySelection) pages.filter { it.selected } else pages.toList()
        return PdfAssembler.assemble(
            chosen.map { PageSelection(it.document.file, it.pageIndex, it.quarterTurns) },
            title
        )
    }

    fun withPdf(onlySelection: Boolean, title: String, action: suspend (ByteArray) -> Unit) {
        launchGuarded("Création du PDF…") {
            val bytes = withContext(Dispatchers.IO) { assemble(onlySelection, title) }
            action(bytes)
        }
    }

    // ---------------------------------------------------------- vignettes

    private val renderers = HashMap<String, Renderer>()
    private val renderLock = Mutex()

    private class Renderer(val descriptor: ParcelFileDescriptor, val renderer: PdfRenderer) {
        fun close() {
            renderer.close()
            descriptor.close()
        }
    }

    /**
     * Vignette d'une page, déjà tournée. `null` si Android ne sait pas
     * dessiner ce fichier : la page reste manipulable, avec son numéro.
     * `PdfRenderer` n'ouvre qu'une page à la fois : tout passe par un verrou.
     */
    suspend fun thumbnail(page: WorkPage, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        renderLock.withLock {
            runCatching {
                val entry = renderers.getOrPut(page.document.id) {
                    val descriptor = ParcelFileDescriptor.open(page.document.cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    Renderer(descriptor, PdfRenderer(descriptor))
                }
                entry.renderer.openPage(page.pageIndex).use { pdfPage ->
                    val height = maxOf(1, widthPx * pdfPage.height / maxOf(1, pdfPage.width))
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (page.quarterTurns == 0) bitmap
                    else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f * page.quarterTurns) }, true)
                }
            }.getOrNull()
        }
    }

    fun dispose() {
        renderers.values.forEach { runCatching { it.close() } }
        renderers.clear()
    }

    companion object {
        /** Au-delà, le fichier tiendrait mal en mémoire avec sa copie produite. */
        const val MAX_BYTES = 120 * 1024 * 1024
        const val DOCUMENT_COLORS = 6
    }
}

@Composable
fun rememberPdfToolsState(): PdfToolsState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { PdfToolsState(context.applicationContext, scope) }
}
