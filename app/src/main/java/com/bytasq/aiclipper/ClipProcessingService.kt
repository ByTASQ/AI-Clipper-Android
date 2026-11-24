package com.bytasq.aiclipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipProcessingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
            ?: return START_NOT_STICKY
        val youtubeUrl = intent.getStringExtra(EXTRA_YOUTUBE_URL).orEmpty()

        startForeground(NOTIFICATION_ID, notification("Hazırlanıyor...", 0))

        serviceScope.launch {
            try {
                process(uri, youtubeUrl)
            } catch (e: Exception) {
                updateState("FAILED", 0f, e.message ?: "Video hazırlanamadı.")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun process(uri: Uri, youtubeUrl: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("state", "PROCESSING")
            .putFloat("progress", 0f)
            .putString("status", "Video hazırlanıyor...")
            .apply()

        // YouTube isteğinde mevcut URI'yi kaynak kabul etme.
        // Her yeni buton tıklaması verilen URL'den videoyu yeniden indirir.
        val videoUri = if (youtubeUrl.isNotBlank()) {
            updateState("PROCESSING", 0.05f, "YouTube videosu indiriliyor...")
            val file = YouTubeVideoDownloader(this).download(youtubeUrl) { progress ->
                updateState(
                    "PROCESSING",
                    0.05f + progress * 0.20f,
                    "Video indiriliyor... %$progress"
                )
            }
            Uri.fromFile(file)
        } else {
            uri
        }

        val transcript = if (youtubeUrl.isNotBlank()) {
            try {
                updateState("PROCESSING", 0.25f, "YouTube transkripti alınıyor...")
                YouTubeTranscriptTranscriptionEngine(youtubeUrl) { current, total ->
                    updateState(
                        "PROCESSING",
                        0.25f + (current.toFloat() / total.coerceAtLeast(1)) * 0.25f,
                        "YouTube transkripti alınıyor... $current/$total"
                    )
                }.transcribe(videoUri)
            } catch (e: Exception) {
                updateState(
                    "PROCESSING",
                    0.25f,
                    "YouTube transkripti alınamadı, cihazdaki ses analiz ediliyor..."
                )
                localTranscribe(videoUri)
            }
        } else {
            localTranscribe(videoUri)
        }

        val validTranscriptSegments = transcript.segments.count {
            it.text.isNotBlank() && it.endMs > it.startMs
        }
        if (validTranscriptSegments == 0) {
            throw IllegalStateException(
                "Video indirildi ancak konuşma metni çıkarılamadı. " +
                    "YouTube altyazısı bulunamadı ve yerel ses analizi sonuç üretmedi."
            )
        }

        updateState(
            "PROCESSING",
            0.52f,
            "Video analiz ediliyor... " + validTranscriptSegments + " konuşma bölümü bulundu."
        )
        val result = LocalClipAnalysisEngine { percent, status ->
            val progress = 0.52f + (percent / 100f) * 0.13f
            updateState("PROCESSING", progress, status)
        }.analyze(transcript)

        val candidate = result.candidates.firstOrNull()
            ?: throw IllegalStateException(
                "Video analiz edildi ancak Short için geçerli bir anlatı bölümü üretilemedi."
            )

        val renderSegments = candidate.segments.filter { it.endMs > it.startMs }
        if (renderSegments.isEmpty()) {
            throw IllegalStateException(
                "Özet oluşturuldu ancak videoya dönüştürülecek zaman aralığı bulunamadı."
            )
        }

        val totalRenderSeconds = renderSegments.sumOf { it.endMs - it.startMs } / 1000L
        updateState(
            "PROCESSING",
            0.65f,
            "Short derleniyor... " + renderSegments.size +
                " bölüm, " + totalRenderSeconds + " saniye"
        )
        val file = ShortVideoRenderer.render(
            context = this,
            uri = videoUri,
            segments = candidate.segments.map { it.startMs to it.endMs }
        )

        updateState("PROCESSING", 0.93f, "Short bilgileri hazırlanıyor...")
        val transcriptText = candidate.segments.joinToString(" ") { range ->
            transcript.segments
                .filter { it.endMs > range.startMs && it.startMs < range.endMs }
                .joinToString(" ") { it.text }
        }

        val metadata = LocalClipMetadataEngine().generate(
            transcriptText = transcriptText,
            suggestedTitle = candidate.suggestedTitle,
            hook = candidate.hook,
            reason = candidate.reason
        )

        prefs.edit()
            .putString("state", "COMPLETED")
            .putFloat("progress", 1f)
            .putString("status", "Short hazırlandı.")
            .putString("filePath", file.absolutePath)
            .putString("fileName", file.name)
            .putString("title", metadata.title)
            .putString("description", metadata.description)
            .putString("hashtags", metadata.hashtags.joinToString("|"))
            .putInt("score", candidate.score)
            .apply()

        updateNotification("Short hazırlandı.", 100)
    }

    private suspend fun localTranscribe(videoUri: Uri): Transcript {
        return LocalWhisperTranscriptionEngine(this) { current, total ->
            updateState(
                "PROCESSING",
                0.25f + (current.toFloat() / total.coerceAtLeast(1)) * 0.35f,
                "Ses transkripti çıkarılıyor... $current/$total"
            )
        }.transcribe(videoUri)
    }

    private fun updateState(state: String, progress: Float, status: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("state", state)
            .putFloat("progress", progress.coerceIn(0f, 1f))
            .putString("status", status)
            .apply()
        updateNotification(status, (progress * 100).toInt())
    }

    private fun updateNotification(text: String, progress: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, progress))
    }

    private fun notification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AI Clipper")
            .setContentText(text)
            .setOngoing(progress < 100)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress < 1)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Video hazırlama",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.bytasq.aiclipper.START_PROCESSING"
        const val EXTRA_URI = "video_uri"
        const val EXTRA_YOUTUBE_URL = "youtube_url"
        const val PREFS = "clip_processing_state"
        private const val CHANNEL_ID = "clip_processing"
        private const val NOTIFICATION_ID = 4107

        fun start(context: Context, videoUri: Uri, youtubeUrl: String = "") {
            val intent = Intent(context, ClipProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, videoUri.toString())
                .putExtra(EXTRA_YOUTUBE_URL, youtubeUrl)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
