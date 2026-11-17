package com.bytasq.aiclipper

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class YouTubeUploadMetadata(
    val title: String,
    val description: String,
    val hashtags: List<String>,
    val privacyStatus: String = "private"
)

data class YouTubeAccount(
    val email: String
)

class YouTubePublisher(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient()
) {
    companion object {
        const val UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload"
        private const val UPLOAD_URL =
            "https://www.googleapis.com/upload/youtube/v3/videos?part=snippet,status"
    }

    fun signInIntent(): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(UPLOAD_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    fun accountFromResult(data: Intent?): YouTubeAccount {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(ApiException::class.java)
        return YouTubeAccount(account.email.orEmpty())
    }

    fun lastSignedInAccount(): YouTubeAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return YouTubeAccount(account.email.orEmpty())
    }

    suspend fun uploadVideo(
        file: File,
        metadata: YouTubeUploadMetadata
    ): String = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("YouTube hesabı bağlı değil.")
        uploadWithFreshToken(account, file, metadata)
    }

    private fun uploadWithFreshToken(
        account: GoogleSignInAccount,
        file: File,
        metadata: YouTubeUploadMetadata
    ): String {
        val googleAccount = account.account
            ?: throw IllegalStateException("Google hesabı için Account bilgisi alınamadı.")
        val token = GoogleAuthUtil.getToken(
            context,
            googleAccount,
            "oauth2:" + UPLOAD_SCOPE
        )

        try {
            return performResumableUpload(token, file, metadata)
        } catch (e: IOException) {
            if (!e.message.orEmpty().contains("HTTP 401")) throw e
            GoogleAuthUtil.clearToken(context, token)
            val refreshedToken = GoogleAuthUtil.getToken(
                context,
                googleAccount,
                "oauth2:" + UPLOAD_SCOPE
            )
            return performResumableUpload(refreshedToken, file, metadata)
        }
    }

    private fun performResumableUpload(
        accessToken: String,
        file: File,
        metadata: YouTubeUploadMetadata
    ): String {
        if (!file.exists() || file.length() == 0L) {
            throw IllegalArgumentException("Yüklenecek MP4 bulunamadı.")
        }

        val snippet = JSONObject().apply {
            put("title", metadata.title.take(100))
            put(
                "description",
                buildString {
                    append(metadata.description.take(4500))
                    if (metadata.hashtags.isNotEmpty()) {
                        append("\n\n")
                        append(metadata.hashtags.joinToString(" "))
                    }
                }.take(5000)
            )
            put("categoryId", "22")
        }

        val status = JSONObject().apply {
            put("privacyStatus", metadata.privacyStatus)
            put("selfDeclaredMadeForKids", false)
        }

        val body = JSONObject().apply {
            put("snippet", snippet)
            put("status", status)
        }.toString()

        val sessionRequest = Request.Builder()
            .url(UPLOAD_URL)
            .header("Authorization", "Bearer " + accessToken)
            .header("X-Upload-Content-Type", "video/mp4")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
            .build()

        client.newCall(sessionRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "YouTube yükleme oturumu başlatılamadı. HTTP " +
                            response.code + ": " + response.body?.string()
                )
            }

            val uploadUrl = response.header("Location")
                ?: throw IOException("YouTube yükleme adresi alınamadı.")

            val uploadRequest = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer " + accessToken)
                .put(file.asRequestBody("video/mp4".toMediaType()))
                .build()

            client.newCall(uploadRequest).execute().use { uploadResponse ->
                val responseBody = uploadResponse.body?.string().orEmpty()
                if (!uploadResponse.isSuccessful) {
                    throw IOException(
                        "YouTube video yüklenemedi. HTTP " +
                                uploadResponse.code + ": " + responseBody
                    )
                }

                val videoId = JSONObject(responseBody).optString("id")
                if (videoId.isBlank()) {
                    throw IOException("YouTube video ID alınamadı.")
                }
                return videoId
            }
        }
    }
}