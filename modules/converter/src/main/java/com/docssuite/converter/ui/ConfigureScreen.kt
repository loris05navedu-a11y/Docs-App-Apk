package com.docssuite.converter.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.converter.model.AudioBitrate
import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.OptionGroup
import com.docssuite.converter.model.PageOrientation
import com.docssuite.converter.model.PageSize
import com.docssuite.converter.model.Rotation
import com.docssuite.converter.model.SampleRate
import com.docssuite.converter.model.ScalePreset
import com.docssuite.converter.model.SourceFile
import com.docssuite.converter.model.TargetFormat
import com.docssuite.converter.model.formatSize

@Composable
fun ConfigureScreen(state: ConverterState, actions: ConverterActions) {
    val target = state.target
    val targets = state.availableTargets

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Spacing.screen,
                end = Spacing.screen,
                top = Spacing.small,
                bottom = Spacing.medium
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.large)
        ) {
            item { SourceSummary(state) }

            state.sourceWarning?.let { warning ->
                item {
                    InfoBanner(
                        warning,
                        Icons.Outlined.WarningAmber,
                        tone = ConverterDesign.tokens.warning
                    )
                }
            }

            if (targets.isEmpty()) {
                item {
                    InfoBanner(
                        "Aucune conversion n'est possible pour ce fichier. " +
                            "Il peut toujours être compressé en archive ZIP.",
                        Icons.Outlined.Info,
                        tone = ConverterDesign.tokens.warning
                    )
                }
            } else {
                item {
                    SectionHeader(
                        "Convertir vers",
                        subtitle = "Seuls les formats réellement possibles sont proposés"
                    )
                }
                items(targets.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.compact)) {
                        row.forEach { format ->
                            FormatCard(
                                format = format,
                                selected = format == target,
                                modifier = Modifier.weight(1f),
                                onClick = { state.chooseTarget(format) }
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            if (target != null) {
                // Un `item` qui n'émet rien laisserait un espacement orphelin :
                // on ne l'ajoute que si la section a quelque chose à montrer.
                if (target.optionGroup != OptionGroup.NONE || state.canMergeToPdf) {
                    item { OptionsSection(state, target) }
                }
                item { ConversionSummary(state, target) }
            }
        }

        BottomAction(state, target, actions)
    }
}

@Composable
private fun SourceSummary(state: ConverterState) {
    val tokens = ConverterDesign.tokens
    val single = state.singleSource
    if (single != null) {
        SurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(iconFor(single.kind), tokens.accentFor(single.kind), size = 52.dp)
                Spacer(Modifier.width(Spacing.medium))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        single.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            single.extension.uppercase().ifBlank { null },
                            formatSize(single.sizeBytes),
                            single.detail
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    single.location?.let { location ->
                        Text(
                            location,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        return
    }

    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                "${state.selection.size} fichiers sélectionnés",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                formatSize(state.selection.sumOf { it.sizeBytes }) + " au total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.compact))
            state.selection.take(6).forEach { file -> SourceRow(file) }
            if (state.selection.size > 6) {
                Text(
                    "et ${state.selection.size - 6} autres",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.tiny)
                )
            }
        }
    }
}

@Composable
private fun SourceRow(file: SourceFile) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.tiny + 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            iconFor(file.kind),
            null,
            tint = ConverterDesign.tokens.accentFor(file.kind),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.small))
        Text(
            file.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            formatSize(file.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FormatCard(
    format: TargetFormat,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = ConverterDesign.tokens.accentFor(format)
    SurfaceCard(modifier = modifier, onClick = onClick, selected = selected) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.compact),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(iconFor(format), accent, size = 40.dp)
            Spacer(Modifier.width(Spacing.compact))
            Column(modifier = Modifier.weight(1f)) {
                Text(format.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    format.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OptionsSection(state: ConverterState, target: TargetFormat) {
    val sourceKind = state.selection.firstOrNull()?.kind ?: FileKind.OTHER
    val group = target.optionGroup

    Column {
        SectionHeader("Options", subtitle = "Seuls les réglages utiles à ce format sont affichés")
        Spacer(Modifier.height(Spacing.compact))
        SurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium)
            ) {
                when (group) {
                    OptionGroup.IMAGE -> ImageOptions(state, target, sourceKind)
                    OptionGroup.PDF -> PdfOptions(state, sourceKind)
                    OptionGroup.AUDIO -> AudioOptions(state, target)
                    OptionGroup.VIDEO -> VideoOptions()
                    OptionGroup.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun ImageOptions(state: ConverterState, target: TargetFormat, sourceKind: FileKind) {
    val options = state.options
    if (target != TargetFormat.PNG) {
        QualitySlider(options.quality) { value -> state.updateOptions { it.copy(quality = value) } }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    SegmentedChoice(
        label = if (sourceKind == FileKind.PDF) "Résolution du rendu" else "Taille de l'image",
        options = ScalePreset.values().toList(),
        selected = options.scale,
        labelOf = { it.shortLabel }
    ) { value -> state.updateOptions { it.copy(scale = value) } }

    if (sourceKind == FileKind.IMAGE) {
        SegmentedChoice(
            label = "Rotation",
            options = Rotation.values().toList(),
            selected = options.rotation,
            labelOf = { it.shortLabel }
        ) { value -> state.updateOptions { it.copy(rotation = value) } }
    }

    if (target == TargetFormat.JPG && sourceKind == FileKind.IMAGE) {
        ToggleRow(
            title = "Conserver les métadonnées",
            subtitle = "Date, appareil et position de la prise de vue",
            checked = options.keepMetadata
        ) { value -> state.updateOptions { it.copy(keepMetadata = value) } }
    }

    if (sourceKind == FileKind.PDF) {
        InfoBanner(
            "Un PDF de plusieurs pages produit une image par page, réunies dans une archive ZIP.",
            Icons.Outlined.Info
        )
    }
}

@Composable
private fun PdfOptions(state: ConverterState, sourceKind: FileKind) {
    val options = state.options
    val sizes = if (sourceKind == FileKind.IMAGE) {
        PageSize.values().toList()
    } else {
        listOf(PageSize.A4, PageSize.LETTER)
    }
    ChoiceRows(
        label = "Taille de page",
        options = sizes,
        selected = if (options.pageSize in sizes) options.pageSize else sizes.first(),
        labelOf = { it.label }
    ) { value -> state.updateOptions { it.copy(pageSize = value) } }

    if (options.pageSize != PageSize.FIT_IMAGE) {
        SegmentedChoice(
            label = "Orientation",
            options = PageOrientation.values().toList(),
            selected = options.orientation,
            labelOf = { it.label }
        ) { value -> state.updateOptions { it.copy(orientation = value) } }
    }

    if (sourceKind == FileKind.IMAGE) {
        SegmentedChoice(
            label = "Qualité des images",
            options = ScalePreset.values().toList(),
            selected = options.scale,
            labelOf = { it.shortLabel }
        ) { value -> state.updateOptions { it.copy(scale = value) } }
    }

    if (state.canMergeToPdf) {
        ToggleRow(
            title = "Réunir dans un seul PDF",
            subtitle = "Une image par page, dans l'ordre de la sélection",
            checked = options.mergeIntoSinglePdf
        ) { value -> state.updateOptions { it.copy(mergeIntoSinglePdf = value) } }
    }
}

@Composable
private fun AudioOptions(state: ConverterState, target: TargetFormat) {
    val options = state.options
    if (target == TargetFormat.M4A) {
        ChoiceRows(
            label = "Débit",
            options = AudioBitrate.values().toList(),
            selected = options.audioBitrate,
            labelOf = { it.label }
        ) { value -> state.updateOptions { it.copy(audioBitrate = value) } }
    } else {
        InfoBanner(
            "Le WAV n'est pas compressé : la qualité est intégrale, le fichier est nettement plus lourd.",
            Icons.Outlined.Info
        )
    }
    ChoiceRows(
        label = "Fréquence d'échantillonnage",
        options = SampleRate.values().toList(),
        selected = options.sampleRate,
        labelOf = { it.label }
    ) { value -> state.updateOptions { it.copy(sampleRate = value) } }
}

@Composable
private fun VideoOptions() {
    InfoBanner(
        "Les images sont recopiées telles quelles, sans réencodage : la qualité d'origine " +
            "est conservée et la conversion est rapide. Seule une piste son incompatible " +
            "avec le MP4 est réencodée.",
        Icons.Outlined.Info
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.compact)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConversionSummary(state: ConverterState, target: TargetFormat) {
    val single = state.singleSource
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text("Récapitulatif", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.small))
            SummaryRow(
                "Fichier source",
                single?.displayName ?: "${state.selection.size} fichiers"
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            SummaryRow("Format de sortie", target.label)
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            val estimate = state.estimatedSize
            SummaryRow(
                "Taille estimée",
                when {
                    state.estimating -> "Calcul en cours…"
                    estimate != null -> "≈ ${formatSize(estimate)}"
                    else -> "Connue après conversion"
                }
            )
        }
    }
}

@Composable
private fun BottomAction(
    state: ConverterState,
    target: TargetFormat?,
    actions: ConverterActions
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.screen)) {
            PrimaryAction(
                text = if (state.selection.size > 1) {
                    "Convertir ${state.selection.size} fichiers"
                } else {
                    "Convertir"
                },
                icon = Icons.Filled.Autorenew,
                enabled = target != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = state::start
            )
            TextButton(
                onClick = actions.pickMultiple,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Choisir d'autres fichiers", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
