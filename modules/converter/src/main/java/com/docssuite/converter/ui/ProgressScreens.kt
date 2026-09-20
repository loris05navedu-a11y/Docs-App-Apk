package com.docssuite.converter.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.converter.model.ItemState
import com.docssuite.converter.model.OutputFile
import com.docssuite.converter.model.QueueItem
import com.docssuite.converter.model.formatSize

@Composable
fun RunningScreen(state: ConverterState) {
    val queue = state.queue
    val overall = remember(queue.toList()) {
        if (queue.isEmpty()) 0f
        else queue.sumOf { item ->
            when (val itemState = item.state) {
                is ItemState.Done -> 1.0
                is ItemState.Running -> itemState.fraction.toDouble()
                else -> 0.0
            }
        }.toFloat() / queue.size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Spacing.large))
        ProgressRing(overall)
        Spacer(Modifier.height(Spacing.large))

        val single = state.singleSource
        if (single != null) {
            Text(
                "${single.displayName}  →  ${single.baseName}.${state.target?.extension.orEmpty()}",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                "${queue.count { it.state is ItemState.Done }} sur ${queue.size} fichiers convertis",
                style = MaterialTheme.typography.titleSmall
            )
        }
        Spacer(Modifier.height(Spacing.tiny))
        Text(
            "Le traitement se fait sur votre appareil",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (queue.size > 1) {
            Spacer(Modifier.height(Spacing.large))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                items(queue) { item -> QueueRow(item) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = state::cancel,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(Radii.medium)
        ) {
            Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.small))
            Text("Annuler")
        }
        Spacer(Modifier.height(Spacing.large))
    }
}

@Composable
private fun QueueRow(item: QueueItem) {
    val tokens = ConverterDesign.tokens
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.compact),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIcon(item.state)
            Spacer(Modifier.width(Spacing.compact))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.source.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                when (val itemState = item.state) {
                    is ItemState.Running -> {
                        Spacer(Modifier.height(Spacing.tiny + 2.dp))
                        LinearProgressIndicator(
                            progress = itemState.fraction,
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(Radii.pill))
                        )
                    }
                    is ItemState.Failed -> Text(
                        itemState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    is ItemState.Done -> Text(
                        formatSize(itemState.output.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.success
                    )
                    ItemState.Cancelled -> Text(
                        "Annulé",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ItemState.Waiting -> Text(
                        "En attente",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.state is ItemState.Running) {
                Text(
                    "${((item.state as ItemState.Running).fraction * 100).toInt()} %",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(state: ItemState) {
    val tokens = ConverterDesign.tokens
    when (state) {
        is ItemState.Done -> IconBadge(Icons.Filled.Check, tokens.success, size = 32.dp)
        is ItemState.Failed -> IconBadge(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, size = 32.dp)
        ItemState.Cancelled -> IconBadge(Icons.Filled.Close, MaterialTheme.colorScheme.onSurfaceVariant, size = 32.dp)
        is ItemState.Running -> Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        ItemState.Waiting -> Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
fun ResultScreen(state: ConverterState, actions: ConverterActions) {
    val outputs = state.results
    val failures = state.queue.count { it.state is ItemState.Failed }

    if (outputs.isEmpty()) {
        FailureResult(state)
        return
    }

    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val scale by animateFloatAsState(
        if (revealed) 1f else 0.6f,
        tween(420),
        label = "reveal"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.medium,
            bottom = Spacing.section
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.medium)
    ) {
        item {
            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(88.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(ConverterDesign.tokens.success.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check,
                    null,
                    tint = ConverterDesign.tokens.success,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        item {
            Text(
                if (outputs.size > 1) "${outputs.size} fichiers convertis" else "Conversion terminée",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
        }
        if (failures > 0) {
            item {
                Text(
                    if (failures == 1) "1 fichier n'a pas pu être converti"
                    else "$failures fichiers n'ont pas pu être convertis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        items(outputs) { output -> ResultCard(output, actions) }

        item {
            TextButton(onClick = state::reset) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.small))
                Text("Convertir un autre fichier")
            }
        }
    }
}

@Composable
private fun ResultCard(output: OutputFile, actions: ConverterActions) {
    val accent = ConverterDesign.tokens.accentFor(output.format)
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(iconFor(output.format), accent, size = 48.dp)
                Spacer(Modifier.width(Spacing.compact))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        output.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${output.format.label} • ${formatSize(output.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Spacing.medium))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                ResultAction(
                    "Ouvrir",
                    Icons.Outlined.OpenInNew,
                    Modifier.weight(1f)
                ) { actions.open(output.file, output.format.mime) }
                ResultAction(
                    "Partager",
                    Icons.Outlined.Share,
                    Modifier.weight(1f)
                ) { actions.share(output.file, output.format.mime) }
                ResultAction(
                    "Enregistrer",
                    Icons.Outlined.SaveAlt,
                    Modifier.weight(1f)
                ) { actions.save(output) }
            }
        }
    }
}

@Composable
private fun ResultAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(Radii.small),
        contentPadding = PaddingValues(horizontal = Spacing.tiny)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FailureResult(state: ConverterState) {
    val reason = state.queue
        .mapNotNull { (it.state as? ItemState.Failed)?.message }
        .firstOrNull()
    val cancelled = state.queue.any { it.state is ItemState.Cancelled }

    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconBadge(
            if (cancelled) Icons.Filled.Close else Icons.Outlined.ErrorOutline,
            if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            size = 72.dp
        )
        Spacer(Modifier.height(Spacing.medium))
        Text(
            if (cancelled) "Conversion annulée" else "La conversion a échoué",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(Spacing.small))
        Text(
            reason ?: if (cancelled) {
                "Aucun fichier n'a été produit."
            } else {
                "Ce fichier n'a pas pu être converti."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xlarge))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.compact)) {
            OutlinedButton(
                onClick = state::backToConfigure,
                shape = RoundedCornerShape(Radii.medium),
                modifier = Modifier.height(52.dp)
            ) {
                Text("Modifier les options")
            }
            PrimaryAction("Nouveau fichier", onClick = state::reset)
        }
    }
}
