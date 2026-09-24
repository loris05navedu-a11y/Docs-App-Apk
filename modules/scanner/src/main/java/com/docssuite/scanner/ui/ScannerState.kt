package com.docssuite.scanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.docssuite.scanner.PageFormat
import com.docssuite.scanner.Pt
import com.docssuite.scanner.Quad
import com.docssuite.scanner.ScanFilter
import com.docssuite.scanner.ScanImaging
import com.docssuite.scanner.ScanPage
import com.docssuite.scanner.ScanSession
import com.docssuite.scanner.buildPdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ScannerStage { PAGES, EDIT }

/**
 * Réglages d'une page en cours de recadrage. Les coins sont exprimés dans
 * le repère de la photo pleine résolution ; [preview], plus petite, sert à
 * l'affichage et à l'aperçu du résultat.
 */
class PageEditor(val page: ScanPage, val preview: Bitmap, val isNew: Boolean) {
    var quad by mutableStateOf(page.quad)
        private set
    var quarterTurns by mutableStateOf(page.quarterTurns)
        private set
    var filter by mutableStateOf(page.filter)
    var showResult by mutableStateOf(false)

    /** Photo pleine résolution → aperçu. */
    val previewScaleX: Float get() = preview.width.toFloat() / page.sourceWidth
    val previewScaleY: Float get() = preview.height.toFloat() / page.sourceHeight

    fun moveCorner(index: Int, to: Pt) {
        quad = quad.withCorner(index, to).clampedTo(page.sourceWidth.toFloat(), page.sourceHeight.toFloat())
    }

    fun useWholePhoto() {
        quad = Quad.inset(page.sourceWidth.toFloat(), page.sourceHeight.toFloat())
    }

    /** Relance la détection ; `false` si aucune feuille nette n'a été trouvée. */
    fun autoDetect(): Boolean {
        val found = ScanImaging.detect(preview) ?: return false
        quad = found.scaled(1f / previewScaleX, 1f / previewScaleY)
            .clampedTo(page.sourceWidth.toFloat(), page.sourceHeight.toFloat())
        return true
    }

    fun rotate(delta: Int) {
        quarterTurns = Math.floorMod(quarterTurns + delta, 4)
    }

    /** Aperçu du résultat, calculé sur la petite image. */
    fun renderPreview(): Bitmap =
        ScanImaging.render(preview, quad.scaled(previewScaleX, previewScaleY), quarterTurns, filter, maxSide = 900)
}

/**
 * État du scanner. Les traitements d'image partent hors du fil principal ;
 * chaque changement de pages est aussitôt écrit sur disque.
 */
class ScannerState(private val context: Context, private val scope: CoroutineScope) {

    private val directory = File(context.cacheDir, "scans")
    private val session = ScanSession(directory)

    val pages = mutableStateListOf<ScanPage>().apply { addAll(session.load()) }

    var stage by mutableStateOf(ScannerStage.PAGES)
        private set
    var editor by mutableStateOf<PageEditor?>(null)
        private set
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Le filtre choisi en dernier devient celui des pages suivantes. */
    var defaultFilter = pages.lastOrNull()?.filter ?: ScanFilter.ENHANCED
        private set

    fun report(text: String) {
        message = text
    }

    fun dismissMessage() {
        message = null
    }

    /**
     * Une erreur qui s'échapperait du scope de composition fermerait l'app :
     * tout passe par ici et devient un message.
     */
    private fun launchGuarded(busy: String?, block: suspend () -> Unit): Job = scope.launch {
        busyMessage = busy
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            message = "Cette photo est trop grande pour la mémoire du téléphone"
        } catch (error: Throwable) {
            message = error.message?.takeIf { it.isNotBlank() } ?: "Cette image n'a pas pu être traitée"
        } finally {
            busyMessage = null
        }
    }

    private fun newFile(prefix: String): File {
        directory.mkdirs()
        return File(directory, "$prefix-${UUID.randomUUID()}.jpg")
    }

    /** Fichier où l'appareil photo écrira la prochaine prise de vue. */
    fun newCaptureFile(): File = newFile("photo")

    fun onPhotoCaptured(file: File, success: Boolean, openEditor: Boolean = true) {
        if (!success || !file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        addPhotos(listOf(file), openEditor)
    }

    /** Photos choisies dans la galerie : copiées d'abord, le fournisseur peut révoquer l'accès plus tard. */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchGuarded(if (uris.size == 1) "Import de la photo…" else "Import de ${uris.size} photos…") {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val file = newFile("photo")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        } ?: throw IOException("Photo illisible")
                        file
                    }.getOrNull()
                }
            }
            if (files.size < uris.size) message = "${uris.size - files.size} photo(s) n'ont pas pu être lues"
            processNewPhotos(files, openEditor = files.size == 1)
        }
    }

    private fun addPhotos(files: List<File>, openEditor: Boolean) {
        launchGuarded("Préparation de la page…") { processNewPhotos(files, openEditor) }
    }

    /** Détecte la feuille et calcule la page pour chaque photo, avec le filtre habituel. */
    private suspend fun processNewPhotos(files: List<File>, openEditor: Boolean) {
        files.forEachIndexed { index, file ->
            if (files.size > 1) busyMessage = "Page ${index + 1} sur ${files.size}…"
            val page = runCatching {
                withContext(Dispatchers.IO) {
                    val source = ScanImaging.decodeSource(file)
                    val quad = ScanImaging.detect(source)
                        ?: Quad.inset(source.width.toFloat(), source.height.toFloat())
                    val output = newFile("page")
                    val rendered = ScanImaging.render(source, quad, 0, defaultFilter)
                    output.writeBytes(ScanImaging.encodeJpeg(rendered))
                    ScanPage(
                        id = UUID.randomUUID().toString(),
                        source = file,
                        sourceWidth = source.width,
                        sourceHeight = source.height,
                        quad = quad,
                        quarterTurns = 0,
                        filter = defaultFilter,
                        rendered = output
                    ).also {
                        source.recycle()
                        rendered.recycle()
                    }
                }
            }.getOrElse { error ->
                file.delete()
                message = if (error is OutOfMemoryError) "Cette photo est trop grande pour la mémoire du téléphone"
                else error.message ?: "Cette photo n'a pas pu être lue"
                null
            } ?: return@forEachIndexed
            pages.add(page)
            persist()
            if (openEditor && files.size == 1) loadEditor(page, isNew = true)
        }
    }

    fun edit(page: ScanPage) {
        launchGuarded("Ouverture de la page…") { loadEditor(page, isNew = false) }
    }

    private suspend fun loadEditor(page: ScanPage, isNew: Boolean) {
        val preview = withContext(Dispatchers.IO) { ScanImaging.decodeSource(page.source, maxSide = PREVIEW_SIDE) }
        editor = PageEditor(page, preview, isNew)
        stage = ScannerStage.EDIT
    }

    /** Recalcule la page en pleine résolution avec les réglages de l'éditeur. */
    fun applyEdit(then: () -> Unit = {}) {
        val current = editor ?: return
        if (!current.quad.isConvex()) {
            message = "Les coins se croisent : replace-les autour de la feuille"
            return
        }
        launchGuarded("Redressement de la page…") {
            val updated = withContext(Dispatchers.IO) {
                val source = ScanImaging.decodeSource(current.page.source)
                val rendered = ScanImaging.render(source, current.quad, current.quarterTurns, current.filter)
                current.page.rendered.writeBytes(ScanImaging.encodeJpeg(rendered))
                source.recycle()
                rendered.recycle()
                current.page.copy(
                    quad = current.quad,
                    quarterTurns = current.quarterTurns,
                    filter = current.filter,
                    version = current.page.version + 1
                )
            }
            val index = pages.indexOfFirst { it.id == updated.id }
            if (index >= 0) pages[index] = updated
            defaultFilter = updated.filter
            persist()
            editor = null
            stage = ScannerStage.PAGES
            then()
        }
    }

    fun closeEditor() {
        editor = null
        stage = ScannerStage.PAGES
    }

    fun move(page: ScanPage, delta: Int) {
        val from = pages.indexOfFirst { it.id == page.id }
        val to = from + delta
        if (from < 0 || to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
        persist()
    }

    fun delete(page: ScanPage) {
        pages.removeAll { it.id == page.id }
        page.source.delete()
        page.rendered.delete()
        persist()
    }

    /** Recommence un scan vierge. */
    fun clearAll() {
        pages.clear()
        editor = null
        stage = ScannerStage.PAGES
        session.clear()
    }

    private fun persist() {
        runCatching { session.save(pages.toList()) }
    }

    /** Le PDF de toutes les pages, dans l'ordre affiché. */
    suspend fun buildPdfBytes(format: PageFormat, title: String): ByteArray = withContext(Dispatchers.IO) {
        val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        buildPdf(pages.toList().map { it.rendered.readBytes() }, format, title, date)
    }

    /** Lance [action] avec le PDF, en affichant l'attente et en rapportant l'échec. */
    fun withPdf(format: PageFormat, title: String, action: suspend (ByteArray) -> Unit) {
        if (pages.isEmpty()) return
        launchGuarded("Création du PDF…") { action(buildPdfBytes(format, title)) }
    }

    fun defaultTitle(): String =
        "Scan du " + SimpleDateFormat("dd-MM-yyyy HH'h'mm", Locale.FRANCE).format(Date())

    companion object {
        const val PREVIEW_SIDE = 1400
    }
}

@Composable
fun rememberScannerState(): ScannerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { ScannerState(context.applicationContext, scope) }
}
