package com.bytasq.aiclipper

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Effects
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.common.util.UnstableApi
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    var analysisState by remember { mutableStateOf<AnalysisState>(AnalysisState.Idle) }
    var apiKey by remember { mutableStateOf("") }
    var transcriptText by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var generatedClips by remember { mutableStateOf<List<GeneratedClip>>(emptyList()) }
    var batchExportState by remember { mutableStateOf<BatchExportState>(BatchExportState.Idle) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val youtubePublisher = remember(context) { YouTubePublisher(context) }
    var youtubeAccount by remember { mutableStateOf<YouTubeAccount?>(youtubePublisher.lastSignedInAccount()) }
    var youtubeStatus by remember { mutableStateOf<String?>(null) }
    var approvalClip by remember { mutableStateOf<GeneratedClip?>(null) }
    var uploadingClipPath by remember { mutableStateOf<String?>(null) }
    var publishedVideoIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val youtubeLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        try {
            youtubeAccount = youtubePublisher.accountFromResult(result.data)
            youtubeStatus = "YouTube hesabı bağlandı."
        } catch (e: Exception) {
            youtubeStatus = "YouTube bağlantısı başarısız: " + (e.message ?: "Bilinmeyen hata")
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedVideo = uri
        analysisState = AnalysisState.Idle
        transcriptText = ""
        transcript = null
        analysisError = null
        clipRange = 0f..0f
        generatedClips = emptyList()
        batchExportState = BatchExportState.Idle
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

                Text(text = "Video → konuşma → analiz → klip.", style = MaterialTheme.typography.bodyMedium)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("YouTube Yayınlama", style = MaterialTheme.typography.titleMedium)
                        Text(
                            youtubeAccount?.email?.let { "Bağlı hesap: $it" } ?: "YouTube hesabı bağlı değil.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { youtubeLauncher.launch(youtubePublisher.signInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (youtubeAccount == null) "YouTube Hesabını Bağla" else "YouTube Hesabını Değiştir")
                        }
                        Text(
                            "Klipler otomatik yüklenmez; önce videoyu bir kez izleyip onaylaman gerekir.",
                            style = MaterialTheme.typography.labelMedium
                        )
                        youtubeStatus?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; analysisError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API anahtarı") },
                    placeholder = { Text("sk-...") },
                    singleLine = true
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
                        onClick = {
                            if (videoDurationMs > 0L) {
                                scope.launch {
                                    analysisState = AnalysisState.Analyzing
                                    analysisError = null
                                    try {
                                        val generatedTranscript = OpenAiTranscriptionEngine(
                                            context = context,
                                            apiKey = apiKey.trim()
                                        ).transcribe(selectedVideo!!)
                                        transcript = generatedTranscript
                                        transcriptText = generatedTranscript.fullText
                                        val result = OpenAiClipAnalysisEngine(
                                            apiKey = apiKey.trim()
                                        ).analyze(generatedTranscript)

                                        analysisState = AnalysisState.Completed(
                                            result.candidates.map {
                                                ClipCandidate(
                                                    startSeconds = it.startMs / 1000f,
                                                    endSeconds = it.endMs / 1000f,
                                                    score = it.score,
                                                    hook = it.hook,
                                                    reason = it.reason,
                                                    suggestedTitle = it.suggestedTitle
                                                )
                                            }
                                        )
                                    } catch (e: Exception) {
                                        analysisState = AnalysisState.Idle
                                        analysisError = e.message ?: "Analiz sırasında hata oluştu."
                                    }
                                }
                            }
                        },
                        enabled = selectedVideo != null &&
                                videoDurationMs > 0L &&
                                analysisState !is AnalysisState.Analyzing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.padding(3.dp))
                        Text(
                            if (analysisState is AnalysisState.Analyzing) {
                                "Analiz ediliyor..."
                            } else {
                                "AI Analiz"
                            }
                        )
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
 
                analysisError?.let {
                    Text(
                        text = "Analiz hatası: $it",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (transcriptText.isNotBlank()) {
                    Text(
                        text = "Transkript",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = transcriptText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                when (val state = analysisState) {
                    AnalysisState.Idle -> Unit
                    AnalysisState.Analyzing -> {
                        Text(
                            "Transkript çıkarıldı. AI, güçlü klipleri seçiyor...",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    is AnalysisState.Completed -> {
                        Text(
                            "Klip adayları",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "AI; hook, bilgi değeri, merak, duygu ve izlenme potansiyeline göre semantik seçim yaptı.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Button(
                            onClick = {
                                if (batchExportState !is BatchExportState.Exporting) {
                                    scope.launch {
                                        batchExportState = BatchExportState.Exporting(
                                            current = 0,
                                            total = state.candidates.size
                                        )
                                        generatedClips = emptyList()

                                        val created = mutableListOf<GeneratedClip>()
                                        state.candidates.forEachIndexed { index, candidate ->
                                            try {
                                                val file = exportClip(
                                                    context = context,
                                                    uri = selectedVideo!!,
                                                    startMs = (candidate.startSeconds * 1000L).toLong(),
                                                    endMs = (candidate.endSeconds * 1000L).toLong(),
                                                    transcript = transcript
                                                )
                                                val clipMetadata = try {
                                                    OpenAiClipMetadataEngine(
                                                        apiKey = apiKey.trim()
                                                    ).generate(
                                                        transcriptText = transcript?.segments
                                                            ?.filter {
                                                                it.endMs > (candidate.startSeconds * 1000L).toLong() &&
                                                                        it.startMs < (candidate.endSeconds * 1000L).toLong()
                                                            }
                                                            ?.joinToString(" ") { it.text }
                                                            .orEmpty(),
                                                        suggestedTitle = candidate.suggestedTitle,
                                                        hook = candidate.hook,
                                                        reason = candidate.reason
                                                    )
                                                } catch (_: Exception) {
                                                    ClipMetadata(
                                                        title = candidate.suggestedTitle,
                                                        description = candidate.reason,
                                                        hashtags = emptyList()
                                                    )
                                                }

                                                created += GeneratedClip(
                                                    fileName = file.name,
                                                    filePath = file.absolutePath,
                                                    title = clipMetadata.title,
                                                    description = clipMetadata.description,
                                                    hashtags = clipMetadata.hashtags,
                                                    score = candidate.score
                                                )
                                                generatedClips = created.toList()
                                                batchExportState = BatchExportState.Exporting(
                                                    current = index + 1,
                                                    total = state.candidates.size
                                                )
                                            } catch (e: Exception) {
                                                batchExportState = BatchExportState.Failed(
                                                    "Klip " + (index + 1) + " oluşturulamadı: " +
                                                            (e.message ?: "Bilinmeyen hata")
                                                )
                                                return@launch
                                            }
                                        }

                                        batchExportState = BatchExportState.Completed(created.size)
                                    }
                                }
                            },
                            enabled = state.candidates.isNotEmpty() &&
                                    selectedVideo != null &&
                                    batchExportState !is BatchExportState.Exporting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when (batchExportState) {
                                    BatchExportState.Idle,
                                    is BatchExportState.Completed,
                                    is BatchExportState.Failed -> "AI Kliplerinin Tümünü Oluştur"
                                    is BatchExportState.Exporting -> "Klipler oluşturuluyor..."
                                }
                            )
                        }

                        when (val batch = batchExportState) {
                            is BatchExportState.Exporting -> {
                                Text(
                                    "Klip " + batch.current + "/" + batch.total + " hazırlanıyor...",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            is BatchExportState.Completed -> {
                                Text(
                                    batch.count.toString() + " klip hazır.",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            is BatchExportState.Failed -> {
                                Text(
                                    batch.message,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            BatchExportState.Idle -> Unit
                        }

                        generatedClips.forEach { clip ->
                            GeneratedClipCard(
                                clip = clip,
                                publishedVideoId = publishedVideoIds[clip.filePath],
                                onRequestApproval = {
                                    if (youtubeAccount == null) {
                                        youtubeStatus = "Önce YouTube hesabını bağla."
                                    } else {
                                        approvalClip = clip
                                    }
                                }
                            )
                        }

                        state.candidates.forEach { candidate ->
                            ClipCandidateCard(
                                candidate = candidate,
                                onSelect = {
                                    clipRange = candidate.startSeconds..candidate.endSeconds
                                    playSelection = true
                                }
                            )
                        }
                    }
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
                                transcript = transcript,
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

    approvalClip?.let { clip ->
        YouTubeApprovalDialog(
            clip = clip,
            isUploading = uploadingClipPath == clip.filePath,
            uploadStatus = youtubeStatus,
            onDismiss = { approvalClip = null },
            onApprove = {
                uploadingClipPath = clip.filePath
                youtubeStatus = "YouTube'a yükleniyor..."
                scope.launch {
                    try {
                        val videoId = youtubePublisher.uploadVideo(
                            File(clip.filePath),
                            YouTubeUploadMetadata(
                                title = clip.title,
                                description = clip.description,
                                hashtags = clip.hashtags,
                                privacyStatus = "public"
                            )
                        )
                        publishedVideoIds = publishedVideoIds + (clip.filePath to videoId)
                        youtubeStatus = "✓ YouTube'a yayınlandı: https://youtu.be/$videoId"
                        uploadingClipPath = null
                        approvalClip = null
                    } catch (e: Exception) {
                        uploadingClipPath = null
                        youtubeStatus = "YouTube yükleme hatası: " + (e.message ?: "Bilinmeyen hata")
                    }
                }
            }
        )
    }
}


private data class ClipCandidate(
    val startSeconds: Float,
    val endSeconds: Float,
    val score: Int,
    val hook: String,
    val reason: String,
    val suggestedTitle: String
)

private data class GeneratedClip(
    val fileName: String,
    val filePath: String,
    val title: String,
    val description: String,
    val hashtags: List<String>,
    val score: Int
)

private sealed interface BatchExportState {
    data object Idle : BatchExportState
    data class Exporting(val current: Int, val total: Int) : BatchExportState
    data class Completed(val count: Int) : BatchExportState
    data class Failed(val message: String) : BatchExportState
}

private sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Analyzing : AnalysisState
    data class Completed(val candidates: List<ClipCandidate>) : AnalysisState
}

@Composable
private fun GeneratedClipCard(
    clip: GeneratedClip,
    publishedVideoId: String?,
    onRequestApproval: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(clip.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "Skor " + clip.score + " • " + clip.fileName,
                style = MaterialTheme.typography.bodySmall
            )
            if (clip.description.isNotBlank()) {
                Text(
                    clip.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (clip.hashtags.isNotEmpty()) {
                Text(
                    clip.hashtags.joinToString(" "),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(clip.filePath, style = MaterialTheme.typography.labelSmall)

            if (publishedVideoId != null) {
                Text(
                    "YouTube'a yayınlandı: https://youtu.be/$publishedVideoId",
                    style = MaterialTheme.typography.labelMedium
                )
            } else {
                Button(
                    onClick = onRequestApproval,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Videoyu İzle ve Yayınlamayı Onayla")
                }
            }
        }
    }
}

@Composable
private fun YouTubeApprovalDialog(
    clip: GeneratedClip,
    isUploading: Boolean,
    uploadStatus: String?,
    onDismiss: () -> Unit,
    onApprove: () -> Unit
) {
    var watchedOnce by remember(clip.filePath) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isUploading) onDismiss() },
        title = { Text("YouTube Yayın Onayı") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(clip.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Yayınlamadan önce klibi bir kez izlemen gerekiyor.",
                    style = MaterialTheme.typography.bodySmall
                )
                GeneratedClipVideoPreview(
                    filePath = clip.filePath,
                    onPlaybackCompleted = { watchedOnce = true }
                )
                Text(
                    if (watchedOnce) "✓ Video izlendi. Yayınlama onayı verebilirsin."
                    else "Video henüz tamamlanmadı.",
                    style = MaterialTheme.typography.labelMedium
                )
                uploadStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                enabled = watchedOnce && !isUploading
            ) {
                Text(if (isUploading) "YouTube'a yükleniyor..." else "✓ Yayınla")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUploading) {
                Text("Vazgeç")
            }
        }
    )
}

@Composable
private fun GeneratedClipVideoPreview(
    filePath: String,
    onPlaybackCompleted: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(filePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(filePath))))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    onPlaybackCompleted()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
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
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    )
}

@Composable
private fun ClipCandidateCard(
    candidate: ClipCandidate,
    onSelect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${formatTime(candidate.startSeconds)} – ${formatTime(candidate.endSeconds)}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Skor ${candidate.score}",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                candidate.suggestedTitle,
                style = MaterialTheme.typography.titleSmall
            )

            if (candidate.hook.isNotBlank()) {
                Text(
                    "Hook: ${candidate.hook}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(candidate.reason, style = MaterialTheme.typography.bodySmall)

            OutlinedButton(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Bu Klipi Seç")
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

@OptIn(UnstableApi::class)
private suspend fun exportClip(
    context: android.content.Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    transcript: Transcript?
): File = suspendCancellableCoroutine { continuation ->
    if (endMs <= startMs) {
        continuation.resumeWithException(IllegalArgumentException("Geçersiz klip aralığı."))
        return@suspendCancellableCoroutine
    }

    val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    if (!outputDir.exists() && !outputDir.mkdirs()) {
        continuation.resumeWithException(IllegalStateException("Çıkış klasörü oluşturulamadı."))
        return@suspendCancellableCoroutine
    }

    val outputFile = File(outputDir, "AIClipper_" + System.currentTimeMillis() + "_" + startMs + ".mp4")

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
    val videoEffects = mutableListOf<Effect>(presentation)

    if (transcript != null && transcript.segments.isNotEmpty()) {
        videoEffects += OverlayEffect(
            listOf(
                SubtitleTextOverlay(
                    segments = transcript.segments,
                    clipStartMs = startMs,
                    clipEndMs = endMs
                )
            )
        )
    }

    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
        .setEffects(Effects(emptyList(), videoEffects))
        .build()

    val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(
                composition: Composition,
                exportResult: ExportResult
            ) {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                if (continuation.isActive) continuation.resume(outputFile)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                outputFile.delete()
                if (continuation.isActive) {
                    continuation.resumeWithException(exportException)
                }
            }
        })
        .build()

    continuation.invokeOnCancellation {
        transformer.cancel()
        outputFile.delete()
    }

    transformer.start(editedMediaItem, outputFile.absolutePath)
}

@OptIn(UnstableApi::class)
private fun exportSelectedClip(
    context: android.content.Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    transcript: Transcript?,
    onStateChanged: (ExportState) -> Unit
) {
    onStateChanged(ExportState.Exporting)

    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        try {
            val file = exportClip(
                context = context,
                uri = uri,
                startMs = startMs,
                endMs = endMs,
                transcript = transcript
            )
            onStateChanged(ExportState.Completed(file.name))
        } catch (e: Exception) {
            onStateChanged(
                ExportState.Failed(
                    e.message ?: "Bilinmeyen dışa aktarma hatası"
                )
            )
        }
    }
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
