package com.bytasq.aiclipper

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.content.Intent
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.media3.transformer.EditedMediaItemSequence
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {
    private var sharedYoutubeUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedYoutubeUrl = extractSharedYoutubeUrl(intent)
        setContent { AIClipperApp(initialYoutubeUrl = sharedYoutubeUrl) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedYoutubeUrl = extractSharedYoutubeUrl(intent)
        setContent { AIClipperApp(initialYoutubeUrl = sharedYoutubeUrl) }
    }
}

private fun extractSharedYoutubeUrl(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_SEND) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
    if (text.isBlank()) return null

    val urlRegex = Regex("""https?://(?:www\.)?(?:youtube\.com|youtu\.be)/\S+""")
    return urlRegex.find(text)?.value?.trimEnd('.', ',', ';', ')', ']', '}')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIClipperApp(initialYoutubeUrl: String? = null) {
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var previewVideo by remember { mutableStateOf<Uri?>(null) }
    var playPreview by remember { mutableStateOf(false) }
    var previewMuted by remember { mutableStateOf(false) }
    var sharedYoutubeUrl by remember(initialYoutubeUrl) { mutableStateOf(initialYoutubeUrl.orEmpty()) }
    var youtubeUrlInput by remember(initialYoutubeUrl) { mutableStateOf(initialYoutubeUrl.orEmpty()) }
    var youtubeDownloadState by remember { mutableStateOf<YouTubeDownloadState>(YouTubeDownloadState.Idle) }
    var autoProcessYoutube by remember { mutableStateOf(false) }
    var autoProcessPickedVideo by remember { mutableStateOf(false) }
    var youtubeRequestId by remember { mutableStateOf(0) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var clipRange by remember { mutableStateOf(0f..0f) }
    var playSelection by remember { mutableStateOf(false) }
    var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var analysisState by remember { mutableStateOf<AnalysisState>(AnalysisState.Idle) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var transcriptText by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var analysisProgress by remember { mutableStateOf(0f) }
    var analysisStatus by remember { mutableStateOf("Hazırlanıyor...") }
    var generatedClips by remember { mutableStateOf<List<GeneratedClip>>(emptyList()) }
    var batchExportState by remember { mutableStateOf<BatchExportState>(BatchExportState.Idle) }
    val scope = rememberCoroutineScope()
    val youtubePublisher = remember(context) { YouTubePublisher(context) }
    var youtubeAccount by remember { mutableStateOf<YouTubeAccount?>(youtubePublisher.lastSignedInAccount()) }
    var youtubeStatus by remember { mutableStateOf<String?>(null) }
    var approvalClip by remember { mutableStateOf<GeneratedClip?>(null) }
    var uploadingClipPath by remember { mutableStateOf<String?>(null) }
    var publishedVideoIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showProfileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sharedYoutubeUrl, youtubeRequestId) {
        if (sharedYoutubeUrl.isNotBlank()) {
            // Her yeni istek, daha önce seçilmiş/indirilmiş videodan bağımsız olarak
            // doğrudan girilen URL'yi yeniden indirir. Eski selectedVideo kullanılamaz.
            selectedVideo = null
            previewVideo = null
            playPreview = false
            videoDurationMs = 0L
            clipRange = 0f..0f
            transcriptText = ""
            transcript = null
            analysisError = null
            analysisState = AnalysisState.Idle
            generatedClips = emptyList()
            batchExportState = BatchExportState.Idle
            youtubeDownloadState = YouTubeDownloadState.Downloading(0)
            try {
                    val file = YouTubeVideoDownloader(context).download(sharedYoutubeUrl) { progress ->
                        youtubeDownloadState = YouTubeDownloadState.Downloading(progress)
                    }
                    selectedVideo = Uri.fromFile(file)
                    previewVideo = selectedVideo
                    playPreview = false
                    analysisState = AnalysisState.Idle
                    transcriptText = ""
                    transcript = null
                    analysisError = null
                    clipRange = 0f..0f
                    generatedClips = emptyList()
                    batchExportState = BatchExportState.Idle
                    autoProcessYoutube = true
                    youtubeDownloadState = YouTubeDownloadState.Processing
                } catch (e: Exception) {
                    youtubeDownloadState = YouTubeDownloadState.Failed(e.message ?: "YouTube videosu indirilemedi.")
                }
            }
        }

    LaunchedEffect(autoProcessYoutube, selectedVideo, videoDurationMs) {
        if (!autoProcessYoutube || selectedVideo == null) return@LaunchedEffect
        try {
            analysisState = AnalysisState.Analyzing
            analysisError = null
            analysisProgress = 0f
            analysisStatus = "Video hazırlanıyor..."
            val generatedTranscript = if (sharedYoutubeUrl.isNotBlank()) {
                try {
                    analysisStatus = "YouTube transkripti alınıyor..."
                    YouTubeTranscriptTranscriptionEngine(sharedYoutubeUrl) { current, total ->
                        scope.launch {
                            analysisProgress = current.toFloat() / total.toFloat()
                            analysisStatus = if (current >= total) {
                                "YouTube transkripti alındı."
                            } else {
                                "YouTube transkripti alınıyor..."
                            }
                        }
                    }.transcribe(selectedVideo!!)
                } catch (_: Exception) {
                    analysisStatus = "YouTube transkripti alınamadı, ses analizi yapılıyor..."
                    LocalWhisperTranscriptionEngine(context) { current, total ->
                        scope.launch {
                            analysisProgress = current.toFloat() / total.toFloat()
                            analysisStatus = "Ses transkripti çıkarılıyor... %$current/$total"
                        }
                    }.transcribe(selectedVideo!!)
                }
            } else {
                LocalWhisperTranscriptionEngine(context) { current, total ->
                    scope.launch {
                        analysisProgress = current.toFloat() / total.toFloat()
                        analysisStatus = "Ses transkripti çıkarılıyor... %$current/$total"
                    }
                }.transcribe(selectedVideo!!)
            }
            transcript = generatedTranscript
            transcriptText = generatedTranscript.fullText
            analysisProgress = 0.95f
            analysisStatus = "Konuşma analizi tamamlandı. Klip adayları hesaplanıyor..."

            val result = LocalClipAnalysisEngine().analyze(generatedTranscript)
            val candidates = if (result.candidates.isNotEmpty()) {
                result.candidates.map {
                ClipCandidate(
                    startSeconds = it.startMs / 1000f,
                    endSeconds = it.endMs / 1000f,
                    score = it.score,
                    segments = it.segments.map { segment ->
                        ClipSegmentUi(
                            startSeconds = segment.startMs / 1000f,
                            endSeconds = segment.endMs / 1000f
                        )
                    },
                    hook = it.hook,
                    reason = it.reason,
                    suggestedTitle = it.suggestedTitle
                )
            } else if (generatedTranscript.segments.isEmpty()) {
                buildFallbackCandidates(context, selectedVideo!!)
            } else {
                emptyList()
            }
            analysisState = AnalysisState.Completed(candidates)
            analysisProgress = 1f
            analysisStatus = "Analiz tamamlandı: ${candidates.size} klip adayı bulundu."

            if (candidates.isEmpty()) {
                throw IllegalStateException("Önemli video bölümü bulunamadı.")
            }

            val candidate = candidates.first()
            batchExportState = BatchExportState.Exporting(0, 1)

            val file = exportClip(
                context = context,
                uri = selectedVideo!!,
                startMs = candidate.startSeconds.toLong() * 1000L,
                endMs = candidate.endSeconds.toLong() * 1000L,
                transcript = generatedTranscript,
                segments = candidate.segments.map {
                    it.startSeconds.toLong() * 1000L to it.endSeconds.toLong() * 1000L
                }
            )

            val clipMetadata = try {
                LocalClipMetadataEngine().generate(
                    transcriptText = candidate.segments.joinToString(" ") { range ->
                        generatedTranscript.segments
                            .filter {
                                it.endMs > (range.startSeconds * 1000L).toLong() &&
                                        it.startMs < (range.endSeconds * 1000L).toLong()
                            }
                            .joinToString(" ") { it.text }
                    },
                    suggestedTitle = candidate.suggestedTitle,
                    hook = candidate.hook,
                    reason = candidate.reason
                )
            } catch (_: Exception) {
                ClipMetadata(candidate.suggestedTitle, candidate.reason, emptyList())
            }

            val created = listOf(
                GeneratedClip(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    title = clipMetadata.title,
                    description = clipMetadata.description,
                    hashtags = clipMetadata.hashtags,
                    score = candidate.score
                )
            )

            generatedClips = created
            previewVideo = Uri.fromFile(file)
            clipRange = 0f..0f
            playSelection = false
            playPreview = true
            batchExportState = BatchExportState.Completed(1)

            youtubeDownloadState = YouTubeDownloadState.Completed
            autoProcessYoutube = false
        } catch (e: Exception) {
            analysisState = AnalysisState.Idle
            batchExportState = BatchExportState.Failed(e.message ?: "Otomatik YouTube işlemi başarısız.")
            youtubeDownloadState = YouTubeDownloadState.Failed(e.message ?: "Otomatik YouTube işlemi başarısız.")
            analysisError = null
            autoProcessYoutube = false
        }
    }

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
        previewVideo = uri
        playPreview = false
        analysisState = AnalysisState.Idle
        transcriptText = ""
        transcript = null
        analysisError = null
        clipRange = 0f..0f
        generatedClips = emptyList()
        batchExportState = BatchExportState.Idle

        if (uri != null && autoProcessPickedVideo) {
            autoProcessPickedVideo = false
            autoProcessYoutube = true
            youtubeDownloadState = YouTubeDownloadState.Processing
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI Clipper") },
                    actions = {
                        IconButton(onClick = { showProfileDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "YouTube hesabı"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            if (showProfileDialog) {
                AlertDialog(
                    onDismissRequest = { showProfileDialog = false },
                    title = { Text("YouTube Hesabı") },
                    text = {
                        Text(
                            youtubeAccount?.email?.let { "Bağlı hesap:\n$it" }
                                ?: "YouTube hesabı bağlı değil."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showProfileDialog = false
                                youtubePublisher.signOut {
                                    youtubeLauncher.launch(youtubePublisher.signInIntent())
                                }
                            }
                        ) {
                            Text(if (youtubeAccount == null) "Giriş Yap" else "Hesap Değiştir")
                        }
                    },
                    dismissButton = {
                        if (youtubeAccount != null) {
                            TextButton(
                                onClick = {
                                    youtubePublisher.signOut {
                                        youtubeAccount = null
                                        youtubeStatus = "YouTube hesabından çıkış yapıldı."
                                    }
                                    showProfileDialog = false
                                }
                            ) {
                                Text("Çıkış Yap")
                            }
                        }
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Videonu Short'a çevir",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    "Önemli bölümleri AI seçer ve tek bir Short videosunda birleştirir.",
                    style = MaterialTheme.typography.bodyMedium
                )

                TextField(
                    value = youtubeUrlInput,
                    onValueChange = { youtubeUrlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube bağlantısı") },
                    placeholder = { Text("https://youtu.be/...") },
                    singleLine = true
                )

                Button(
                    onClick = {
                        val url = youtubeUrlInput.trim()
                        if (url.isBlank()) {
                            youtubeDownloadState =
                                YouTubeDownloadState.Failed("YouTube bağlantısını gir.")
                        } else {
                            selectedVideo = null
                            previewVideo = null
                            generatedClips = emptyList()
                            analysisError = null
                            youtubeDownloadState = YouTubeDownloadState.Idle
                            sharedYoutubeUrl = url
                            youtubeRequestId += 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text("Short Oluştur")
                }

                OutlinedButton(
                    onClick = {
                        autoProcessPickedVideo = true
                        videoPicker.launch(arrayOf("video/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null)
                    Text("Telefondan Video Seç")
                }

                when (val state = youtubeDownloadState) {
                    YouTubeDownloadState.Idle -> Unit

                    is YouTubeDownloadState.Downloading -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Yüklemeler", style = MaterialTheme.typography.titleMedium)
                                Text("Video indiriliyor... %" + state.progress)
                                LinearProgressIndicator(
                                    progress = { state.progress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    YouTubeDownloadState.Processing -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Yüklemeler", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (val batch = batchExportState) {
                                        is BatchExportState.Exporting ->
                                            if (batch.total <= 1) "Short hazırlanıyor..."
                                            else "Short hazırlanıyor... " + batch.current + "/" + batch.total
                                        is BatchExportState.Completed -> "Short hazır."
                                        is BatchExportState.Failed -> "Short oluşturulamadı."
                                        BatchExportState.Idle -> analysisStatus
                                    }
                                )
                                if (analysisState is AnalysisState.Analyzing) {
                                    LinearProgressIndicator(
                                        progress = { analysisProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    YouTubeDownloadState.Completed -> Unit

                    is YouTubeDownloadState.Failed -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("İşlem başarısız", style = MaterialTheme.typography.titleMedium)
                                Text(state.message)
                                Button(
                                    onClick = {
                                        sharedYoutubeUrl = youtubeUrlInput.trim()
                                        youtubeRequestId += 1
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Tekrar Dene")
                                }
                            }
                        }
                    }
                }

                if (analysisError != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(analysisError!!, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                generatedClips.firstOrNull()?.let { clip ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Sonuç Videosu",
                                style = MaterialTheme.typography.titleLarge
                            )

                            Text(
                                clip.title,
                                style = MaterialTheme.typography.titleMedium
                            )

                            GeneratedClipVideoPreview(
                                filePath = clip.filePath,
                                onPlaybackCompleted = {}
                            )

                            Button(
                                onClick = { approvalClip = clip },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Onayla ve Paylaş")
                            }

                            TextButton(
                                onClick = {
                                    generatedClips = emptyList()
                                    sharedYoutubeUrl = youtubeUrlInput.trim()
                                    youtubeRequestId += 1
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Yeniden Oluştur")
                            }
                        }
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
            youtubeAccount = youtubeAccount,
            onSelectAccount = {
                youtubePublisher.signOut {
                    youtubeLauncher.launch(youtubePublisher.signInIntent())
                }
            },
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

private sealed interface YouTubeDownloadState {
    data object Idle : YouTubeDownloadState
    data class Downloading(val progress: Int) : YouTubeDownloadState
    data object Processing : YouTubeDownloadState
    data object Completed : YouTubeDownloadState
    data class Failed(val message: String) : YouTubeDownloadState
}

private data class ClipSegmentUi(
    val startSeconds: Float,
    val endSeconds: Float
)

private data class ClipCandidate(
    val startSeconds: Float,
    val endSeconds: Float,
    val score: Int,
    val hook: String,
    val reason: String,
    val suggestedTitle: String,
    val segments: List<ClipSegmentUi> = listOf(
        ClipSegmentUi(startSeconds, endSeconds)
    )
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
    youtubeAccount: YouTubeAccount?,
    onSelectAccount: () -> Unit,
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
                    "Yayın hesabı: " + (youtubeAccount?.email ?: "Henüz seçilmedi"),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onSelectAccount,
                    enabled = !isUploading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (youtubeAccount == null) "Yayın Hesabını Seç" else "Yayın Hesabını Değiştir")
                }

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
                enabled = watchedOnce && youtubeAccount != null && !isUploading
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
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
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
    transcript: Transcript?,
    segments: List<Pair<Long, Long>> = listOf(startMs to endMs)
): File = suspendCancellableCoroutine { continuation ->
    val normalizedSegments = segments
        .map { (start, end) -> start.coerceAtLeast(0L) to end.coerceAtLeast(0L) }
        .filter { it.second > it.first }

    if (normalizedSegments.isEmpty()) {
        continuation.resumeWithException(IllegalArgumentException("Geçerli klip bölümü bulunamadı."))
        return@suspendCancellableCoroutine
    }

    val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    if (!outputDir.exists() && !outputDir.mkdirs()) {
        continuation.resumeWithException(IllegalStateException("Çıkış klasörü oluşturulamadı."))
        return@suspendCancellableCoroutine
    }

    val outputFile = File(
        outputDir,
        "AIClipper_" + System.currentTimeMillis() + "_" + normalizedSegments.first().first + ".mp4"
    )

    val editedItems = normalizedSegments.map { (clipStartMs, clipEndMs) ->
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clipStartMs)
            .setEndPositionMs(clipEndMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()

        val videoEffects = mutableListOf<Effect>(
            Presentation.createForAspectRatio(
                9f / 16f,
                Presentation.LAYOUT_SCALE_TO_FIT
            )
        )

        if (transcript != null && transcript.segments.isNotEmpty()) {
            videoEffects += OverlayEffect(
                listOf(
                    SubtitleTextOverlay(
                        segments = transcript.segments,
                        clipStartMs = clipStartMs,
                        clipEndMs = clipEndMs
                    )
                )
            )
        }

        EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
    }

    // Birden fazla kaynak bölümünü ardışık olarak tek MP4 halinde birleştir.
    val sequence = EditedMediaItemSequence(editedItems)
    val composition = Composition.Builder(sequence).build()

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

    transformer.start(composition, outputFile.absolutePath)
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
    autoPlay: Boolean = false,
    muted: Boolean = false,
    onAutoPlayConsumed: () -> Unit = {},
    onPlaySelectionConsumed: () -> Unit,
    onDurationChanged: (Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            volume = if (muted) 0f else 1f
            prepare()
            playWhenReady = autoPlay
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

    LaunchedEffect(muted) {
        player.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(autoPlay) {
        if (autoPlay) {
            player.seekTo(0L)
            player.playWhenReady = true
            onAutoPlayConsumed()
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
                useController = true
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = {
            it.player = player
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun buildFallbackCandidates(
    context: android.content.Context,
    uri: Uri
): List<ClipCandidate> {
    val retriever = MediaMetadataRetriever()
    val durationMs = try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }

    if (durationMs <= 0L) return emptyList()

    val segments = if (durationMs <= 90_000L) {
        listOf(ClipSegmentUi(0f, (durationMs / 1000f).coerceAtMost(90f)))
    } else {
        val clipLength = 18_000L
        listOf(
            ClipSegmentUi(0f, clipLength / 1000f),
            ClipSegmentUi(
                ((durationMs / 2L - clipLength / 2L).coerceAtLeast(0L) / 1000f),
                ((durationMs / 2L + clipLength / 2L).coerceAtMost(durationMs) / 1000f)
            ),
            ClipSegmentUi(
                ((durationMs - clipLength).coerceAtLeast(0L) / 1000f),
                durationMs / 1000f
            )
        )
    }

    return listOf(ClipCandidate(
        startSeconds = segments.minOf { it.startSeconds },
        endSeconds = segments.maxOf { it.endSeconds },
        score = 50,
        hook = "Konuşma metni çıkarılamadı.",
        reason = "Otomatik konuşma metni alınamadığı için güvenli kısa video bölümleri seçildi.",
        suggestedTitle = "AI Short",
        segments = segments
    ))
}

private fun formatTime(seconds: Float): String {
    val total = seconds.toInt()
    val minutes = total / 60
    val secs = total % 60
    return "%02d:%02d".format(minutes, secs)
}