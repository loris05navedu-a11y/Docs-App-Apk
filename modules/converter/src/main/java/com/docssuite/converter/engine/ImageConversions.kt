package com.docssuite.converter.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import com.docssuite.converter.model.ConversionOptions
import com.docssuite.converter.model.Rotation
import com.docssuite.converter.model.TargetFormat
import java.io.File

/**
 * Conversions d'images, entièrement locales : `BitmapFactory` décode,
 * `Bitmap.compress` réencode. Les deux sont fournis par Android, aucune
 * bibliothèque externe n'intervient.
 */
object ImageConversions {

    /**
     * Tags décrivant la prise de vue. Ils survivent à la conversion quand
     * l'utilisateur demande de conserver les métadonnées ; l'orientation en est
     * volontairement exclue, puisqu'elle est déjà appliquée aux pixels.
     */
    private val PRESERVED_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF
    )

    fun convert(
        source: File,
        destination: File,
        target: TargetFormat,
        options: ConversionOptions,
        onProgress: (Float) -> Unit
    ) {
        onProgress(0.05f)
        val bitmap = decode(source, options)
        onProgress(0.55f)
        try {
            destination.outputStream().use { output ->
                val ok = bitmap.compress(compressFormat(target, options.quality), options.quality, output)
                if (!ok) throw ConversionException("L'encodage en ${target.label} a échoué")
            }
        } finally {
            bitmap.recycle()
        }
        onProgress(0.9f)
        if (options.keepMetadata && target == TargetFormat.JPG) {
            copyExif(source, destination)
        }
        onProgress(1f)
    }

    /**
     * Décode en appliquant l'orientation EXIF, la rotation demandée et
     * l'échelle. Le sous-échantillonnage protège d'un dépassement mémoire sur
     * les très grandes images : la taille réellement obtenue est ensuite
     * affichée dans le résultat.
     */
    fun decode(source: File, options: ConversionOptions): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ConversionException("Ce fichier n'est pas une image lisible")
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
            ?: throw ConversionException("Cette image n'a pas pu être décodée")

        val degrees = exifRotation(source) + options.rotation.degrees
        val scale = options.scale.factor
        if (degrees % 360 == 0 && scale == 1f) return decoded

        val matrix = Matrix()
        if (scale != 1f) matrix.postScale(scale, scale)
        if (degrees % 360 != 0) matrix.postRotate((degrees % 360).toFloat())
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    /**
     * Un bitmap ARGB occupe 4 octets par pixel. On reste sous le quart du tas
     * disponible, marge qui laisse la place à la copie produite par la rotation.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val budgetPixels = (Runtime.getRuntime().maxMemory() / 4 / 4).coerceAtLeast(2_000_000L)
        var sample = 1
        while (width.toLong() * height / (sample.toLong() * sample) > budgetPixels) sample *= 2
        return sample
    }

    private fun compressFormat(target: TargetFormat, quality: Int): Bitmap.CompressFormat =
        when (target) {
            TargetFormat.PNG -> Bitmap.CompressFormat.PNG
            TargetFormat.JPG -> Bitmap.CompressFormat.JPEG
            TargetFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS
                    else Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            else -> throw ConversionException("${target.label} n'est pas un format d'image")
        }

    /** Orientation enregistrée par l'appareil photo, en degrés. */
    private fun exifRotation(source: File): Int = runCatching {
        when (
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun copyExif(source: File, destination: File) {
        runCatching {
            val from = ExifInterface(source.absolutePath)
            val to = ExifInterface(destination.absolutePath)
            PRESERVED_TAGS.forEach { tag ->
                from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            }
            to.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            to.saveAttributes()
        }
    }
}

/** Erreur dont le message est directement affichable à l'utilisateur. */
class ConversionException(message: String) : Exception(message)
