package com.docssuite.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File

/**
 * Une « photo » de feuille inclinée sur une table sombre, avec une ligne de
 * texte à 30 % de sa hauteur. À utiliser avec le moteur graphique natif.
 */
object SyntheticPhoto {

    val sheet = Quad(Pt(140f, 90f), Pt(470f, 130f), Pt(430f, 700f), Pt(90f, 650f))

    private fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    fun bitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(60, 55, 50))
        val path = Path().apply {
            moveTo(sheet.topLeft.x, sheet.topLeft.y)
            lineTo(sheet.topRight.x, sheet.topRight.y)
            lineTo(sheet.bottomRight.x, sheet.bottomRight.y)
            lineTo(sheet.bottomLeft.x, sheet.bottomLeft.y)
            close()
        }
        canvas.drawPath(path, Paint().apply { color = Color.rgb(235, 235, 230); isAntiAlias = true })
        val a = lerp(sheet.topLeft, sheet.bottomLeft, 0.3f)
        val b = lerp(sheet.topRight, sheet.bottomRight, 0.3f)
        val from = lerp(a, b, 0.1f)
        val to = lerp(a, b, 0.9f)
        canvas.drawLine(from.x, from.y, to.x, to.y, Paint().apply { color = Color.BLACK; strokeWidth = 12f })
        return bitmap
    }

    fun writeJpeg(file: File): File {
        file.parentFile?.mkdirs()
        file.writeBytes(ScanImaging.encodeJpeg(bitmap(), quality = 92))
        return file
    }
}
