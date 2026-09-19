package com.docssuite.media

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.docssuite.core.ListPickerDialog
import com.docssuite.core.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Track(val uri: Uri, val name: String, val info: TrackInfo?)

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val playlist = remember { mutableStateListOf<Track>() }
    var currentIndex by remember { mutableStateOf(-1) }
    var showSpeed by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    val controller = remember { PlaybackController(context) }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun playAt(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        controller.load(playlist[index].uri)
    }

    controller.onCompleted = {
        if (currentIndex + 1 in playlist.indices) playAt(currentIndex + 1) else Unit
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    // La permission persistante permet de rouvrir la piste
                    // après un aller-retour vers l'accueil.
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val name = displayName(context, uri) ?: "Média"
                    Track(uri, name, readTrackInfo(context, uri, name))
                }
            }
            val wasEmpty = playlist.isEmpty()
            playlist.addAll(added)
            if (wasEmpty && playlist.isNotEmpty()) playAt(0)
        }
    }

    LaunchedEffect(controller.isPlaying) {
        while (controller.isPlaying) {
            controller.refreshPosition()
            delay(250)
        }
    }

    LaunchedEffect(controller.error) {
        controller.error?.let {
            report(it)
            controller.error = null
        }
    }

    val current = playlist.getOrNull(currentIndex)
    val hasVideo = controller.videoWidth > 0 && controller.videoHeight > 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                title = {
                    Column {
                        Text(
                            current?.info?.title ?: current?.name ?: "Lecteur",
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        val subtitle = current?.info?.artist
                            ?: playlist.size.takeIf { it > 0 }?.let { "$it fichier(s)" }
                        subtitle?.let {
                            Text(
                                it,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(MediaFormats.pickerMimeTypes) }) {
                        Icon(Icons.Filled.LibraryAdd, contentDescription = "Ajouter des médias")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .then(
                        if (hasVideo) {
                            Modifier.aspectRatio(
                                controller.videoWidth.toFloat() / controller.videoHeight
                            )
                        } else {
                            Modifier.height(if (current == null) 0.dp else 220.dp)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (current != null) {
                    VideoSurface(controller = controller, visible = hasVideo)
                    if (!hasVideo) AudioArtwork(current)
                }
            }

            if (current == null) {
                EmptyState { picker.launch(MediaFormats.pickerMimeTypes) }
            } else {
                Controls(
                    controller = controller,
                    scrubbing = scrubbing,
                    onScrub = { scrubbing = it },
                    onScrubEnd = {
                        scrubbing?.let { controller.seekTo(it.toInt()) }
                        scrubbing = null
                    },
                    onPrevious = {
                        // Deux secondes de lecture écoulées : le bouton
                        // « précédent » revient d'abord au début de la piste.
                        if (controller.positionMs > 2000) controller.seekTo(0)
                        else if (currentIndex > 0) playAt(currentIndex - 1)
                        else controller.seekTo(0)
                    },
                    onNext = {
                        if (currentIndex + 1 in playlist.indices) playAt(currentIndex + 1)
                    },
                    hasNext = currentIndex + 1 in playlist.indices,
                    onSpeed = { showSpeed = true }
                )

                Playlist(
                    tracks = playlist,
                    currentIndex = currentIndex,
                    onSelect = ::playAt,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showSpeed) {
        ListPickerDialog(
            title = "Vitesse de lecture",
            items = speeds,
            label = { "×$it" },
            selected = controller.speed,
            onPick = {
                controller.changeSpeed(it)
                showSpeed = false
            },
            onDismiss = { showSpeed = false }
        )
    }
}

@Composable
private fun VideoSurface(controller: PlaybackController, visible: Boolean) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .then(if (visible) Modifier else Modifier.size(1.dp)),
        factory = { context ->
            TextureView(context).apply {
                // Sans cela, l'écran s'éteint au milieu d'un film.
                keepScreenOn = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) = controller.attachSurface(Surface(texture))

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        controller.detachSurface()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        }
    )
}

@Composable
private fun AudioArtwork(track: Track) {
    val artwork = remember(track) {
        track.info?.artwork?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
    }
    if (artwork != null) {
        Image(
            bitmap = artwork.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(84.dp)
        )
    }
}

@Composable
private fun Controls(
    controller: PlaybackController,
    scrubbing: Float?,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    hasNext: Boolean,
    onSpeed: () -> Unit
) {
    val duration = controller.durationMs.coerceAtLeast(1)
    val position = scrubbing?.toInt() ?: controller.positionMs

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Slider(
            value = (scrubbing ?: controller.positionMs.toFloat()).coerceIn(0f, duration.toFloat()),
            onValueChange = onScrub,
            onValueChangeFinished = onScrubEnd,
            valueRange = 0f..duration.toFloat(),
            enabled = controller.isPrepared
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position), style = MaterialTheme.typography.labelMedium)
            Text(
                formatDuration(controller.durationMs),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.repeatOne = !controller.repeatOne }) {
                Icon(
                    if (controller.repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Répéter la piste",
                    tint = if (controller.repeatOne) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Précédent")
            }
            IconButton(onClick = { controller.skip(-10_000) }) {
                Icon(Icons.Filled.Replay10, contentDescription = "Reculer de 10 secondes")
            }

            MaterialSurface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(60.dp).clickable { controller.toggle() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (controller.isPlaying) "Pause" else "Lire",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            IconButton(onClick = { controller.skip(10_000) }) {
                Icon(Icons.Filled.Forward10, contentDescription = "Avancer de 10 secondes")
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Suivant")
            }
            IconButton(onClick = onSpeed) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = "Vitesse de lecture",
                    tint = if (controller.speed != 1f) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun Playlist(
    tracks: List<Track>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(tracks) { index, track ->
            val active = index == currentIndex
            MaterialSurface(
                shape = RoundedCornerShape(12.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                modifier = Modifier.fillMaxWidth().clickable { onSelect(index) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (track.info?.hasVideo == true) {
                            Icons.Filled.Videocam
                        } else {
                            Icons.Filled.Audiotrack
                        },
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            track.info?.title ?: track.name,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                        track.info?.artist?.let {
                            Text(
                                it,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        formatDuration(track.info?.durationMs ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Text(
            "Aucun média",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "Ajoutez des vidéos (MP4, MKV, WebM, 3GP…) ou de la musique " +
                "(MP3, M4A, AAC, FLAC, OGG, WAV…) pour les lire à la suite.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        Button(onClick = onPick) {
            Icon(Icons.Filled.LibraryAdd, contentDescription = null)
            Text("  Choisir des fichiers")
        }
    }
}
