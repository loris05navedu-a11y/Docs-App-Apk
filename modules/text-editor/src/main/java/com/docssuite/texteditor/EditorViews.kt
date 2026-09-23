package com.docssuite.texteditor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Un champ de texte riche. Le curseur reste visible pendant la frappe : le
 * champ n'a pas de défilement propre, c'est la page qui défile, et elle
 * doit être priée d'amener le curseur à l'écran.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RichTextField(
    field: RichField,
    lineSpacing: Int,
    placeholder: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val transformation = remember(field.styles, field.paras, lineSpacing) {
        RichTextTransformation(field.styles, field.paras, lineSpacing)
    }

    Box(modifier = modifier) {
        if (placeholder != null && field.text.isEmpty()) {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        }
        BasicTextField(
            value = field.value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
        )
    }

    LaunchedEffect(field.value.selection, field.text.length, focused) {
        if (!focused) return@LaunchedEffect
        val current = layout ?: return@LaunchedEffect
        // La mise en page peut dater de l'image précédente : on borne.
        val offset = field.value.selection.end.coerceIn(0, current.layoutInput.text.length)
        runCatching {
            val rect = current.getCursorRect(offset)
            bringIntoView.bringIntoView(rect.copy(top = rect.top - 48f, bottom = rect.bottom + 96f))
        }
    }
}

/**
 * Un tableau éditable. Chaque ligne est une disposition sur mesure : toutes
 * les cellules sont mesurées, la ligne prend la hauteur de la plus haute, et
 * le quadrillage est tracé d'après les largeurs connues d'avance. Un tableau
 * large défile horizontalement plutôt que d'écraser ses colonnes.
 */
@Composable
internal fun TableView(
    table: TableData,
    lineSpacing: Int,
    selected: Pair<Int, Int>?,
    requesterFor: (Int, Int) -> FocusRequester,
    onCellFocused: (Int, Int) -> Unit,
    onCellChange: (Int, Int, TextFieldValue) -> Unit
) {
    val columns = table.columnCount.coerceAtLeast(1)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val headerTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val selectionColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val minimum = 104.dp * columns
        val width = if (minimum > maxWidth) minimum else maxWidth
        val scroll = rememberScrollState()
        Box(modifier = Modifier.horizontalScroll(scroll, enabled = minimum > maxWidth)) {
            Column(modifier = Modifier.width(width)) {
                table.rows.forEachIndexed { r, row ->
                    val header = table.headerRow && r == 0
                    TableRowLayout(
                        row = row,
                        columns = columns,
                        modifier = Modifier.drawBehind {
                            val unit = size.width / columns
                            val stroke = 1.dp.toPx()
                            var x = 0f
                            row.forEachIndexed { c, cell ->
                                val w = unit * cell.colSpan
                                val fill = when {
                                    cell.fill != 0L -> Color(cell.fill)
                                    header -> headerTint
                                    else -> null
                                }
                                fill?.let { drawRect(it, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height)) }
                                if (!cell.mergedAbove) drawLine(gridColor, Offset(x, 0f), Offset(x + w, 0f), stroke)
                                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), stroke)
                                if (selected == r to c) {
                                    val inset = stroke
                                    drawRect(
                                        selectionColor,
                                        Offset(x + inset, inset),
                                        androidx.compose.ui.geometry.Size(w - 2 * inset, size.height - 2 * inset),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                    )
                                }
                                x += w
                            }
                            drawLine(gridColor, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
                            if (r == table.rows.lastIndex) {
                                drawLine(gridColor, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
                            }
                        }
                    ) {
                        row.forEachIndexed { c, cell ->
                            if (cell.mergedAbove) {
                                // Prolongement d'une fusion verticale : rien à saisir ici.
                                Box(modifier = Modifier.heightIn(min = 40.dp))
                            } else {
                                val requester = requesterFor(r, c)
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { runCatching { requester.requestFocus() } }
                                        .padding(horizontal = 8.dp, vertical = 8.dp)
                                ) {
                                    RichTextField(
                                        field = cell.field,
                                        lineSpacing = lineSpacing,
                                        placeholder = null,
                                        focusRequester = requester,
                                        onFocused = { onCellFocused(r, c) },
                                        onValueChange = { onCellChange(r, c, it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Place les cellules d'une ligne côte à côte, toutes à la hauteur de la plus haute. */
@Composable
private fun TableRowLayout(
    row: List<CellField>,
    columns: Int,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val total = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val unit = total.toFloat() / columns
        var x = 0f
        val lefts = IntArray(measurables.size)
        val widths = IntArray(measurables.size)
        measurables.indices.forEach { i ->
            val span = row.getOrNull(i)?.colSpan ?: 1
            val left = x.toInt()
            x += unit * span
            lefts[i] = left
            widths[i] = (x.toInt() - left).coerceAtLeast(0)
        }
        val placeables = measurables.mapIndexed { i, measurable ->
            measurable.measure(Constraints.fixedWidth(widths[i]))
        }
        val height = max(placeables.maxOfOrNull { it.height } ?: 0, 1)
        layout(total, height) {
            placeables.forEachIndexed { i, placeable -> placeable.place(lefts[i], 0) }
        }
    }
}
