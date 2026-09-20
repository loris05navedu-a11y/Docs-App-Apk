package com.docssuite.converter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.docssuite.converter.model.ConversionOptions
import com.docssuite.converter.model.PageOrientation
import com.docssuite.converter.model.PageSize
import com.docssuite.converter.model.TargetFormat
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Lecture et écriture de PDF avec les seules API du système :
 * `PdfRenderer` pour rasteriser, `PdfDocument` pour produire.
 */
object PdfConversions {

    /** Un point PDF vaut 1/72 de pouce ; ×2 place le rendu à 144 dpi. */
    private const val BASE_RENDER_SCALE = 2f
    private const val MARGIN_PT = 40f

    fun pageCount(file: File): Int = withRenderer(file) { it.pageCount }

    /**
     * Rasterise le PDF. Une page produit une image ; plusieurs pages produisent
     * une archive, seule façon de rendre un document entier en un fichier.
     */
    fun toImages(
        source: File,
        destinationDirectory: File,
        baseName: String,
        target: TargetFormat,
        options: ConversionOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Pair<File, TargetFormat> = withRenderer(source) { renderer ->
        val pages = renderer.pageCount
        if (pages == 0) throw ConversionException("Ce PDF ne contient aucune page")

        if (pages == 1) {
            val destination = File(destinationDirectory, "$baseName.${target.extension}")
            renderPage(renderer, 0, target, options, destination)
            onProgress(1f)
            return@withRenderer destination to target
        }

        val destination = File(destinationDirectory, "$baseName.zip")
        val scratch = File(destinationDirectory, "page-scratch.${target.extension}")
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                for (index in 0 until pages) {
                    if (isCancelled()) throw ConversionCancelled()
                    renderPage(renderer, index, target, options, scratch)
                    val name = String.format(Locale.ROOT, "%s-%03d.%s", baseName, index + 1, target.extension)
                    zip.putNextEntry(ZipEntry(name))
                    scratch.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    onProgress((index + 1f) / pages)
                }
            }
        } finally {
            scratch.delete()
        }
        destination to TargetFormat.ZIP
    }

    private fun renderPage(
        renderer: PdfRenderer,
        index: Int,
        target: TargetFormat,
        options: ConversionOptions,
        destination: File
    ) {
        renderer.openPage(index).use { page ->
            val scale = BASE_RENDER_SCALE * options.scale.factor
            val width = max(1, (page.width * scale).toInt())
            val height = max(1, (page.height * scale).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // Les zones sans fond sont transparentes dans un PDF ; sans ce
                // blanc, un JPG les rendrait noires.
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                destination.outputStream().use { output ->
                    val format = when (target) {
                        TargetFormat.PNG -> Bitmap.CompressFormat.PNG
                        TargetFormat.JPG -> Bitmap.CompressFormat.JPEG
                        TargetFormat.WEBP -> webpFormat(options.quality)
                        else -> throw ConversionException("${target.label} n'est pas un format d'image")
                    }
                    if (!bitmap.compress(format, options.quality, output)) {
                        throw ConversionException("La page ${index + 1} n'a pas pu être encodée")
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(quality: Int): Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS
            else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    /** Assemble une ou plusieurs images en un PDF, une image par page. */
    fun fromImages(
        sources: List<File>,
        destination: File,
        options: ConversionOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        if (sources.isEmpty()) throw ConversionException("Aucune image à convertir")
        val document = PdfDocument()
        try {
            sources.forEachIndexed { index, source ->
                if (isCancelled()) throw ConversionCancelled()
                val bitmap = ImageConversions.decode(source, options)
                try {
                    val (pageWidth, pageHeight) = pageDimensions(options, bitmap.width, bitmap.height)
                    val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = document.startPage(info)
                    drawCentred(page.canvas, bitmap, pageWidth, pageHeight, options.pageSize)
                    document.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
                onProgress((index + 1f) / sources.size)
            }
            destination.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun pageDimensions(
        options: ConversionOptions,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<Int, Int> {
        if (options.pageSize == PageSize.FIT_IMAGE) {
            return max(1, imageWidth) to max(1, imageHeight)
        }
        val portraitWidth = options.pageSize.widthPt
        val portraitHeight = options.pageSize.heightPt
        return if (options.orientation == PageOrientation.LANDSCAPE) {
            portraitHeight to portraitWidth
        } else {
            portraitWidth to portraitHeight
        }
    }

    private fun drawCentred(
        canvas: Canvas,
        bitmap: Bitmap,
        pageWidth: Int,
        pageHeight: Int,
        pageSize: PageSize
    ) {
        canvas.drawColor(Color.WHITE)
        if (pageSize == PageSize.FIT_IMAGE) {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, pageWidth, pageHeight), null)
            return
        }
        val available = Rect(
            MARGIN_PT.toInt(),
            MARGIN_PT.toInt(),
            pageWidth - MARGIN_PT.toInt(),
            pageHeight - MARGIN_PT.toInt()
        )
        val ratio = min(
            available.width().toFloat() / bitmap.width,
            available.height().toFloat() / bitmap.height
        )
        val drawWidth = (bitmap.width * ratio).toInt()
        val drawHeight = (bitmap.height * ratio).toInt()
        val left = (pageWidth - drawWidth) / 2
        val top = (pageHeight - drawHeight) / 2
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + drawWidth, top + drawHeight), null)
    }

    /** Met un texte en page dans un PDF, avec césure et pagination réelles. */
    fun fromText(
        text: String,
        destination: File,
        options: ConversionOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val size = if (options.pageSize == PageSize.FIT_IMAGE) PageSize.A4 else options.pageSize
        val portraitWidth = size.widthPt
        val portraitHeight = size.heightPt
        val pageWidth: Int
        val pageHeight: Int
        if (options.orientation == PageOrientation.LANDSCAPE) {
            pageWidth = portraitHeight
            pageHeight = portraitWidth
        } else {
            pageWidth = portraitWidth
            pageHeight = portraitHeight
        }

        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        val contentWidth = pageWidth - 2 * MARGIN_PT.toInt()
        val contentHeight = pageHeight - 2 * MARGIN_PT.toInt()
        val body = text.ifBlank { " " }
        val layout = StaticLayout.Builder
            .obtain(body, 0, body.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .setIncludePad(false)
            .build()

        val document = PdfDocument()
        try {
            var line = 0
            var pageNumber = 1
            while (line < layout.lineCount) {
                if (isCancelled()) throw ConversionCancelled()
                val top = layout.getLineTop(line)
                // Dernière ligne qui tient entièrement sur la page ; on avance
                // d'au moins une ligne pour ne jamais boucler sur un paragraphe
                // plus haut que la page.
                var last = line
                while (
                    last + 1 < layout.lineCount &&
                    layout.getLineBottom(last + 1) - top <= contentHeight
                ) {
                    last++
                }
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = document.startPage(info)
                page.canvas.apply {
                    drawColor(Color.WHITE)
                    save()
                    translate(MARGIN_PT, MARGIN_PT - top)
                    clipRect(
                        0f,
                        top.toFloat(),
                        contentWidth.toFloat(),
                        (top + contentHeight).toFloat()
                    )
                    layout.draw(this)
                    restore()
                }
                document.finishPage(page)
                onProgress(min(0.99f, (last + 1f) / layout.lineCount))
                line = last + 1
                pageNumber++
            }
            destination.outputStream().use { document.writeTo(it) }
            onProgress(1f)
        } finally {
            document.close()
        }
    }

    private fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val opened = try {
                PdfRenderer(descriptor)
            } catch (error: SecurityException) {
                throw ConversionException("Ce PDF est protégé par un mot de passe")
            } catch (error: java.io.IOException) {
                throw ConversionException("Ce PDF est abîmé et ne peut pas être ouvert")
            }
            renderer = opened
            return block(opened)
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }
}

/** Levée quand l'utilisateur interrompt la conversion. */
class ConversionCancelled : Exception("Conversion annulée")
