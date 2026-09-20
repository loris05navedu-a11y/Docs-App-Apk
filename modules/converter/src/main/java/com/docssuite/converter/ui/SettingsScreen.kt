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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.converter.data.HistoryRetention
import com.docssuite.converter.data.ThemeMode

@Composable
fun SettingsScreen(state: ConverterState, actions: ConverterActions) {
    val context = LocalContext.current
    var outputLabel by remember { mutableStateOf(state.settings.outputTreeLabel) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.small,
            bottom = Spacing.section
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.large)
    ) {
        item {
            SettingsGroup("Apparence") {
                SegmentedChoice(
                    label = "Thème de l'application",
                    options = ThemeMode.values().toList(),
                    selected = state.themeMode,
                    labelOf = { it.label },
                    onSelect = state::chooseTheme
                )
            }
        }

        item {
            SettingsGroup("Dossier de sortie") {
                Text(
                    outputLabel?.let { "Les fichiers convertis sont enregistrés dans « $it »." }
                        ?: "Aucun dossier choisi : l'emplacement est demandé à chaque " +
                        "enregistrement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.compact))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    OutlinedButton(
                        onClick = {
                            actions.chooseOutputFolder()
                            outputLabel = state.settings.outputTreeLabel
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.small))
                        Text(if (outputLabel == null) "Choisir" else "Changer")
                    }
                    if (outputLabel != null) {
                        TextButton(
                            onClick = {
                                state.settings.forgetOutputTree()
                                outputLabel = null
                                state.report("Dossier par défaut oublié")
                            },
                            modifier = Modifier.height(48.dp)
                        ) { Text("Retirer") }
                    }
                }
            }
        }

        item {
            SettingsGroup("Qualité par défaut") {
                QualitySlider(state.options.quality, onChange = state::chooseDefaultQuality)
                Text(
                    "Appliquée aux nouvelles conversions d'images en JPG et WEBP.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsGroup("Historique") {
                ChoiceRows(
                    label = "Durée de conservation",
                    options = HistoryRetention.values().toList(),
                    selected = state.settings.retention,
                    labelOf = { it.label },
                    onSelect = state::chooseRetention
                )
            }
        }

        item {
            SettingsGroup("À propos") {
                val version = remember {
                    runCatching {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0)
                            .versionName
                    }.getOrNull() ?: "—"
                }
                AboutRow("Version", version.toString())
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                AboutRow("Conversions", "Locales, sur l'appareil")
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                AboutRow("Bibliothèques", "Aucune bibliothèque tierce")
                Spacer(Modifier.height(Spacing.compact))
                InfoBanner(
                    "Les conversions s'appuient sur les composants d'Android : BitmapFactory " +
                        "et Bitmap pour les images, PdfRenderer et PdfDocument pour les PDF, " +
                        "MediaCodec et MediaMuxer pour l'audio et la vidéo. Vos fichiers ne " +
                        "sont jamais envoyés sur un serveur.",
                    Icons.Outlined.Info
                )
                Spacer(Modifier.height(Spacing.small))
                Text(
                    "App faite par Loris et Thao",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = Spacing.compact)
        )
        SurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(Spacing.medium)) { content() }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
