package com.docssuite.scanner

import kotlin.math.max
import kotlin.math.min

/**
 * Traitements d'image du scanner, sur des pixels ARGB bruts (`IntArray`,
 * rangée par rangée) : détection de la feuille et filtres de lisibilité.
 * Aucun appel Android, pour pouvoir les vérifier sur des images de synthèse.
 */

enum class ScanFilter(val label: String) {
    ORIGINAL("Original"),
    ENHANCED("Couleurs nettes"),
    GRAY("Niveaux de gris"),
    DOCUMENT("Noir et blanc")
}

internal fun luminance(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000
}

private fun opaque(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/** Seuil d'Otsu : la valeur qui sépare le mieux deux populations (feuille / fond). */
internal fun otsuThreshold(histogram: IntArray): Int {
    val total = histogram.sum().toLong()
    if (total == 0L) return 127
    var sumAll = 0.0
    for (i in 0..255) sumAll += i.toDouble() * histogram[i]
    var sumBack = 0.0
    var weightBack = 0L
    var best = 0.0
    var threshold = 127
    for (t in 0..255) {
        weightBack += histogram[t]
        if (weightBack == 0L) continue
        val weightFore = total - weightBack
        if (weightFore == 0L) break
        sumBack += t.toDouble() * histogram[t]
        val meanBack = sumBack / weightBack
        val meanFore = (sumAll - sumBack) / weightFore
        val between = weightBack.toDouble() * weightFore * (meanBack - meanFore) * (meanBack - meanFore)
        if (between > best) {
            best = between
            threshold = t
        }
    }
    return threshold
}

/**
 * Cherche la feuille dans une petite image (quelques centaines de pixels de
 * côté suffisent) : la plus grande zone claire d'un seul tenant, dont on
 * prend les points extrêmes comme coins. Fonctionne pour une feuille claire
 * sur un fond plus sombre ; sinon renvoie `null`, et l'on garde l'image
 * entière plutôt que de proposer un recadrage absurde.
 */
fun detectDocument(pixels: IntArray, width: Int, height: Int): Quad? {
    val count = width * height
    if (count < 16 || pixels.size < count) return null

    val lum = IntArray(count) { luminance(pixels[it]) }
    val histogram = IntArray(256)
    lum.forEach { histogram[it]++ }
    val threshold = otsuThreshold(histogram)

    // Plus grande composante claire, en 4-connexité.
    val label = IntArray(count)
    val queue = IntArray(count)
    var bestLabel = 0
    var bestSize = 0
    var nextLabel = 0
    for (start in 0 until count) {
        if (label[start] != 0 || lum[start] <= threshold) continue
        nextLabel++
        var head = 0
        var tail = 0
        queue[tail++] = start
        label[start] = nextLabel
        while (head < tail) {
            val p = queue[head++]
            val x = p % width
            val y = p / width
            if (x > 0) tail = visit(p - 1, nextLabel, threshold, lum, label, queue, tail)
            if (x < width - 1) tail = visit(p + 1, nextLabel, threshold, lum, label, queue, tail)
            if (y > 0) tail = visit(p - width, nextLabel, threshold, lum, label, queue, tail)
            if (y < height - 1) tail = visit(p + width, nextLabel, threshold, lum, label, queue, tail)
        }
        if (tail > bestSize) {
            bestSize = tail
            bestLabel = nextLabel
        }
    }

    val coverage = bestSize.toFloat() / count
    if (bestLabel == 0 || coverage < 0.12f || coverage > 0.97f) return null

    var tl = Pt(0f, 0f); var tlScore = Float.MAX_VALUE
    var br = Pt(0f, 0f); var brScore = -Float.MAX_VALUE
    var tr = Pt(0f, 0f); var trScore = -Float.MAX_VALUE
    var bl = Pt(0f, 0f); var blScore = Float.MAX_VALUE
    for (p in 0 until count) {
        if (label[p] != bestLabel) continue
        val x = (p % width).toFloat()
        val y = (p / width).toFloat()
        val sum = x + y
        val diff = x - y
        if (sum < tlScore) { tlScore = sum; tl = Pt(x, y) }
        if (sum > brScore) { brScore = sum; br = Pt(x + 1, y + 1) }
        if (diff > trScore) { trScore = diff; tr = Pt(x + 1, y) }
        if (diff < blScore) { blScore = diff; bl = Pt(x, y + 1) }
    }

    val quad = orderCorners(listOf(tl, tr, br, bl))
    if (!quad.isConvex()) return null
    // Une vraie feuille remplit son quadrilatère ; une forme claire
    // quelconque (reflet, mur, drap) non.
    if (bestSize / quad.area() < 0.9f) return null
    return quad
}

private fun visit(p: Int, id: Int, threshold: Int, lum: IntArray, label: IntArray, queue: IntArray, tail: Int): Int {
    if (label[p] != 0 || lum[p] <= threshold) return tail
    label[p] = id
    queue[tail] = p
    return tail + 1
}

/** Applique [filter] et renvoie de nouveaux pixels ; l'entrée n'est pas modifiée. */
fun applyFilter(filter: ScanFilter, pixels: IntArray, width: Int, height: Int): IntArray = when (filter) {
    ScanFilter.ORIGINAL -> pixels.copyOf()
    ScanFilter.GRAY -> IntArray(pixels.size) { val l = luminance(pixels[it]); opaque(l, l, l) }
    ScanFilter.ENHANCED -> enhance(pixels)
    ScanFilter.DOCUMENT -> documentBlackAndWhite(pixels, width, height)
}

/**
 * Étirement des niveaux : le papier devient blanc, l'encre franchement
 * sombre, sans perdre les couleurs (tampons, surligneur, signature bleue).
 * Le point blanc est pris canal par canal : une lampe jaune ou un papier
 * crème redeviennent blancs au lieu de garder leur teinte.
 */
private fun enhance(pixels: IntArray): IntArray {
    val lumHistogram = IntArray(256)
    val channels = Array(3) { IntArray(256) }
    pixels.forEach { c ->
        lumHistogram[luminance(c)]++
        channels[0][(c shr 16) and 0xFF]++
        channels[1][(c shr 8) and 0xFF]++
        channels[2][c and 0xFF]++
    }
    // Sur une page presque vide (un reçu, une courte lettre), les pixels
    // les plus sombres sont encore du papier : le point noir est donc aussi
    // borné par rapport au papier, pour que celui-ci blanchisse quand même.
    val paper = percentile(lumHistogram, pixels.size, 0.90)
    val low = minOf(percentile(lumHistogram, pixels.size, 0.01), max(0, paper - 128))
    val highs = channels.map { percentile(it, pixels.size, 0.90) }
    if (highs.any { it - low < 16 }) return pixels.copyOf()
    val luts = highs.map { high -> IntArray(256) { v -> ((v - low) * 255 / (high - low)).coerceIn(0, 255) } }
    return IntArray(pixels.size) {
        val c = pixels[it]
        opaque(luts[0][(c shr 16) and 0xFF], luts[1][(c shr 8) and 0xFF], luts[2][c and 0xFF])
    }
}

private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
    val target = (total * fraction).toLong()
    var seen = 0L
    for (i in 0..255) {
        seen += histogram[i]
        if (seen > target) return i
    }
    return 255
}

/**
 * Seuillage adaptatif (Bradley) : chaque pixel est comparé à la moyenne de
 * son voisinage plutôt qu'à un seuil global. Une ombre sur la moitié de la
 * feuille ne la noircit donc pas : seul ce qui est nettement plus sombre que
 * ce qui l'entoure — le texte — reste noir.
 *
 * Les sommes locales passent par deux passes de fenêtre glissante plutôt que
 * par une image intégrale : sur une page de plusieurs millions de pixels,
 * celle-ci déborderait un `Int` et doublerait la mémoire en `Long`.
 */
private fun documentBlackAndWhite(pixels: IntArray, width: Int, height: Int): IntArray {
    val lum = ByteArray(pixels.size) { luminance(pixels[it]).toByte() }
    val radius = max(4, max(width, height) / 32)
    val sensitivity = 12 // % sous la moyenne locale pour compter comme encre

    // Passe horizontale : somme sur [x - radius, x + radius] de chaque rangée.
    val rowSums = IntArray(pixels.size)
    for (y in 0 until height) {
        val base = y * width
        var sum = 0
        for (x in 0..min(radius, width - 1)) sum += lum[base + x].toInt() and 0xFF
        for (x in 0 until width) {
            rowSums[base + x] = sum
            val add = x + radius + 1
            val remove = x - radius
            if (add < width) sum += lum[base + add].toInt() and 0xFF
            if (remove >= 0) sum -= lum[base + remove].toInt() and 0xFF
        }
    }

    val out = IntArray(pixels.size)
    val black = opaque(0, 0, 0)
    val white = opaque(255, 255, 255)
    val columnSum = IntArray(width)
    for (x in 0 until width) {
        var s = 0
        for (y in 0..min(radius, height - 1)) s += rowSums[y * width + x]
        columnSum[x] = s
    }
    for (y in 0 until height) {
        val y0 = max(0, y - radius)
        val y1 = min(height - 1, y + radius)
        for (x in 0 until width) {
            val x0 = max(0, x - radius)
            val x1 = min(width - 1, x + radius)
            val area = (x1 - x0 + 1) * (y1 - y0 + 1)
            val value = lum[y * width + x].toInt() and 0xFF
            // value < moyenne × (1 − sensibilité), sans division.
            val ink = value.toLong() * area * 100 < columnSum[x].toLong() * (100 - sensitivity)
            out[y * width + x] = if (ink) black else white
        }
        // Fait glisser la fenêtre verticale d'une rangée.
        val add = y + radius + 1
        val remove = y - radius
        for (x in 0 until width) {
            if (add < height) columnSum[x] += rowSums[add * width + x]
            if (remove >= 0) columnSum[x] -= rowSums[remove * width + x]
        }
    }
    return out
}
