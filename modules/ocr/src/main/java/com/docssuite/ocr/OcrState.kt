package com.docssuite.ocr

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.docssuite.scanner.Quad
import com.docssuite.scanner.ScanFilter
import com.docssuite.scanner.ScanImaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Une image lue : l'image analysée (redressée si une feuille a été trouvée) et ce qui y a été reconnu. */
data class ReadImage(val image: File, val page: OcrPage)

/**
 * État de la lecture de texte. Chaque photo est d'abord redressée quand on
 * y trouve une feuille (le même outil que le scanner) : un texte de
 * travers ou en perspective est bien moins bien reconnu.
 */
class OcrState(private val context: Context, private val scope: CoroutineScope, private val engine: OcrEngine) {

    private val directory = File(context.cacheDir, "ocr").apply { mkdirs() }

    val images = mutableStateListOf<ReadImage>()

    /** Le texte, modifiable ; les pages ajoutées ensuite viennent à la suite, sans écraser les retouches. */
    var text by mutableStateOf("")
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun dismissMessage() {
        message = null
    }

    fun report(text: String) {
        message = text
    }

    fun newCaptureFile(): File = File(directory, "photo-${UUID.randomUUID()}.jpg")

    fun onPhotoCaptured(file: File, success: Boolean) {
        if (!success || !file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        read(listOf(file))
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busyMessage = "Import…"
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val file = newCaptureFile()
                        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                            ?: throw IOException("Image illisible")
                        file
                    }.getOrNull()
                }
            }
            busyMessage = null
            if (files.size < uris.size) message = "${uris.size - files.size} image(s) n'ont pas pu être lues"
            read(files)
        }
    }

    private fun read(files: List<File>) {
        scope.launch {
            var found = 0
            files.forEachIndexed { index, file ->
                busyMessage = if (files.size == 1) "Lecture du texte…" else "Lecture du texte… image ${index + 1} sur ${files.size}"
                val result = runCatching {
                    val prepared = withContext(Dispatchers.IO) { prepare(file) }
                    val page = engine.recognize(prepared.second)
                    prepared.second.recycle()
                    ReadImage(prepared.first, page)
                }
                result
                    .onSuccess { read ->
                        images.add(read)
                        val pageText = TextLayout.pageText(read.page)
                        if (pageText.isNotBlank()) {
                            found++
                            text = if (text.isBlank()) pageText else text.trimEnd() + "\n\n" + pageText
                        }
                    }
                    .onFailure { error ->
                        message = if (error is OutOfMemoryError) "Cette image est trop grande pour la mémoire du téléphone"
                        else "Lecture impossible : ${error.message ?: "erreur inconnue"}"
                    }
            }
            busyMessage = null
            if (found == 0 && message == null && files.isNotEmpty()) {
                message = "Aucun texte trouvé : essaie une photo plus nette, bien éclairée et prise de face"
            }
        }
    }

    /** Décode (orientation comprise), redresse la feuille si on la trouve, garde une copie pour l'affichage. */
    private fun prepare(file: File): Pair<File, android.graphics.Bitmap> {
        val source = ScanImaging.decodeSource(file, maxSide = 2400)
        val quad: Quad? = ScanImaging.detect(source)
        val bitmap = if (quad != null) {
            ScanImaging.render(source, quad, 0, ScanFilter.ORIGINAL, maxSide = 2400).also { source.recycle() }
        } else {
            source
        }
        val shown = File(directory, "lue-${UUID.randomUUID()}.jpg")
        shown.writeBytes(ScanImaging.encodeJpeg(bitmap, 85))
        file.delete()
        return shown to bitmap
    }

    fun removeImage(index: Int) {
        if (index in images.indices) images.removeAt(index).image.delete()
    }

    fun clear() {
        images.forEach { it.image.delete() }
        images.clear()
        text = ""
    }

    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }

    /** Titre proposé pour le document : sa première ligne, raccourcie. */
    fun suggestedTitle(): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(60)?.trimEnd(',', ';', ':')
            ?: "Texte extrait"

    fun dispose() {
        engine.close()
    }
}
