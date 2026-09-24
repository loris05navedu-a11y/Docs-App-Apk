package com.docssuite.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ce qui lit le texte d'une image ; remplaçable dans les tests. */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrPage
    fun close() {}
}

/**
 * ML Kit, modèle embarqué dans l'APK : tout se passe sur le téléphone,
 * sans connexion, et aucune image n'est envoyée. Alphabet latin : français,
 * anglais, espagnol, allemand, italien, portugais…
 */
class MlKitOcrEngine : OcrEngine {

    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrPage = suspendCancellableCoroutine { continuation ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val blocks = text.textBlocks.map { block ->
                    OcrBlock(
                        lines = block.lines.map { OcrLine(it.text, it.boundingBox.toBox()) },
                        box = block.boundingBox.toBox()
                    )
                }
                continuation.resume(OcrPage(blocks, bitmap.width, bitmap.height))
            }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    override fun close() = client.close()

    private fun Rect?.toBox(): Box = if (this == null) Box(0, 0, 0, 0) else Box(left, top, right, bottom)
}
