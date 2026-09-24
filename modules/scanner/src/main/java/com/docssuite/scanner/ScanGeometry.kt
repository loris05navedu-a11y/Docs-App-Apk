package com.docssuite.scanner

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Géométrie du recadrage : les quatre coins de la feuille photographiée et
 * la taille de l'image redressée. Rien d'Android ici, tout se teste sur JVM.
 */

data class Pt(val x: Float, val y: Float)

/** Coins de la feuille, toujours dans l'ordre haut-gauche, haut-droit, bas-droit, bas-gauche. */
data class Quad(val topLeft: Pt, val topRight: Pt, val bottomRight: Pt, val bottomLeft: Pt) {

    val points: List<Pt> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun withCorner(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(topLeft = p)
        1 -> copy(topRight = p)
        2 -> copy(bottomRight = p)
        3 -> copy(bottomLeft = p)
        else -> throw IndexOutOfBoundsException("Coin $index")
    }

    fun scaled(sx: Float, sy: Float): Quad =
        Quad(topLeft.scale(sx, sy), topRight.scale(sx, sy), bottomRight.scale(sx, sy), bottomLeft.scale(sx, sy))

    fun clampedTo(width: Float, height: Float): Quad {
        fun Pt.clamp() = Pt(x.coerceIn(0f, width), y.coerceIn(0f, height))
        return Quad(topLeft.clamp(), topRight.clamp(), bottomRight.clamp(), bottomLeft.clamp())
    }

    /**
     * Un quadrilatère croisé ou retourné (coins traînés l'un par-dessus
     * l'autre) produirait une image en miroir ou déchirée : on le refuse.
     */
    fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Aire (formule du lacet), en pixels carrés. */
    fun area(): Float {
        val p = points
        var sum = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            sum += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(sum) / 2f
    }

    /** Pour `Matrix.setPolyToPoly` : x0, y0, x1, y1… dans l'ordre des coins. */
    fun toFloatArray(): FloatArray = points.flatMap { listOf(it.x, it.y) }.toFloatArray()

    companion object {
        /** Toute l'image, légèrement en retrait pour que les poignées restent attrapables. */
        fun inset(width: Float, height: Float, fraction: Float = 0f): Quad {
            val dx = width * fraction
            val dy = height * fraction
            return Quad(Pt(dx, dy), Pt(width - dx, dy), Pt(width - dx, height - dy), Pt(dx, height - dy))
        }
    }
}

private fun Pt.scale(sx: Float, sy: Float) = Pt(x * sx, y * sy)

private fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

/**
 * Remet quatre points quelconques dans l'ordre des coins : tri par angle
 * autour de leur centre, puis rotation pour commencer par le plus proche du
 * coin haut-gauche. Plus sûr que les sommes x+y seules, qui peuvent désigner
 * deux fois le même point quand la feuille est très inclinée.
 */
fun orderCorners(points: List<Pt>): Quad {
    require(points.size == 4) { "Il faut exactement quatre coins" }
    val cx = points.sumOf { it.x.toDouble() } / 4
    val cy = points.sumOf { it.y.toDouble() } / 4
    val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
    val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y }!!
    val r = (0 until 4).map { sorted[(start + it) % 4] }
    return Quad(r[0], r[1], r[2], r[3])
}

/**
 * Taille de l'image redressée : les côtés opposés les plus longs, pour ne
 * pas perdre de détail, ramenés sous [maxLongSide] pour tenir en mémoire
 * (2480 px ≈ une page A4 à 300 dpi).
 */
fun outputSize(quad: Quad, maxLongSide: Int = 2480): Pair<Int, Int> {
    val w = max(dist(quad.topLeft, quad.topRight), dist(quad.bottomLeft, quad.bottomRight))
    val h = max(dist(quad.topLeft, quad.bottomLeft), dist(quad.topRight, quad.bottomRight))
    val longest = max(w, h)
    val factor = if (longest > maxLongSide) maxLongSide / longest else 1f
    return max(1, (w * factor).roundToInt()) to max(1, (h * factor).roundToInt())
}
