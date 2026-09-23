package com.docssuite.transfer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.docssuite.transfer.DiscoveredPeer
import com.docssuite.transfer.ReceiveState
import com.docssuite.transfer.SendState
import com.docssuite.transfer.TransferFile

/** Entrée du module : à brancher dans la navigation de l'application. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(onBack: () -> Unit) {
    val state = rememberTransferState()
    DisposableEffect(state) { onDispose { state.dispose() } }

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        state.dismissMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle(state), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (state.mode == TransferMode.HOME) onBack() else state.reset() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.mode) {
                TransferMode.HOME -> TransferHomeContent(state)
                TransferMode.SEND -> SendContent(state)
                TransferMode.RECEIVE -> ReceiveContent(state)
            }
        }
    }
}

private fun topBarTitle(state: TransferState): String = when (state.mode) {
    TransferMode.HOME -> "Transfert d'appareil à appareil"
    TransferMode.SEND -> "Envoyer"
    TransferMode.RECEIVE -> "Recevoir"
}

// ------------------------------------------------------------------ accueil

@Composable
private fun TransferHomeContent(state: TransferState) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "Envoie ou reçois des fichiers, de n'importe quel type, directement entre deux appareils avec DocsApp Suite installé : par le même Wi-Fi, par Wi-Fi Direct sans routeur, ou par câble en activant le partage de connexion USB.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        ActionCard("Envoyer", "Choisir des fichiers à proposer à un autre appareil", Icons.Filled.SendToMobile, Color(0xFF4F46E5), state::goToSend)
        Spacer(Modifier.height(12.dp))
        ActionCard("Recevoir", "Trouver un appareil qui propose des fichiers, ou saisir son adresse", Icons.Filled.WifiTethering, Color(0xFF0F766E), state::goToReceive)
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(56.dp).background(color, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// -------------------------------------------------------------------- envoi

@Composable
private fun SendContent(state: TransferState) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        state.pickFilesForSend(uris)
    }

    if (state.filesToSend.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.SendToMobile, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Choisis les fichiers à envoyer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "N'importe quel type de fichier, un ou plusieurs à la fois",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { runCatching { pickFiles.launch(arrayOf("*/*")) } }) {
                Text("Choisir des fichiers")
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("${state.filesToSend.size} fichier(s) proposé(s)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.filesToSend) { offered ->
                FileRow(offered.meta.name, offered.meta.size)
            }
        }
        SendStatusCard(state)
    }
}

@Composable
private fun SendStatusCard(state: TransferState) {
    when (val send = state.sendState) {
        null, is SendState.Listening, is SendState.PeerConnecting -> {
            val listening = send as? SendState.Listening
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (send is SendState.PeerConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Un appareil se connecte…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("En attente de connexion", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Code à saisir sur l'autre appareil", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listening?.pin ?: "----",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Sur le même Wi-Fi, l'autre appareil te trouve automatiquement dans sa liste. Sinon, active le Wi-Fi Direct ci-dessous, ou donne-lui l'adresse IP de cet appareil (par câble, active d'abord le partage de connexion USB).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!state.wifiDirectVisible) {
                        val requestWifiDirect = rememberWifiDirectPermission(state::makeVisibleOnWifiDirect)
                        OutlinedButton(onClick = requestWifiDirect, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.WifiTethering, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Se rendre visible en Wi-Fi Direct")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Visible en Wi-Fi Direct", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = state::reset) { Text("Annuler") }
                }
            }
        }
        is SendState.Rejected -> {
            InfoCard(Icons.Filled.Error, "« ${send.reason} »", "Le code a été refusé ; l'appareil peut réessayer.")
        }
        is SendState.Sending -> {
            ProgressCard("Envoi de « ${send.fileName} »", send.fileIndex + 1, send.fileCount, send.bytesSent, send.fileSize)
        }
        SendState.Completed -> ResultCard(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, "Transfert terminé", "Tous les fichiers sélectionnés ont été envoyés.", state::reset)
        is SendState.Failed -> ResultCard(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Échec de l'envoi", send.message, state::reset)
        SendState.Cancelled -> ResultCard(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Envoi annulé", "Le transfert a été interrompu.", state::reset)
        SendState.Stopped -> ResultCard(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.onSurfaceVariant, "Écoute arrêtée", "Aucun appareil ne s'est connecté.", state::reset)
    }
}

// ----------------------------------------------------------------- réception

@Composable
private fun ReceiveContent(state: TransferState) {
    val context = LocalContext.current
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            state.receiveInto(uri)
        }
    }

    val receiveState = state.receiveState
    when {
        receiveState is ReceiveState.ReviewingManifest || (receiveState is ReceiveState.Receiving) -> {
            ManifestOrProgressContent(state, receiveState, onChooseFolder = { runCatching { chooseFolder.launch(null) } })
        }
        receiveState is ReceiveState.Completed -> ResultCard(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, "Réception terminée", "${receiveState.received.size} fichier(s) enregistré(s).", state::reset)
        receiveState is ReceiveState.Failed -> ResultCard(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Échec de la réception", receiveState.message, state::reset)
        receiveState is ReceiveState.Cancelled -> ResultCard(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Réception annulée", "Le transfert a été interrompu.", state::reset)
        state.pendingPeer != null -> PinEntryDialog(state)
        state.discoveryMethod != null -> DiscoveryContent(state)
        else -> DiscoveryMethodChoice(state)
    }
}

@Composable
private fun DiscoveryMethodChoice(state: TransferState) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Comment trouver l'appareil ?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        MethodCard("Même réseau Wi-Fi", "L'appareil apparaît automatiquement dans une liste", Icons.Filled.Wifi, Color(0xFF0F766E)) {
            state.chooseDiscovery(DiscoveryMethod.SAME_WIFI)
        }
        Spacer(Modifier.height(10.dp))
        val requestWifiDirect = rememberWifiDirectPermission { state.chooseDiscovery(DiscoveryMethod.WIFI_DIRECT) }
        MethodCard("Wi-Fi Direct (sans routeur)", "Connexion directe entre les deux appareils", Icons.Filled.WifiTethering, Color(0xFF7C3AED), requestWifiDirect)
        Spacer(Modifier.height(10.dp))
        MethodCard("Câble ou adresse manuelle", "Partage de connexion USB, ou IP saisie à la main", Icons.Filled.Cable, Color(0xFF4F46E5)) {
            state.chooseDiscovery(DiscoveryMethod.MANUAL)
        }
    }
}

@Composable
private fun MethodCard(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).background(color, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiscoveryContent(state: TransferState) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        // Une recherche active tourne pour le Wi-Fi et le Wi-Fi Direct ; la
        // saisie manuelle ne cherche rien, l'indicateur y serait trompeur.
        if (state.discoveryMethod != DiscoveryMethod.MANUAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    when (state.discoveryMethod) {
                        DiscoveryMethod.SAME_WIFI -> "Recherche sur le Wi-Fi…"
                        DiscoveryMethod.WIFI_DIRECT -> "Recherche en Wi-Fi Direct…"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        when (state.discoveryMethod) {
            DiscoveryMethod.SAME_WIFI -> {
                if (state.discoveredPeers.isEmpty()) {
                    Text("Aucun appareil trouvé pour l'instant…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.discoveredPeers) { peer ->
                            PeerRow(peer.name) { state.selectDiscoveredPeer(peer) }
                        }
                    }
                }
            }
            DiscoveryMethod.WIFI_DIRECT -> {
                if (state.wifiDirectDevices.isEmpty()) {
                    Text("Aucun appareil trouvé pour l'instant…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.wifiDirectDevices) { device: WifiP2pDevice ->
                            PeerRow(device.deviceName.ifBlank { device.deviceAddress }) { state.selectWifiDirectDevice(device) }
                        }
                    }
                }
            }
            DiscoveryMethod.MANUAL -> {
                OutlinedTextField(
                    value = state.manualHost,
                    onValueChange = { state.manualHost = it },
                    label = { Text("Adresse IP de l'autre appareil") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.manualPort,
                    onValueChange = { state.manualPort = it },
                    label = { Text("Port (laisser tel quel en général)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = state::connectManually, modifier = Modifier.fillMaxWidth()) {
                    Text("Se connecter")
                }
            }
            null -> {}
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = state::reset) { Text("Annuler") }
    }
}

@Composable
private fun PeerRow(name: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PinEntryDialog(state: TransferState) {
    val peer = state.pendingPeer ?: return
    val connecting = state.receiveState is ReceiveState.Connecting || state.receiveState is ReceiveState.Verifying
    AlertDialog(
        onDismissRequest = { if (!connecting) state.cancelPendingPeer() },
        title = { Text("Code de « ${peer.name} »") },
        text = {
            Column {
                Text("Saisis le code à 4 chiffres affiché sur l'autre appareil.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.pinInput,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) state.pinInput = it },
                    singleLine = true,
                    enabled = !connecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (connecting) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Connexion…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = state::confirmPin, enabled = !connecting && state.pinInput.length == 4) {
                Text("Se connecter")
            }
        },
        dismissButton = {
            TextButton(onClick = state::cancelPendingPeer, enabled = !connecting) { Text("Annuler") }
        }
    )
}

@Composable
private fun ManifestOrProgressContent(state: TransferState, receiveState: ReceiveState, onChooseFolder: () -> Unit) {
    if (receiveState is ReceiveState.Receiving) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            ProgressCard("Réception de « ${receiveState.fileName} »", receiveState.fileIndex + 1, receiveState.fileCount, receiveState.bytesReceived, receiveState.fileSize)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("« ${state.senderName} » propose :", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.manifest) { index, file ->
                ManifestFileRow(
                    file = file,
                    checked = state.selected.getOrElse(index) { false },
                    onToggle = { state.toggleSelected(index) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.selectAll(true) }) { Text("Tout cocher") }
            TextButton(onClick = { state.selectAll(false) }) { Text("Tout décocher") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onChooseFolder,
            enabled = state.selected.any { it },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choisir où enregistrer et recevoir")
        }
    }
}

@Composable
private fun ManifestFileRow(file: TransferFile, checked: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Text(formatBytes(file.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FileRow(name: String, size: Long) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Text(formatBytes(size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProgressCard(title: String, index: Int, count: Int, done: Long, total: Long) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Fichier $index / $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            val fraction = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("${formatBytes(done)} / ${formatBytes(total)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, subtitle: String) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultCard(icon: ImageVector, color: Color, title: String, subtitle: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onDone) { Text("Terminer") }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes o"
    val units = listOf("Ko", "Mo", "Go", "To")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val decimals = if (value >= 100) 0 else 1
    return String.format(java.util.Locale.FRANCE, "%.${decimals}f %s", value, units[unit])
}

/**
 * Le Wi-Fi Direct exige une permission d'exécution : la localisation avant
 * Android 13, puis une permission dédiée. [onGranted] est appelé tout de
 * suite si elle est déjà accordée, sinon après que l'utilisateur l'accepte.
 */
@Composable
private fun rememberWifiDirectPermission(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            launcher.launch(permission)
        }
    }
}
