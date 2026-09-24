package com.docssuite.pdftools.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.docssuite.pdftools.OverlayItem
import com.docssuite.pdftools.PageGeometry
import com.docssuite.pdftools.PageSelection
import com.docssuite.pdftools.PdfAssembler
import com.docssuite.pdftools.PdfEncryptedException
import com.docssuite.pdftools.PdfFile
import com.docssuite.pdftools.PdfNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class SignTool(val label: String) { SIGNATURE("Signature"), TEXT("Texte"), DATE("Date"), CHECK("Coche") }

/** Un élément posé sur une page, en fractions de la page affichée (coin haut-gauche). */
data class Placed(
    val id: String,
    val page: Int,
    val u: Float,
    val v: Float,
    /** Texte et coche : hauteur des lettres (fraction de la largeur). Image : largeur (fraction de la largeur). */
    val size: Float,
    val text: String = "",
    val check: Boolean = false,
    val image: Bitmap? = null
) {
    fun toOverlay(pageAspect: Float): OverlayItem = when {
        image != null -> {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            // Hauteur en fraction de la hauteur affichée : largeur × proportions de l'image × largeur/hauteur de la page.
            val height = size * image.height / image.width * pageAspect
            OverlayItem.Image(u, v, size, height, pixels, image.width, image.height)
        }
        check -> OverlayItem.Check(u, v, size)
        else -> OverlayItem.Text(u, v, text, size)
    }
}

/**
 * Signer ou remplir un PDF : les éléments posés restent modifiables jusqu'à
 * l'enregistrement, qui produit un nouveau PDF (l'original n'est pas touché).
 */
class PdfSignState(private val context: Context, private val scope: CoroutineScope) {

    var file by mutableStateOf<PdfFile?>(null)
        private set
    var name by mutableStateOf("")
        private set
    val placed = mutableStateListOf<Placed>()
    var selectedId by mutableStateOf<String?>(null)
    var armed by mutableStateOf<SignTool?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val signatureFile = File(context.filesDir, "signature.png")
    var savedSignature by mutableStateOf<Bitmap?>(runCatching { BitmapFactory.decodeFile(signatureFile.path) }.getOrNull())
        private set

    private var cacheFile: File? = null
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private val renderLock = Mutex()

    fun report(text: String) {
        message = text
    }

    fun dismissMessage() {
        message = null
    }

    fun open(uri: Uri) {
        scope.launch {
            busy = "Ouverture du PDF…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Fichier illisible")
                    val parsed = PdfFile.parse(bytes)
                    val cache = File(context.cacheDir, "signer-${UUID.randomUUID()}.pdf").apply { writeBytes(bytes) }
                    Triple(parsed, cache, displayName(uri))
                }
            }
            busy = null
            result
                .onSuccess { (parsed, cache, display) -> load(parsed, cache, display) }
                .onFailure {
                    message = when (it) {
                        is PdfEncryptedException -> "Ce PDF est protégé par un mot de passe : il ne peut pas être signé ici"
                        else -> "Ce PDF n'a pas pu être ouvert : ${it.message ?: "fichier illisible"}"
                    }
                }
        }
    }

    /** Charge un PDF déjà lu (utilisé aussi par les tests). */
    fun load(parsed: PdfFile, cache: File, display: String) {
        close()
        file = parsed
        cacheFile = cache
        name = display.removeSuffix(".pdf").removeSuffix(".PDF")
        placed.clear()
        selectedId = null
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull() ?: "document.pdf"

    fun geometry(page: Int): PageGeometry {
        val f = file!!
        val p = f.pages[page]
        val rotation = (f.resolve(p.attribute("Rotate")) as? PdfNumber)?.intValue ?: 0
        return PageGeometry.of(f, p, rotation)
    }

    /** L'élément que pose l'outil armé, centré sur le point touché. */
    fun placeAt(page: Int, u: Float, v: Float, aspect: Float) {
        val tool = armed ?: return
        val item = when (tool) {
            SignTool.SIGNATURE -> {
                val signature = savedSignature ?: return
                val width = 0.35f
                val height = width * signature.height / signature.width * aspect
                Placed(UUID.randomUUID().toString(), page, u - width / 2, v - height / 2, width, image = signature)
            }
            SignTool.TEXT -> Placed(UUID.randomUUID().toString(), page, u, v - 0.012f, 0.025f, text = "Texte")
            SignTool.DATE -> Placed(
                UUID.randomUUID().toString(), page, u, v - 0.012f, 0.025f,
                text = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date())
            )
            SignTool.CHECK -> Placed(UUID.randomUUID().toString(), page, u - 0.02f, v - 0.02f, 0.04f, check = true)
        }
        placed.add(item.copy(u = item.u.coerceIn(0f, 0.95f), v = item.v.coerceIn(0f, 0.97f)))
        selectedId = item.id
        armed = null
    }

    fun update(id: String, transform: (Placed) -> Placed) {
        val i = placed.indexOfFirst { it.id == id }
        if (i >= 0) placed[i] = transform(placed[i])
    }

    fun remove(id: String) {
        placed.removeAll { it.id == id }
        if (selectedId == id) selectedId = null
    }

    /** Garde la signature dessinée pour les prochaines fois. */
    fun rememberSignature(bitmap: Bitmap) {
        savedSignature = bitmap
        runCatching { signatureFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun forgetSignature() {
        savedSignature = null
        signatureFile.delete()
    }

    fun signedTitle() = "$name (signé)"

    /** Le PDF avec tout ce qui a été posé ; les pages sans ajout sont recopiées telles quelles. */
    fun build(): ByteArray {
        val f = file ?: error("Aucun PDF ouvert")
        return PdfAssembler.assemble(
            f.pages.indices.map { index ->
                val g = geometry(index)
                val aspect = g.displayWidth / g.displayHeight
                PageSelection(f, index, overlay = placed.filter { it.page == index }.map { it.toOverlay(aspect) })
            },
            signedTitle()
        )
    }

    fun withPdf(action: suspend (ByteArray) -> Unit) {
        scope.launch {
            busy = "Création du PDF signé…"
            val result = withContext(Dispatchers.IO) { runCatching { build() } }
            busy = null
            result.onSuccess { action(it) }.onFailure { message = it.message ?: "Enregistrement impossible" }
        }
    }

    /** Rendu d'une page, en largeur donnée ; `null` si Android ne sait pas la dessiner. */
    suspend fun render(page: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        renderLock.withLock {
            runCatching {
                val r = renderer ?: run {
                    val d = ParcelFileDescriptor.open(cacheFile!!, ParcelFileDescriptor.MODE_READ_ONLY)
                    descriptor = d
                    PdfRenderer(d).also { renderer = it }
                }
                r.openPage(page).use { p ->
                    val height = maxOf(1, widthPx * p.height / maxOf(1, p.width))
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        p.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
        }
    }

    fun close() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
        cacheFile?.delete()
        cacheFile = null
    }
}
