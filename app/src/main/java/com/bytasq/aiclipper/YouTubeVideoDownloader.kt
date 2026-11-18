package com.bytasq.aiclipper

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.chaquo.python.Python
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class YouTubeVideoDownloader(private val context: Context) {

    companion object {
        private const val OVERLAY_DIR = "ytdlp_overlay"
        private const val OVERLAY_WHEEL = "yt_dlp-2026.10.26-py3-none-any.whl"
        private const val OVERLAY_ASSET = "ytdlp/$OVERLAY_WHEEL"
    }

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): File {
        val normalized = normalizeYoutubeUrl(url)
            ?: throw IllegalArgumentException("Geçerli bir YouTube bağlantısı girin.")

        val webCookies = collectYoutubeCookies(normalized)

        return withContext(Dispatchers.IO) {
            downloadLocally(normalized, webCookies, onProgress)
        }
    }

    private fun downloadLocally(
        url: String,
        webCookies: WebCookieSession?,
        onProgress: (Int) -> Unit
    ): File {
        val outputDir = getOutputDir()

        try {
            val python = Python.getInstance()
            val module = python.getModule("youtube_downloader")

            onProgress(5)

            val result = module.callAttr(
                "download",
                url,
                outputDir.absolutePath,
                webCookies?.cookieFile,
                webCookies?.userAgent
            )

            onProgress(100)

            val file = File(result.toString())
            if (file.isFile && file.length() > 0L) {
                return file
            }

            throw IllegalStateException("yt-dlp dosyayı oluşturdu fakat video dosyası bulunamadı.")
        } catch (t: Throwable) {
            Log.e("AIClipperYtDlp", "Python yt-dlp failed", t)
            throw IllegalStateException(
                "YouTube videosu indirilemedi.\n\n" +
                    "Python yt-dlp: " +
                    (t.message ?: t.toString()).takeLast(2500)
            )
        }
    }

    private data class WebCookieSession(
        val cookieFile: String,
        val userAgent: String
    )

    private suspend fun collectYoutubeCookies(url: String): WebCookieSession? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context.applicationContext)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                val handler = Handler(Looper.getMainLooper())
                var finished = false

                fun finish() {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)

                    try {
                        cookieManager.flush()
                        val rawCookie = cookieManager.getCookie("https://www.youtube.com")
                        val file = createNetscapeCookieFile(rawCookie)
                        if (file != null) {
                            continuation.resume(
                                WebCookieSession(
                                    file.absolutePath,
                                    webView.settings.userAgentString
                                )
                            )
                        } else {
                            continuation.resume(null)
                        }
                    } catch (_: Throwable) {
                        continuation.resume(null)
                    }

                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Throwable) {
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        handler.postDelayed({ finish() }, 1200)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            handler.postDelayed({ finish() }, 300)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    handler.post {
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }

                handler.postDelayed({ finish() }, 10000)
                webView.loadUrl(url)
            }
        }

    private fun createNetscapeCookieFile(rawCookie: String?): File? {
        if (rawCookie.isNullOrBlank()) return null

        val outputDir = getOutputDir()
        val file = File(outputDir, "youtube-cookies.txt")

        return try {
            FileWriter(file, false).use { writer ->
                writer.appendLine("# Netscape HTTP Cookie File")
                writer.appendLine("# Generated from Android WebView CookieManager")
                rawCookie.split(";").forEach { item ->
                    val separator = item.indexOf('=')
                    if (separator <= 0) return@forEach

                    val name = item.substring(0, separator).trim()
                    val value = item.substring(separator + 1).trim()
                    if (name.isBlank()) return@forEach

                    writer.append(".youtube.com\tTRUE\t/\tTRUE\t0\t")
                        .append(name)
                        .append('\t')
                        .append(value)
                        .append('\n')
                }
            }
            if (file.length() > 0L) file else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOutputDir(): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("Uygulama video klasörü oluşturulamadı.")

        val outputDir = File(baseDir, "AIClipper")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("AIClipper indirme klasörü oluşturulamadı.")
        }
        return outputDir
    }

    private fun normalizeYoutubeUrl(url: String): String? {
        val value = url.trim()
        if (value.isBlank()) return null

        return try {
            val uri = Uri.parse(
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    value
                } else {
                    "https://$value"
                }
            )

            val host = uri.host?.lowercase() ?: return null
            val videoId = when {
                host == "youtu.be" -> uri.pathSegments.firstOrNull()
                host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                    uri.path.equals("/watch", ignoreCase = true) ->
                        uri.getQueryParameter("v")
                    uri.pathSegments.firstOrNull()?.equals("shorts", true) == true ->
                        uri.pathSegments.getOrNull(1)
                    uri.pathSegments.firstOrNull()?.equals("live", true) == true ->
                        uri.pathSegments.getOrNull(1)
                    else -> null
                }
                else -> null
            }

            if (videoId.isNullOrBlank()) return null
            "https://www.youtube.com/watch?v=" + videoId.trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun simplifyYoutubeError(error: String): String {
        val text = error.replace("\\n", " ").replace("\\r", " ").trim()

        return when {
            text.contains("Sign in", true) ||
                text.contains("confirm you're not a bot", true) ->
                "YouTube erişimi doğrulama istiyor.\n\n" +
                    "yt-dlp: " + text.takeLast(1800)

            text.contains("PO Token", true) ||
                text.contains("po token", true) ->
                "YouTube PO Token doğrulaması istiyor.\n\n" +
                    "yt-dlp: " + text.takeLast(1800)

            text.contains("This video is not available", true) ||
                text.contains("Video unavailable", true) ->
                "YouTube videoyu indirme istemcisine vermedi.\n\n" +
                    "yt-dlp: " + text.takeLast(1800)

            text.contains("403", true) ||
                text.contains("Forbidden", true) ->
                "YouTube HTTP 403 döndürdü.\n\n" +
                    "yt-dlp: " + text.takeLast(1800)

            else ->
                "YouTube videosu indirilemedi.\n\n" +
                    "yt-dlp tanısı:\n" + text.takeLast(1800)
        }
    }
}
