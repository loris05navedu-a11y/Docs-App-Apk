package com.docssuite.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/** Un moteur factice : rend les blocs qu'on lui donne, en notant la taille des images reçues. */
class FakeOcrEngine(private val pages: ArrayDeque<List<OcrBlock>>) : OcrEngine {
    val received = ArrayList<Pair<Int, Int>>()
    var failWith: Exception? = null
    override suspend fun recognize(bitmap: Bitmap): OcrPage {
        failWith?.let { throw it }
        received.add(bitmap.width to bitmap.height)
        return OcrPage(pages.removeFirstOrNull() ?: emptyList(), bitmap.width, bitmap.height)
    }
}

fun block(vararg lines: String): OcrBlock {
    val ocrLines = lines.mapIndexed { i, t -> OcrLine(t, Box(10, 10 + i * 40, 900, 40 + i * 40)) }
    return OcrBlock(ocrLines, Box(10, 10, 900, 40 + (lines.size - 1) * 40))
}

/** Une feuille claire inclinée sur une table sombre, comme une vraie photo. */
fun writeSheetPhoto(file: File): File {
    val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(50, 45, 40))
    val path = android.graphics.Path().apply {
        moveTo(120f, 80f); lineTo(480f, 110f); lineTo(450f, 720f); lineTo(90f, 690f); close()
    }
    canvas.drawPath(path, Paint().apply { color = Color.rgb(236, 236, 232); isAntiAlias = true })
    file.parentFile?.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return file
}
