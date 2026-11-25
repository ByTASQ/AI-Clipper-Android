package com.bytasq.clipstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private var incomingUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUrl = extractUrl(intent)
        setContent { ClipStudioScreen(incomingUrl) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = extractUrl(intent)
        setContent { ClipStudioScreen(incomingUrl) }
    }
}

private fun extractUrl(intent: Intent?): String {
    if (intent?.action != Intent.ACTION_SEND) return ""
    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    return Regex(
        """https?://(?:www\.)?(?:youtube\.com|youtu\.be)/[^\s]+"""
    ).find(text)
        ?.value
        ?.trimEnd('.', ',', ';', ')', ']', '}')
        .orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipStudioScreen(initialUrl: String) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ProcessingService.PREFS, 0)
    }

    var url by remember { mutableStateOf(initialUrl) }
    var jobId by remember {
        mutableStateOf(prefs.getString("last_job_id", "").orEmpty())
    }

    var state by remember { mutableStateOf("IDLE") }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("YouTube bağlantısını girin.") }
    var resultPath by remember { mutableStateOf("") }
    var resultTitle by remember { mutableStateOf("") }
    var profileOpen by remember { mutableStateOf(false) }
    var apiKey by remember {
        mutableStateOf(
            context.getSharedPreferences("profile", 0)
                .getString("openai_key", "")
                .orEmpty()
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            val id = jobId
            if (id.isNotBlank()) {
                state = prefs.getString("job_" + id + "_state", "IDLE").orEmpty()
                progress = prefs.getFloat("job_" + id + "_progress", 0f)
                message = prefs.getString("job_" + id + "_message", "").orEmpty()

                if (state == "COMPLETED") {
                    val count = prefs.getInt("job_" + id + "_count", 0)
                    if (count > 0) {
                        resultPath = prefs.getString("job_" + id + "_path_0", "").orEmpty()
                        resultTitle = prefs.getString(
                            "job_" + id + "_title_0",
                            "AI Short"
                        ).orEmpty()
                    }
                }
            }
            delay(400)
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI Clip Studio") },
                    actions = {
                        TextButton(onClick = { profileOpen = true }) {
                            Text("Profil")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "YouTube videosinden konu bütünlüğünü koruyan Shorts üret",
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube bağlantısı") },
                    singleLine = true
                )

                Button(
                    onClick = {
                        val clean = normalizeYoutubeUrl(url)
                        if (isYoutubeUrl(clean)) {
                            resultPath = ""
                            resultTitle = ""
                            state = "PROCESSING"
                            message = "İşlem başlatılıyor..."
                            jobId = ProcessingService.start(context, clean)
                        } else {
                            state = "FAILED"
                            message = "Geçerli bir YouTube bağlantısı girin."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.isNotBlank() && state != "PROCESSING"
                ) {
                    Text("Short Hazırla")
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(message)
                        if (state == "PROCESSING" || state == "COMPLETED") {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text((progress * 100).toInt().toString() + "%")
                        }
                        if (state == "FAILED") {
                            Text(
                                "Hata ekranda tutuldu. İşlem penceresi kendiliğinden kapanmaz.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (resultPath.isNotBlank() && File(resultPath).isFile) {
                    Text(
                        resultTitle,
                        style = MaterialTheme.typography.titleMedium
                    )
                    VideoPreview(Uri.fromFile(File(resultPath)))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND)
                                    .setType("video/mp4")
                                    .putExtra(
                                        Intent.EXTRA_TEXT,
                                        "AI Clip Studio: " + resultTitle
                                    )
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Paylaş")
                                )
                            }
                        ) {
                            Text("Paylaş")
                        }
                    }
                }

                if (state == "COMPLETED") {
                    Text("Önizleme tamamlandı. Yayınlamadan önce videoyu kontrol edin.")
                }
            }
        }
    }

    if (profileOpen) {
        AlertDialog(
            onDismissRequest = { profileOpen = false },
            title = { Text("Profil") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OpenAI anahtarı isteğe bağlıdır ve cihazda saklanır.")
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("OpenAI API anahtarı") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSharedPreferences("profile", 0)
                        .edit()
                        .putString("openai_key", apiKey.trim())
                        .apply()
                    profileOpen = false
                }) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { profileOpen = false }) {
                    Text("İptal")
                }
            }
        )
    }
}

private fun isYoutubeUrl(value: String): Boolean =
    Regex(
        "^https?://(?:www\\.)?(?:youtube\\.com|youtu\\.be)/.+",
        RegexOption.IGNORE_CASE
    ).matches(value)

@Composable
private fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = true
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { it.player = player },
        modifier = Modifier
            .fillMaxWidth()
            .height(430.dp)
    )
}


private fun normalizeYoutubeUrl(value: String): String {
    return value.trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .trim()
}
