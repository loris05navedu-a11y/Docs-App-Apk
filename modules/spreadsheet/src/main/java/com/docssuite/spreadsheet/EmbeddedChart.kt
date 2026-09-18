package com.docssuite.spreadsheet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.docssuite.core.ChartEntry
import com.docssuite.core.ChartType
import com.docssuite.core.ChartView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Graphique posé sur la feuille : sa position est en dp, relative à la cellule A1. */
data class EmbeddedChart(
    val id: String,
    val type: ChartType,
    val title: String,
    val labelsRange: String,
    val valuesRange: String,
    val x: Float = 24f,
    val y: Float = 24f,
    val width: Float = 280f,
    val height: Float = 220f
)

const val MIN_CHART_WIDTH = 160f
const val MIN_CHART_HEIGHT = 140f
private val HEADER_HEIGHT = 40.dp

fun chartsToJson(charts: List<EmbeddedChart>): JSONArray {
    val array = JSONArray()
    charts.forEach { chart ->
        array.put(JSONObject().apply {
            put("id", chart.id)
            put("type", chart.type.name)
            put("title", chart.title)
            put("labels", chart.labelsRange)
            put("values", chart.valuesRange)
            put("x", chart.x.toDouble())
            put("y", chart.y.toDouble())
            put("w", chart.width.toDouble())
            put("h", chart.height.toDouble())
        })
    }
    return array
}

fun chartsFromJson(array: JSONArray?): List<EmbeddedChart> {
    if (array == null) return emptyList()
    val out = ArrayList<EmbeddedChart>(array.length())
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val type = runCatching { ChartType.valueOf(o.optString("type")) }.getOrNull() ?: ChartType.COLUMN
        out.add(
            EmbeddedChart(
                id = o.optString("id", i.toString()),
                type = type,
                title = o.optString("title"),
                labelsRange = o.optString("labels"),
                valuesRange = o.optString("values"),
                x = o.optDouble("x", 24.0).toFloat(),
                y = o.optDouble("y", 24.0).toFloat(),
                width = o.optDouble("w", 280.0).toFloat().coerceAtLeast(MIN_CHART_WIDTH),
                height = o.optDouble("h", 220.0).toFloat().coerceAtLeast(MIN_CHART_HEIGHT)
            )
        )
    }
    return out
}

@Composable
fun FloatingChart(
    chart: EmbeddedChart,
    entries: List<ChartEntry>,
    selected: Boolean,
    screenX: Float,
    screenY: Float,
    onSelect: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onResize: (dw: Float, dh: Float) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
            .size(chart.width.dp, chart.height.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = if (selected) 10.dp else 4.dp,
            border = if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(chart.id) {
                    detectTapGestures { onSelect() }
                }
                // Le déplacement est libre : on attrape le glissement partout sur
                // la carte et on le consomme pour que la grille ne défile pas.
                .pointerInput(chart.id) {
                    detectDragGestures(
                        onDragStart = { onSelect() },
                        onDrag = { change, drag ->
                            change.consume()
                            with(density) { onMove(drag.x.toDp().value, drag.y.toDp().value) }
                        }
                    )
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "Déplacer",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        chart.title.ifBlank { chart.type.label },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Modifier",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Supprimer",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                ChartView(
                    type = chart.type,
                    title = "",
                    entries = entries,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    canvasHeight = (chart.height.dp - HEADER_HEIGHT - 8.dp).coerceAtLeast(80.dp),
                    showDataPanel = false
                )
            }
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
                    .pointerInput(chart.id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            with(density) { onResize(drag.x.toDp().value, drag.y.toDp().value) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        contentDescription = "Redimensionner",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }
    }
}
