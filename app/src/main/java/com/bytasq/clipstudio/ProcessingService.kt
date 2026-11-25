package com.bytasq.clipstudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.UUID

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "AI Clip Studio",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
        if (!isYoutubeUrl(url) || jobId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification("İşlem başlatılıyor...", 0))
        scope.launch {
            try {
                runJob(url, jobId)
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName
                    ?: "Video hazırlanamadı."
                writeState(jobId, "FAILED", readProgress(jobId), message)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runJob(url: String, jobId: String) {
        val jobDir = File(filesDir, "jobs/$jobId").apply { mkdirs() }
        writeState(jobId, "PROCESSING", 0.01f, "Bağlantı doğrulanıyor...")

        val python = Python.getInstance()
        val downloader = python.getModule("youtube_downloader")
        val progressFile = File(jobDir, "$jobId.progress.json")

        writeState(jobId, "PROCESSING", 0.03f, "YouTube videosu indiriliyor...")
        val downloadTask = scope.async(Dispatchers.IO) {
            downloader.callAttr(
                "download",
                url,
                jobDir.absolutePath,
                jobId
            ).toString()
        }

        while (!downloadTask.isCompleted) {
            readDownloadProgress(progressFile, jobId)
            delay(350)
        }

        val downloaded = File(
            org.json.JSONObject(downloadTask.await()).getString("path")
        )
        require(downloaded.isFile && downloaded.length() > 1024) {
            "İndirilen video dosyası geçersiz."
        }
        writeState(jobId, "PROCESSING", 0.25f, "Video indirildi. Transkript aranıyor...")

        val transcript = try {
            val transcriptModule = python.getModule("youtube_transcript")
            val raw = transcriptModule.callAttr("fetch", url).toString()
            parseTranscript(raw)
        } catch (_: Exception) {
            writeState(
                jobId,
                "PROCESSING",
                0.27f,
                "YouTube altyazısı bulunamadı. Güvenli bölümleme hazırlanıyor..."
            )
            emptyList()
        }

        val usable = transcript.filter {
            it.endMs > it.startMs && it.text.trim().isNotEmpty()
        }

        if (usable.isEmpty()) {
            createFallback(jobId, downloaded)
            return
        }

        writeState(jobId, "PROCESSING", 0.38f, "Transkript temizleniyor...")
        delay(200)

        writeState(jobId, "PROCESSING", 0.40f, "Konu sınırları yapay zekâ mantığıyla analiz ediliyor...")
        val topics = TopicAnalyzer.analyze(usable) { percent, status ->
            writeState(
                jobId,
                "PROCESSING",
                0.40f + percent / 100f * 0.22f,
                status
            )
        }

        if (topics.isEmpty()) {
            throw IllegalStateException("Anlamlı konu bölümü üretilemedi.")
        }

        val selected = topics
            .sortedByDescending { it.score }
            .take(4)
            .sortedBy { it.startMs }

        val results = mutableListOf<ClipResult>()
        selected.forEachIndexed { index, topic ->
            val progress = 0.63f + (index.toFloat() / selected.size) * 0.32f
            writeState(
                jobId,
                "PROCESSING",
                progress,
                "Short " + (index + 1) + "/" + selected.size + " render ediliyor..."
            )

            val rendered = VideoRenderer.render(
                context = this,
                input = downloaded,
                startMs = topic.startMs,
                endMs = topic.endMs
            )

            results += ClipResult(
                path = rendered.absolutePath,
                title = topic.title,
                startMs = topic.startMs,
                endMs = topic.endMs,
                score = topic.score
            )
        }

        saveResults(jobId, results)
        writeState(
            jobId,
            "COMPLETED",
            1f,
            results.size.toString() + " Short hazırlandı. Önizleme için videoya dokunun."
        )
    }

    private fun createFallback(jobId: String, video: File) {
        val duration = mediaDurationMs(video)
        if (duration < 5_000L) throw IllegalStateException("Video çok kısa.")

        val length = minOf(60_000L, duration)
        val start = if (duration > length) (duration - length) / 2 else 0L

        writeState(
            jobId,
            "PROCESSING",
            0.55f,
            "Altyazı yok. Güvenli " + (length / 1000L) + " saniyelik bölüm hazırlanıyor..."
        )

        val output = VideoRenderer.render(
            this,
            video,
            start,
            start + length
        )

        saveResults(
            jobId,
            listOf(
                ClipResult(
                    path = output.absolutePath,
                    title = "AI Short",
                    startMs = start,
                    endMs = start + length,
                    score = 50
                )
            )
        )

        writeState(jobId, "COMPLETED", 1f, "Short hazırlandı. Önizleme için videoya dokunun.")
    }

    private fun mediaDurationMs(video: File): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun parseTranscript(raw: String): List<TranscriptLine> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TranscriptLine(
                        startMs = item.getLong("startMs"),
                        endMs = item.getLong("endMs"),
                        text = item.getString("text")
                    )
                )
            }
        }
    }

    private fun readDownloadProgress(file: File, jobId: String) {
        if (!file.isFile) return
        try {
            val json = org.json.JSONObject(file.readText())
            val percent = json.optInt("percent", 0).coerceIn(0, 100)
            val text = json.optString("message", "Video indiriliyor...")
            writeState(
                jobId,
                "PROCESSING",
                0.03f + percent / 100f * 0.22f,
                text
            )
        } catch (_: Exception) {
        }
    }

    private fun saveResults(jobId: String, results: List<ClipResult>) {
        val editor = prefs().edit()
            .putInt("job_" + jobId + "_count", results.size)

        results.forEachIndexed { index, result ->
            editor
                .putString("job_" + jobId + "_path_" + index, result.path)
                .putString("job_" + jobId + "_title_" + index, result.title)
                .putLong("job_" + jobId + "_start_" + index, result.startMs)
                .putLong("job_" + jobId + "_end_" + index, result.endMs)
                .putInt("job_" + jobId + "_score_" + index, result.score)
        }
        editor.apply()
    }

    private fun writeState(
        jobId: String,
        state: String,
        progress: Float,
        message: String
    ) {
        prefs().edit()
            .putString("job_" + jobId + "_state", state)
            .putFloat("job_" + jobId + "_progress", progress.coerceIn(0f, 1f))
            .putString("job_" + jobId + "_message", message)
            .apply()

        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIFICATION_ID,
                notification(message, (progress * 100).toInt())
            )
    }

    private fun readProgress(jobId: String): Float =
        prefs().getFloat("job_" + jobId + "_progress", 0f)

    private fun prefs() =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notification(text: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AI Clip Studio")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress.coerceIn(0, 100), progress < 1)
            .build()

    private fun isYoutubeUrl(value: String): Boolean =
        Regex(
            "^https?://(?:www\\.)?(?:youtube\\.com|youtu\\.be)/.+",
            RegexOption.IGNORE_CASE
        ).matches(value)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.bytasq.clipstudio.START"
        const val EXTRA_URL = "youtube_url"
        const val EXTRA_JOB_ID = "job_id"
        const val PREFS = "processing_jobs"

        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 7301

        fun start(context: Context, url: String): String {
            val jobId = UUID.randomUUID().toString()

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("last_job_id", jobId)
                .apply()

            val intent = Intent(context, ProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url.trim())
                .putExtra(EXTRA_JOB_ID, jobId)

            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            return jobId
        }
    }
}
