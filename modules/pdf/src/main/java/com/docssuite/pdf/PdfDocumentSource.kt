package com.docssuite.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File

/**
 * Un PDF ouvert, prêt à être rendu page par page.
 *
 * `PdfRenderer` impose deux contraintes : il lui faut un fichier accessible en
 * accès direct (pas un flux), et une seule page peut être ouverte à la fois.
 * D'où la copie en cache à l'ouverture et le verrou autour du rendu.
 */
class PdfDocumentSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val cacheFile: File?
) : Closeable {

    private val lock = Mutex()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Proportions d'une page, pour réserver sa place avant même de la dessiner. */
    suspend fun aspectRatio(index: Int): Float = lock.withLock {
        if (closed) return 1f / 1.414f
        runCatching {
            renderer.openPage(index).use { page ->
                if (page.height == 0) 1f / 1.414f else page.width.toFloat() / page.height
            }
        }.getOrDefault(1f / 1.414f)
    }

    /** Rend la page [index] sur une largeur de [widthPx] pixels. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed || index !in 0 until renderer.pageCount || widthPx <= 0) return null
        runCatching {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width)
                    .toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                // Le rendu est transparent par défaut : sans ce fond blanc, un
                // PDF s'afficherait en noir sur noir en thème sombre.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        cacheFile?.delete()
    }

    companion object {
        /**
         * Ouvre un PDF depuis ses octets. Lève [PdfOpenException] avec un
         * message affichable si le fichier est abîmé ou protégé par mot de
         * passe — `PdfRenderer` ne sait pas déchiffrer.
         */
        fun open(context: Context, bytes: ByteArray, name: String): PdfDocumentSource {
            val directory = File(context.cacheDir, "pdf").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val file = File(directory, "${name.hashCode()}.pdf")
            return try {
                file.writeBytes(bytes)
                val descriptor = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (error: Throwable) {
                    descriptor.close()
                    throw error
                }
                PdfDocumentSource(descriptor, renderer, file)
            } catch (error: Throwable) {
                file.delete()
                throw PdfOpenException(
                    when {
                        error is SecurityException ->
                            "Ce PDF est protégé par un mot de passe"
                        else -> "Ce PDF est illisible ou endommagé"
                    },
                    error
                )
            }
        }
    }
}

class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)
