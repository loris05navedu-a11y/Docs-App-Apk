package com.docssuite.converter.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.docssuite.converter.data.OutputStore
import com.docssuite.converter.model.ConversionOptions
import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.OutputFile
import com.docssuite.converter.model.SourceFile
import com.docssuite.converter.model.TargetFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Aiguillage des conversions. Chaque branche appelle un convertisseur réel ;
 * une combinaison source/cible non gérée lève une erreur explicite plutôt que
 * de produire un fichier vide.
 */
object ConversionEngine {

    suspend fun convert(
        context: Context,
        source: SourceFile,
        target: TargetFormat,
        options: ConversionOptions,
        onProgress: (Float) -> Unit
    ): OutputFile = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val isCancelled = { job?.isActive == false }

        OutputStore.ensureSpaceFor(context, estimateOutputSize(source, target, options) ?: source.sizeBytes)

        val directory = OutputStore.outputDirectory(context)
        var effectiveFormat = target
        val destination: File

        if (target == TargetFormat.ZIP) {
            destination = OutputStore.uniqueOutput(context, source.baseName, "zip")
            DocumentConversions.toZip(
                listOf(source.cached), listOf(source.displayName), destination, onProgress, isCancelled
            )
        } else when (source.kind) {
            FileKind.IMAGE -> when (target) {
                TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP -> {
                    destination = OutputStore.uniqueOutput(context, source.baseName, target.extension)
                    ImageConversions.convert(source.cached, destination, target, options, onProgress)
                }
                TargetFormat.PDF -> {
                    destination = OutputStore.uniqueOutput(context, source.baseName, "pdf")
                    PdfConversions.fromImages(listOf(source.cached), destination, options, onProgress, isCancelled)
                }
                else -> throw unsupported(source, target)
            }

            FileKind.PDF -> when (target) {
                TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP -> {
                    val (file, format) = PdfConversions.toImages(
                        source.cached, directory, uniqueBase(context, source.baseName),
                        target, options, onProgress, isCancelled
                    )
                    destination = file
                    effectiveFormat = format
                }
                else -> throw unsupported(source, target)
            }

            FileKind.AUDIO, FileKind.VIDEO -> when (target) {
                TargetFormat.WAV -> {
                    destination = OutputStore.uniqueOutput(context, source.baseName, "wav")
                    AudioConversions.toWav(source.cached, destination, options, onProgress, isCancelled)
                }
                TargetFormat.M4A -> {
                    destination = OutputStore.uniqueOutput(context, source.baseName, "m4a")
                    AudioConversions.toM4a(source.cached, destination, options, onProgress, isCancelled)
                }
                TargetFormat.MP4 -> {
                    destination = OutputStore.uniqueOutput(context, source.baseName, "mp4")
                    VideoConversions.toMp4(source.cached, destination, options, onProgress, isCancelled)
                }
                else -> throw unsupported(source, target)
            }

            FileKind.TEXT -> when (target) {
                TargetFormat.PDF -> {
                    destination = OutputStore.uniqueOutput(context, source.baseName, "pdf")
                    PdfConversions.fromText(
                        DocumentConversions.readText(source.cached),
                        destination, options, onProgress, isCancelled
                    )
                }
                TargetFormat.TXT -> {
                    destination = OutputStore.uniqueOutput(context, source.baseName, "txt")
                    DocumentConversions.toPlainText(source.cached, destination)
                    onProgress(1f)
                }
                else -> throw unsupported(source, target)
            }

            FileKind.ARCHIVE, FileKind.OTHER -> throw unsupported(source, target)
        }

        if (!destination.exists() || destination.length() == 0L) {
            destination.delete()
            throw ConversionException("La conversion n'a produit aucun fichier exploitable")
        }

        OutputFile(
            file = destination,
            displayName = destination.name,
            format = effectiveFormat,
            sizeBytes = destination.length()
        )
    }

    /** Réunit plusieurs images dans un PDF unique, une image par page. */
    suspend fun mergeImagesToPdf(
        context: Context,
        sources: List<SourceFile>,
        options: ConversionOptions,
        onProgress: (Float) -> Unit
    ): OutputFile = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val isCancelled = { job?.isActive == false }
        val images = sources.filter { it.kind == FileKind.IMAGE }
        if (images.isEmpty()) throw ConversionException("Aucune image à réunir")

        OutputStore.ensureSpaceFor(context, images.sumOf { it.sizeBytes })
        val destination = OutputStore.uniqueOutput(context, "Images réunies", "pdf")
        PdfConversions.fromImages(images.map { it.cached }, destination, options, onProgress, isCancelled)

        if (!destination.exists() || destination.length() == 0L) {
            destination.delete()
            throw ConversionException("Le PDF n'a pas pu être créé")
        }
        OutputFile(destination, destination.name, TargetFormat.PDF, destination.length())
    }

    /**
     * Taille attendue du résultat, ou `null` quand elle n'est pas prévisible.
     * Pour l'audio elle se déduit du débit et de la durée ; pour une image elle
     * est mesurée en encodant réellement une version réduite, puis extrapolée.
     */
    suspend fun estimateOutputSize(
        source: SourceFile,
        target: TargetFormat,
        options: ConversionOptions
    ): Long? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                target == TargetFormat.MP4 -> source.sizeBytes
                target == TargetFormat.WAV -> {
                    val durationMs = MediaProbe.durationMs(source.cached)
                    if (durationMs <= 0) return@runCatching null
                    val rate = options.sampleRate.hertz.takeIf { it > 0 } ?: 44_100
                    durationMs * rate / 1000 * 2 * 2 + 44
                }
                target == TargetFormat.M4A -> {
                    val durationMs = MediaProbe.durationMs(source.cached)
                    if (durationMs <= 0) return@runCatching null
                    durationMs * options.audioBitrate.bitsPerSecond / 8 / 1000
                }
                source.kind == FileKind.IMAGE &&
                    target in setOf(TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP) ->
                    estimateImage(source.cached, target, options)
                target == TargetFormat.TXT -> source.sizeBytes
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Encode un échantillon réduit d'un facteur 8 — donc 64 fois moins de
     * pixels — et remonte à la taille complète. L'ordre de grandeur est juste,
     * ce que l'interface annonce d'ailleurs comme une estimation.
     */
    private fun estimateImage(
        source: File,
        target: TargetFormat,
        options: ConversionOptions
    ): Long? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        val sample = 8
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val preview = BitmapFactory.decodeFile(source.absolutePath, decodeOptions) ?: return null
        return try {
            val buffer = ByteArrayOutputStream()
            val format = when (target) {
                TargetFormat.PNG -> Bitmap.CompressFormat.PNG
                TargetFormat.JPG -> Bitmap.CompressFormat.JPEG
                else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            if (!preview.compress(format, options.quality, buffer)) return null
            val ratio = options.scale.factor * options.scale.factor
            (buffer.size().toLong() * sample * sample * ratio).toLong()
        } finally {
            preview.recycle()
        }
    }

    private fun uniqueBase(context: Context, baseName: String): String =
        OutputStore.uniqueOutput(context, baseName, "tmp").nameWithoutExtension

    private fun unsupported(source: SourceFile, target: TargetFormat) = ConversionException(
        "Un fichier ${source.extension.uppercase()} ne peut pas être converti en ${target.label}"
    )
}
