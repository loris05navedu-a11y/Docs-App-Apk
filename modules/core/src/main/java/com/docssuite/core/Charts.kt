package com.docssuite.core

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ChartType(val label: String) {
    PIE("Secteurs"),
    DONUT("Anneau"),
    COLUMN("Barres verticales"),
    BAR("Barres horizontales"),
    LINE("Courbe"),
    AREA("Aires"),
    BOXPLOT("Boîte à moustaches")
}

data class ChartEntry(val label: String, val value: Double)

/** Résumé à cinq nombres d'une série, plus les valeurs aberrantes (méthode de Tukey). */
data class BoxStats(
    val label: String,
    val count: Int,
    val min: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val max: Double,
    val lowerWhisker: Double,
    val upperWhisker: Double,
    val outliers: List<Double>
) {
    val interquartileRange: Double get() = q3 - q1
}

/** Quantile par interpolation linéaire (même convention que QUARTILE d'Excel). */
fun quantile(sortedValues: List<Double>, p: Double): Double {
    if (sortedValues.isEmpty()) return 0.0
    if (sortedValues.size == 1) return sortedValues[0]
    val position = (sortedValues.size - 1) * p.coerceIn(0.0, 1.0)
    val lower = floor(position).toInt()
    val upper = ceil(position).toInt()
    if (lower == upper) return sortedValues[lower]
    return sortedValues[lower] + (position - lower) * (sortedValues[upper] - sortedValues[lower])
}

fun computeBoxStats(label: String, values: List<Double>): BoxStats? {
    val sorted = values.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty()) return null

    val q1 = quantile(sorted, 0.25)
    val median = quantile(sorted, 0.5)
    val q3 = quantile(sorted, 0.75)
    val iqr = q3 - q1
    val lowerFence = q1 - 1.5 * iqr
    val upperFence = q3 + 1.5 * iqr

    val inside = sorted.filter { it in lowerFence..upperFence }
    val outliers = sorted.filter { it < lowerFence || it > upperFence }

    return BoxStats(
        label = label,
        count = sorted.size,
        min = sorted.first(),
        q1 = q1,
        median = median,
        q3 = q3,
        max = sorted.last(),
        lowerWhisker = inside.minOrNull() ?: q1,
        upperWhisker = inside.maxOrNull() ?: q3,
        outliers = outliers
    )
}

/**
 * Une boîte par libellé quand les libellés se répètent (comparaison de groupes),
 * sinon une seule boîte pour toute la série.
 */
fun buildBoxStats(entries: List<ChartEntry>): List<BoxStats> {
    val usable = entries.filter { it.value.isFinite() }
    if (usable.isEmpty()) return emptyList()

    val groups = LinkedHashMap<String, MutableList<Double>>()
    usable.forEach { groups.getOrPut(it.label) { ArrayList() }.add(it.value) }

    val grouped = groups.size > 1 && groups.values.any { it.size > 1 }
    return if (grouped) {
        groups.entries.take(MAX_BOXES).mapNotNull { computeBoxStats(it.key, it.value) }
    } else {
        listOfNotNull(computeBoxStats("Série", usable.map { it.value }))
    }
}

/**
 * Palette catégorielle validée (séparation daltonisme et contraste vérifiés
 * pour les deux fonds). L'ordre des teintes est la garantie de lisibilité :
 * on les attribue toujours dans cet ordre, jamais en boucle.
 */
private val CategoricalLight = listOf(
    0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100,
    0xFFE87BA4, 0xFF008300, 0xFF4A3AA7, 0xFFE34948
)

private val CategoricalDark = listOf(
    0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500,
    0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767
)

/** Au-delà, les secteurs deviennent illisibles : le reste est regroupé. */
private const val MAX_PIE_SEGMENTS = 6
private const val MAX_CATEGORIES = 14
private const val MAX_BOXES = 8
private const val MAX_TICKS = 20

/** Construit les points du graphique à partir de deux plages de cellules. */
fun buildChartEntries(
    labelsRange: String,
    valuesRange: String,
    cells: Map<String, String>
): List<ChartEntry> {
    val valueKeys = FormulaEngine.expandRange(valuesRange)
    val labelKeys = FormulaEngine.expandRange(labelsRange)
    return valueKeys.mapIndexedNotNull { index, key ->
        val shown = FormulaEngine.displayValue(key, cells)
        val value = shown.replace(" ", "").replace(',', '.').toDoubleOrNull()
            ?: return@mapIndexedNotNull null
        val label = labelKeys.getOrNull(index)
            ?.let { FormulaEngine.displayValue(it, cells) }
            ?.takeIf { it.isNotBlank() }
            ?: key
        ChartEntry(label, value)
    }
}

@Composable
fun chartPalette(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(dark) {
        (if (dark) CategoricalDark else CategoricalLight).map { Color(it) }
    }
}

/** Regroupe la queue de distribution dans « Autres » plutôt que de recycler les teintes. */
fun foldEntries(entries: List<ChartEntry>, max: Int): List<ChartEntry> {
    if (entries.size <= max) return entries
    val head = entries.take(max - 1)
    val tail = entries.drop(max - 1)
    return head + ChartEntry("Autres", tail.sumOf { it.value })
}

@Composable
fun ChartView(
    type: ChartType,
    title: String,
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    canvasHeight: Dp? = null,
    showDataPanel: Boolean = true
) {
    val palette = chartPalette()
    val surface = MaterialTheme.colorScheme.surface
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant

    val isPie = type == ChartType.PIE || type == ChartType.DONUT
    val isBox = type == ChartType.BOXPLOT
    val data = remember(entries, isPie) {
        foldEntries(
            entries.filter { it.value.isFinite() },
            if (isPie) MAX_PIE_SEGMENTS else MAX_CATEGORIES
        )
    }
    val boxes = remember(entries, isBox) {
        if (isBox) buildBoxStats(entries) else emptyList()
    }

    // Une boîte à moustaches reste valable même si toutes les valeurs sont nulles :
    // c'est une distribution, pas une magnitude.
    val hasData = if (isBox) boxes.isNotEmpty() else data.isNotEmpty() && data.any { it.value != 0.0 }

    Column(modifier = modifier) {
        if (title.isNotBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textPrimary
            )
        }

        // Pas de retour anticipé ici : sortir d'un lambda composable en plein
        // milieu casse la table de slots de Compose (plantage au rendu).
        if (!hasData) {
            Text(
                "Aucune valeur numérique dans la plage sélectionnée.",
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondary,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            val paint = remember { Paint().apply { isAntiAlias = true } }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeight ?: if (isPie) 250.dp else 230.dp)
                    .padding(top = 12.dp)
            ) {
                if (size.minDimension <= 0f) return@Canvas
                when (type) {
                    ChartType.PIE -> drawPie(data, palette, surface, paint, textPrimary, donut = false)
                    ChartType.DONUT -> drawPie(data, palette, surface, paint, textPrimary, donut = true)
                    ChartType.COLUMN -> drawColumns(data, palette[0], surface, grid, paint, textSecondary, textPrimary)
                    ChartType.BAR -> drawBars(data, palette[0], surface, grid, paint, textSecondary, textPrimary)
                    ChartType.LINE -> drawLine(data, palette[0], surface, grid, paint, textSecondary, textPrimary, fill = false)
                    ChartType.AREA -> drawLine(data, palette[0], surface, grid, paint, textSecondary, textPrimary, fill = true)
                    ChartType.BOXPLOT -> drawBoxPlot(boxes, palette[0], surface, grid, paint, textSecondary, textPrimary)
                }
            }

            if (showDataPanel) {
                ChartDataPanel(type, data, boxes, palette, textPrimary, textSecondary)
            }
        }
    }
}

@Composable
private fun ChartDataPanel(
    type: ChartType,
    data: List<ChartEntry>,
    boxes: List<BoxStats>,
    palette: List<Color>,
    textPrimary: Color,
    textSecondary: Color
) {
    val isPie = type == ChartType.PIE || type == ChartType.DONUT
    Column(modifier = Modifier.padding(top = 12.dp)) {
        if (type == ChartType.BOXPLOT) {
            boxes.forEach { box ->
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    Text(
                        "${box.label}  ·  ${box.count} valeur(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textPrimary
                    )
                    Text(
                        "Min ${FormulaEngine.formatNumber(box.min)}  ·  " +
                            "Q1 ${FormulaEngine.formatNumber(box.q1)}  ·  " +
                            "Médiane ${FormulaEngine.formatNumber(box.median)}  ·  " +
                            "Q3 ${FormulaEngine.formatNumber(box.q3)}  ·  " +
                            "Max ${FormulaEngine.formatNumber(box.max)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )
                    if (box.outliers.isNotEmpty()) {
                        Text(
                            "Valeurs aberrantes : ${box.outliers.joinToString(", ") { FormulaEngine.formatNumber(it) }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = textSecondary
                        )
                    }
                }
            }
        } else if (isPie) {
            val total = data.sumOf { abs(it.value) }
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                data.forEachIndexed { index, entry ->
                    val share = if (total == 0.0) 0.0 else abs(entry.value) / total * 100
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(palette[index % palette.size], CircleShape)
                        )
                        Text(
                            entry.label,
                            modifier = Modifier.padding(start = 10.dp).weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textPrimary,
                            maxLines = 1
                        )
                        Text(
                            "${FormulaEngine.formatNumber(entry.value)}  ·  ${
                                String.format(Locale.FRANCE, "%.1f", share)
                            } %",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textSecondary
                        )
                    }
                }
            }
        } else {
            // Vue tableau : garantit l'accès aux valeurs exactes non étiquetées.
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                data.take(8).forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            entry.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = textSecondary,
                            maxLines = 1
                        )
                        Text(
                            FormulaEngine.formatNumber(entry.value),
                            style = MaterialTheme.typography.bodySmall,
                            color = textPrimary
                        )
                    }
                }
                if (data.size > 8) {
                    Text(
                        "… et ${data.size - 8} autre(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondary
                    )
                }
            }
        }
    }
}

// --- Rendu ---------------------------------------------------------------

private fun DrawScope.label(
    paint: Paint,
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizeSp: Float,
    align: Paint.Align = Paint.Align.CENTER,
    bold: Boolean = false
) {
    paint.color = color.toArgb()
    paint.textSize = sizeSp.sp.toPx()
    paint.textAlign = align
    paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

private fun DrawScope.drawPie(
    data: List<ChartEntry>,
    palette: List<Color>,
    surface: Color,
    paint: Paint,
    textPrimary: Color,
    donut: Boolean
) {
    if (data.isEmpty()) return
    val total = data.sumOf { abs(it.value) }
    if (total <= 0.0) return

    val radius = min(size.width, size.height) / 2f - 8.dp.toPx()
    if (radius <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val gapDegrees = if (data.size > 1) (2.dp.toPx() / radius) * (180f / Math.PI.toFloat()) else 0f
    val ringWidth = radius * 0.42f

    var start = -90f
    data.forEachIndexed { index, entry ->
        val sweep = (abs(entry.value) / total * 360f).toFloat()
        val drawnSweep = max(sweep - gapDegrees, 0.3f)
        if (donut) {
            drawArc(
                color = palette[index % palette.size],
                startAngle = start,
                sweepAngle = drawnSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius + ringWidth / 2, center.y - radius + ringWidth / 2),
                size = Size((radius - ringWidth / 2) * 2, (radius - ringWidth / 2) * 2),
                style = Stroke(width = ringWidth)
            )
        } else {
            drawArc(
                color = palette[index % palette.size],
                startAngle = start,
                sweepAngle = drawnSweep,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2)
            )
        }

        // Étiquette directe seulement si la part est assez large pour la contenir.
        if (sweep >= 30f) {
            val mid = Math.toRadians((start + sweep / 2).toDouble())
            val r = if (donut) radius - ringWidth / 2 else radius * 0.62f
            val percent = "${(abs(entry.value) / total * 100).toInt()} %"
            label(
                paint,
                percent,
                center.x + (r * kotlin.math.cos(mid)).toFloat(),
                center.y + (r * kotlin.math.sin(mid)).toFloat() + 5.dp.toPx(),
                if (donut) textPrimary else pickInkFor(palette[index % palette.size]),
                13f,
                bold = true
            )
        }
        start += sweep
    }

    if (donut) {
        label(paint, FormulaEngine.formatNumber(total), center.x, center.y + 4.dp.toPx(), textPrimary, 20f, bold = true)
    }
}

private fun DrawScope.drawColumns(
    data: List<ChartEntry>,
    barColor: Color,
    surface: Color,
    grid: Color,
    paint: Paint,
    textSecondary: Color,
    textPrimary: Color
) {
    if (data.isEmpty()) return
    val leftPad = 46.dp.toPx()
    val bottomPad = 26.dp.toPx()
    val topPad = 14.dp.toPx()
    val plotWidth = size.width - leftPad - 8.dp.toPx()
    val plotHeight = size.height - bottomPad - topPad
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val maxValue = max(0.0, data.maxOf { it.value })
    val minValue = min(0.0, data.minOf { it.value })
    val axis = niceAxis(minValue, maxValue)
    fun yOf(v: Double): Float =
        topPad + plotHeight - ((v - axis.min) / (axis.max - axis.min) * plotHeight).toFloat()

    drawGrid(axis, paint, grid, textSecondary, leftPad, topPad, plotWidth, plotHeight)

    val slot = plotWidth / data.size
    val barWidth = min(24.dp.toPx(), slot - 2.dp.toPx())
    val radius = 4.dp.toPx()
    val baseline = yOf(0.0)

    data.forEachIndexed { index, entry ->
        val cx = leftPad + slot * index + slot / 2
        val top = yOf(entry.value)
        val isPositive = entry.value >= 0
        drawBarShape(
            left = cx - barWidth / 2,
            right = cx + barWidth / 2,
            dataEnd = top,
            baseline = baseline,
            radius = radius,
            color = barColor,
            verticalBar = true,
            positive = isPositive
        )

        val short = ellipsize(entry.label, 8)
        label(paint, short, cx, size.height - 8.dp.toPx(), textSecondary, 11f)

        if (data.size <= 4) {
            val y = if (isPositive) top - 6.dp.toPx() else top + 14.dp.toPx()
            label(paint, FormulaEngine.formatNumber(entry.value), cx, y, textPrimary, 11f, bold = true)
        }
    }
}

private fun DrawScope.drawBars(
    data: List<ChartEntry>,
    barColor: Color,
    surface: Color,
    grid: Color,
    paint: Paint,
    textSecondary: Color,
    textPrimary: Color
) {
    if (data.isEmpty()) return
    val leftPad = 76.dp.toPx()
    val rightPad = 40.dp.toPx()
    val topPad = 10.dp.toPx()
    val bottomPad = 20.dp.toPx()
    val plotWidth = size.width - leftPad - rightPad
    val plotHeight = size.height - topPad - bottomPad
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val maxValue = max(0.0, data.maxOf { it.value })
    val minValue = min(0.0, data.minOf { it.value })
    val axis = niceAxis(minValue, maxValue)
    fun xOf(v: Double): Float = leftPad + ((v - axis.min) / (axis.max - axis.min) * plotWidth).toFloat()

    // Grille verticale discrète
    axis.forEachTick { tick ->
        val x = xOf(tick)
        drawLine(grid, Offset(x, topPad), Offset(x, topPad + plotHeight), strokeWidth = 1f)
        label(paint, formatTick(tick), x, size.height - 6.dp.toPx(), textSecondary, 10f)
    }

    val slot = plotHeight / data.size
    val barHeight = min(24.dp.toPx(), slot - 2.dp.toPx())
    val radius = 4.dp.toPx()
    val baseline = xOf(0.0)

    data.forEachIndexed { index, entry ->
        val cy = topPad + slot * index + slot / 2
        val end = xOf(entry.value)
        val isPositive = entry.value >= 0
        drawBarShape(
            left = cy - barHeight / 2,
            right = cy + barHeight / 2,
            dataEnd = end,
            baseline = baseline,
            radius = radius,
            color = barColor,
            verticalBar = false,
            positive = isPositive
        )

        label(paint, ellipsize(entry.label, 11), leftPad - 8.dp.toPx(), cy + 4.dp.toPx(), textSecondary, 11f, Paint.Align.RIGHT)

        if (data.size <= 4) {
            label(
                paint,
                FormulaEngine.formatNumber(entry.value),
                end + (if (isPositive) 6.dp.toPx() else -6.dp.toPx()),
                cy + 4.dp.toPx(),
                textPrimary,
                11f,
                if (isPositive) Paint.Align.LEFT else Paint.Align.RIGHT,
                bold = true
            )
        }
    }
}

private fun DrawScope.drawLine(
    data: List<ChartEntry>,
    lineColor: Color,
    surface: Color,
    grid: Color,
    paint: Paint,
    textSecondary: Color,
    textPrimary: Color,
    fill: Boolean
) {
    if (data.isEmpty()) return
    val leftPad = 46.dp.toPx()
    val bottomPad = 26.dp.toPx()
    val topPad = 14.dp.toPx()
    val plotWidth = size.width - leftPad - 12.dp.toPx()
    val plotHeight = size.height - bottomPad - topPad
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val axis = niceAxis(min(0.0, data.minOf { it.value }), max(0.0, data.maxOf { it.value }))
    fun yOf(v: Double): Float =
        topPad + plotHeight - ((v - axis.min) / (axis.max - axis.min) * plotHeight).toFloat()

    drawGrid(axis, paint, grid, textSecondary, leftPad, topPad, plotWidth, plotHeight)

    val stepX = if (data.size <= 1) 0f else plotWidth / (data.size - 1)
    val points = data.mapIndexed { index, entry ->
        Offset(leftPad + stepX * index, yOf(entry.value))
    }

    if (fill && points.size > 1) {
        val area = Path().apply {
            moveTo(points.first().x, yOf(axis.min.coerceAtLeast(0.0)))
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, yOf(axis.min.coerceAtLeast(0.0)))
            close()
        }
        drawPath(area, lineColor.copy(alpha = 0.10f))
    }

    if (points.size > 1) {
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path,
            lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    points.forEachIndexed { index, point ->
        // Anneau de surface : le marqueur reste lisible là où il croise la courbe.
        drawCircle(surface, radius = 6.dp.toPx(), center = point)
        drawCircle(lineColor, radius = 4.dp.toPx(), center = point)
        if (index == points.lastIndex || data.size <= 4) {
            label(
                paint,
                FormulaEngine.formatNumber(data[index].value),
                point.x,
                point.y - 10.dp.toPx(),
                textPrimary,
                11f,
                bold = true
            )
        }
        label(paint, ellipsize(data[index].label, 8), point.x, size.height - 8.dp.toPx(), textSecondary, 11f)
    }
}

/**
 * Itère les graduations par index plutôt qu'en accumulant le pas : borné, et
 * insensible aux pertes de précision qui pourraient figer la boucle.
 */
private inline fun Axis.forEachTick(action: (Double) -> Unit) {
    if (!step.isFinite() || step <= 0.0 || !min.isFinite() || !max.isFinite()) return
    val count = (((max - min) / step).toInt() + 1).coerceIn(1, MAX_TICKS)
    for (i in 0 until count) action(min + step * i)
}

private fun DrawScope.drawGrid(
    axis: Axis,
    paint: Paint,
    grid: Color,
    textSecondary: Color,
    leftPad: Float,
    topPad: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    axis.forEachTick { tick ->
        val y = topPad + plotHeight - ((tick - axis.min) / (axis.max - axis.min) * plotHeight).toFloat()
        drawLine(grid, Offset(leftPad, y), Offset(leftPad + plotWidth, y), strokeWidth = 1f)
        label(paint, formatTick(tick), leftPad - 6.dp.toPx(), y + 4.dp.toPx(), textSecondary, 10f, Paint.Align.RIGHT)
    }
}

/** Boîte à moustaches (Tukey) : boîte Q1–Q3, médiane, moustaches, points aberrants. */
private fun DrawScope.drawBoxPlot(
    boxes: List<BoxStats>,
    boxColor: Color,
    surface: Color,
    grid: Color,
    paint: Paint,
    textSecondary: Color,
    textPrimary: Color
) {
    if (boxes.isEmpty()) return

    val leftPad = 46.dp.toPx()
    val bottomPad = 26.dp.toPx()
    val topPad = 14.dp.toPx()
    val plotWidth = size.width - leftPad - 12.dp.toPx()
    val plotHeight = size.height - topPad - bottomPad
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val lowest = boxes.minOf { minOf(it.lowerWhisker, it.outliers.minOrNull() ?: it.min) }
    val highest = boxes.maxOf { maxOf(it.upperWhisker, it.outliers.maxOrNull() ?: it.max) }
    // Une distribution n'a pas à partir de zéro : l'axe cadre les valeurs réelles.
    val axis = niceAxis(lowest, highest)
    fun yOf(v: Double): Float =
        topPad + plotHeight - ((v - axis.min) / (axis.max - axis.min) * plotHeight).toFloat()

    drawGrid(axis, paint, grid, textSecondary, leftPad, topPad, plotWidth, plotHeight)

    val slot = plotWidth / boxes.size
    val boxWidth = min(56.dp.toPx(), slot - 16.dp.toPx()).coerceAtLeast(12.dp.toPx())
    val whiskerCap = boxWidth / 2.5f

    boxes.forEachIndexed { index, box ->
        val cx = leftPad + slot * index + slot / 2

        // Moustaches
        drawLine(
            boxColor,
            Offset(cx, yOf(box.upperWhisker)),
            Offset(cx, yOf(box.q3)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            boxColor,
            Offset(cx, yOf(box.q1)),
            Offset(cx, yOf(box.lowerWhisker)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            boxColor,
            Offset(cx - whiskerCap, yOf(box.upperWhisker)),
            Offset(cx + whiskerCap, yOf(box.upperWhisker)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            boxColor,
            Offset(cx - whiskerCap, yOf(box.lowerWhisker)),
            Offset(cx + whiskerCap, yOf(box.lowerWhisker)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Boîte interquartile
        val boxTop = yOf(box.q3)
        val boxBottom = yOf(box.q1)
        drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = cx - boxWidth / 2,
                        top = min(boxTop, boxBottom),
                        right = cx + boxWidth / 2,
                        bottom = max(boxTop, boxBottom) + 1f,
                        topLeftCornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        topRightCornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        bottomRightCornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        bottomLeftCornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                )
            },
            boxColor
        )

        // Médiane : trait dans la couleur du fond, il sépare sans ajouter d'encre.
        drawLine(
            surface,
            Offset(cx - boxWidth / 2, yOf(box.median)),
            Offset(cx + boxWidth / 2, yOf(box.median)),
            strokeWidth = 2.dp.toPx()
        )

        // Valeurs aberrantes, avec anneau de surface pour rester lisibles.
        box.outliers.forEach { outlier ->
            val center = Offset(cx, yOf(outlier))
            drawCircle(surface, radius = 6.dp.toPx(), center = center)
            drawCircle(boxColor, radius = 4.dp.toPx(), center = center)
        }

        label(paint, ellipsize(box.label, 10), cx, size.height - 8.dp.toPx(), textSecondary, 11f)

        if (boxes.size <= 4) {
            label(
                paint,
                FormulaEngine.formatNumber(box.median),
                cx + boxWidth / 2 + 6.dp.toPx(),
                yOf(box.median) + 4.dp.toPx(),
                textPrimary,
                11f,
                Paint.Align.LEFT,
                bold = true
            )
        }
    }
}

/** Extrémité arrondie côté donnée, carrée côté ligne de base. */
private fun DrawScope.drawBarShape(
    left: Float,
    right: Float,
    dataEnd: Float,
    baseline: Float,
    radius: Float,
    color: Color,
    verticalBar: Boolean,
    positive: Boolean
) {
    val zero = CornerRadius.Zero
    val r = CornerRadius(radius, radius)
    val rect = if (verticalBar) {
        RoundRect(
            left = left,
            top = min(dataEnd, baseline),
            right = right,
            bottom = max(dataEnd, baseline),
            topLeftCornerRadius = if (positive) r else zero,
            topRightCornerRadius = if (positive) r else zero,
            bottomRightCornerRadius = if (positive) zero else r,
            bottomLeftCornerRadius = if (positive) zero else r
        )
    } else {
        RoundRect(
            left = min(dataEnd, baseline),
            top = left,
            right = max(dataEnd, baseline),
            bottom = right,
            topLeftCornerRadius = if (positive) zero else r,
            topRightCornerRadius = if (positive) r else zero,
            bottomRightCornerRadius = if (positive) r else zero,
            bottomLeftCornerRadius = if (positive) zero else r
        )
    }
    drawPath(Path().apply { addRoundRect(rect) }, color)
}

internal data class Axis(val min: Double, val max: Double, val step: Double)

internal fun niceAxis(minValue: Double, maxValue: Double): Axis {
    if (minValue == 0.0 && maxValue == 0.0) return Axis(0.0, 1.0, 1.0)
    val range = maxValue - minValue
    val step = niceStep(if (range == 0.0) abs(maxValue) else range)
    val niceMin = floor(minValue / step) * step
    val niceMax = ceil(maxValue / step) * step
    return Axis(niceMin, if (niceMax == niceMin) niceMin + step else niceMax, step)
}

private fun niceStep(range: Double, targetTicks: Int = 4): Double {
    if (range <= 0.0) return 1.0
    val raw = range / targetTicks
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val factor = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return factor * magnitude
}

private fun formatTick(value: Double): String {
    val rounded = FormulaEngine.formatNumber(value)
    val number = rounded.toDoubleOrNull() ?: return rounded
    if (abs(number) >= 1000) {
        return String.format(Locale.US, "%,.0f", number).replace(',', ' ')
    }
    return rounded
}

private fun ellipsize(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

/** Encre blanche ou sombre selon la luminance du remplissage, pour rester lisible. */
private fun pickInkFor(fill: Color): Color =
    if (fill.luminance() > 0.5f) Color(0xFF111827) else Color.White
