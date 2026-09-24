package com.docssuite.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Le passage des photos aux pages : décodage raisonnable en mémoire,
 * redressement de la perspective, rotation, filtre, JPEG.
 */
object ScanImaging {

    /** Au-delà, une photo de 50 Mpx saturerait la mémoire sans rien apporter à la lecture. */
    const val MAX_SOURCE_SIDE = 3000

    /** ≈ A4 à 240 dpi : le texte reste net, un filtre tient en mémoire sur un téléphone modeste. */
    const val MAX_OUTPUT_SIDE = 2000

    private const val DETECTION_SIDE = 320

    /**
     * Décode une photo sous-échantillonnée, redressée selon son orientation
     * EXIF : un appareil photo enregistre souvent l'image couchée, avec
     * seulement une étiquette « à tourner de 90° ».
     */
    fun decodeSource(file: File, maxSide: Int = MAX_SOURCE_SIDE): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Cette image est illisible")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw IllegalArgumentException("Cette image est illisible")

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return decoded
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    /** Cherche la feuille sur une version réduite, puis ramène les coins à l'échelle de [bitmap]. */
    fun detect(bitmap: Bitmap): Quad? {
        val factor = DETECTION_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val w = max(1, (bitmap.width * factor).roundToInt())
        val h = max(1, (bitmap.height * factor).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        return detectDocument(pixels, w, h)
            ?.scaled(bitmap.width.toFloat() / w, bitmap.height.toFloat() / h)
            ?.clampedTo(bitmap.width.toFloat(), bitmap.height.toFloat())
    }

    /**
     * La page finale : le quadrilatère [quad] de [source] étiré en rectangle,
     * tourné de [quarterTurns] quarts de tour dans le sens horaire, filtré.
     */
    fun render(
        source: Bitmap,
        quad: Quad,
        quarterTurns: Int,
        filter: ScanFilter,
        maxSide: Int = MAX_OUTPUT_SIDE
    ): Bitmap {
        if (!quad.isConvex()) throw IllegalArgumentException("Les coins se croisent : replace-les autour de la feuille")
        val (w, h) = outputSize(quad, maxSide)
        // Les bords du quadrilatère mêlent feuille et table (lissage, coin
        // posé à un pixel près) : on les pousse juste hors de la page, sans
        // quoi un liseré sombre l'encadre, pointillé en noir et blanc.
        val bleed = max(2f, max(w, h) * 0.004f)
        val target = floatArrayOf(
            -bleed, -bleed, w + bleed, -bleed, w + bleed, h + bleed, -bleed, h + bleed
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(quad.toFloatArray(), 0, target, 0, 4)) {
            throw IllegalArgumentException("Ce recadrage ne peut pas être redressé")
        }
        var page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }

        val turns = Math.floorMod(quarterTurns, 4)
        if (turns != 0) {
            val rotated = Bitmap.createBitmap(page, 0, 0, w, h, Matrix().apply { postRotate(90f * turns) }, true)
            page.recycle()
            page = rotated
        }

        if (filter != ScanFilter.ORIGINAL) {
            val pw = page.width
            val ph = page.height
            val pixels = IntArray(pw * ph)
            page.getPixels(pixels, 0, pw, 0, 0, pw, ph)
            val filtered = applyFilter(filter, pixels, pw, ph)
            if (!page.isMutable) page = page.copy(Bitmap.Config.ARGB_8888, true)
            page.setPixels(filtered, 0, pw, 0, 0, pw, ph)
        }
        return page
    }

    fun encodeJpeg(bitmap: Bitmap, quality: Int = 85): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Vignette légère pour les listes : jamais la page entière en mémoire. */
    fun decodeThumbnail(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
