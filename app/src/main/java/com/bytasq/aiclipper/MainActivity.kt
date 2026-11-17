package com.bytasq.aiclipper

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Effects
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AIClipperApp() }
    }
}

@Composable
private fun AIClipperApp() {
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var clipRange by remember { mutableStateOf(0f..0f) }
    var playSelection by remember { mutableStateOf(false) }
    var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedVideo = uri
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("AI Clipper") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Videodan kısa klipler oluştur",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = "İlk adım: telefondan video seçme.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .height(420.dp)
                            .aspectRatio(9f / 16f)
                    ) {
                        if (selectedVideo != null) {
                            VideoPreview(
                                uri = selectedVideo!!,
                                clipRange = clipRange,
                                playSelection = playSelection,
                                onPlaySelectionConsumed = { playSelection = false },
                                onDurationChanged = { duration ->
                                    if (duration > 0L && duration != videoDurationMs) {
                                        videoDurationMs = duration
                                        clipRange = 0f..duration.toFloat()
                                    }
                                }
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = null)
                                    Spacer(Modifier.height(10.dp))
                                    Text("Henüz video seçilmedi")
                                    Text(
                                        "9:16 Shorts önizleme",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            videoPicker.launch(arrayOf("video/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.padding(3.dp))
                        Text("Video Seç")
                    }

                    OutlinedButton(
                        onClick = { },
                        enabled = selectedVideo != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.padding(3.dp))
                        Text("AI Analiz")
                    }
                }

                Text(
                    text = selectedVideo?.lastPathSegment ?: "Video seçerek önizlemeyi başlat.",
                    style = MaterialTheme.typography.labelMedium
                )

                if (selectedVideo != null) {
                    Text(
                        text = "Çıkış formatı: 9:16 • Shorts / Reels / TikTok",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
 
                if (selectedVideo != null && videoDurationMs > 0L) {
                    Text(
                        text = "Klip aralığı: ${formatTime(clipRange.start)} – ${formatTime(clipRange.endInclusive)}",
                        style = MaterialTheme.typography.titleSmall
                    )

                    RangeSlider(
                        value = clipRange,
                        onValueChange = { range ->
                            clipRange = range.start..max(range.endInclusive, range.start + 0.5f)
                        },
                        valueRange = 0f..videoDurationMs.toFloat(),
                        steps = 0
                    )

                    Button(
                        onClick = { playSelection = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Seçimi Oynat")
                    }

                    Button(
                        onClick = {
                            exportSelectedClip(
                                context = context,
                                uri = selectedVideo!!,
                                startMs = (clipRange.start * 1000L).toLong(),
                                endMs = (clipRange.endInclusive * 1000L).toLong(),
                                onStateChanged = { exportState = it }
                            )
                        },
                        enabled = exportState !is ExportState.Exporting && clipRange.endInclusive > clipRange.start,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when (exportState) {
                                ExportState.Exporting -> "Klip oluşturuluyor..."
                                ExportState.Idle, is ExportState.Completed -> "Klibi Dışa Aktar"
                                is ExportState.Failed -> "Tekrar Dene"
                            }
                        )
                    }

                    when (val state = exportState) {
                        ExportState.Exporting -> Text("MP4 hazırlanıyor...", style = MaterialTheme.typography.labelMedium)
                        is ExportState.Completed -> Text("Klip hazır: ${state.fileName}", style = MaterialTheme.typography.labelMedium)
                        is ExportState.Failed -> Text("Dışa aktarma hatası: ${state.message}", style = MaterialTheme.typography.labelMedium)
                        ExportState.Idle -> Unit
                    }
                }
            }
        }
    }
}


private sealed interface ExportState {
    data object Idle : ExportState
    data object Exporting : ExportState
    data class Completed(val fileName: String) : ExportState
    data class Failed(val message: String) : ExportState
}

private fun exportSelectedClip(
    context: android.content.Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    onStateChanged: (ExportState) -> Unit
) {
    if (endMs <= startMs) return
    onStateChanged(ExportState.Exporting)

    val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    val outputFile = File(outputDir, "AIClipper_${System.currentTimeMillis()}.mp4")

    val clipping = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(startMs)
        .setEndPositionMs(endMs)
        .build()
    val mediaItem = MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(clipping)
        .build()
    val presentation = Presentation.createForAspectRatio(
        9f / 16f,
        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
    )
    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
        .setEffects(Effects(emptyList(), listOf(presentation)))
        .build()

    val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(outputFile.absolutePath), arrayOf("video/mp4"), null
                )
                onStateChanged(ExportState.Completed(outputFile.name))
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                outputFile.delete()
                onStateChanged(ExportState.Failed(exportException.message ?: "Bilinmeyen dışa aktarma hatası"))
            }
        })
        .build()

    transformer.start(editedMediaItem, outputFile.absolutePath)
}

@Composable
private fun VideoPreview(
    uri: Uri,
    clipRange: ClosedFloatingPointRange<Float>,
    playSelection: Boolean,
    onPlaySelectionConsumed: () -> Unit,
    onDurationChanged: (Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            if (duration > 0L) onDurationChanged(duration)
            delay(250)
        }
    }

    LaunchedEffect(playSelection, clipRange) {
        if (playSelection && clipRange.endInclusive > clipRange.start) {
            player.seekTo((clipRange.start * 1000L).toLong())
            player.playWhenReady = true
            while (player.currentPosition < (clipRange.endInclusive * 1000L).toLong()) {
                delay(100)
            }
            player.pause()
            player.seekTo((clipRange.start * 1000L).toLong())
            onPlaySelectionConsumed()
        }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = true
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun formatTime(seconds: Float): String {
    val total = seconds.toInt()
    val minutes = total / 60
    val secs = total % 60
    return "%02d:%02d".format(minutes, secs)
}
