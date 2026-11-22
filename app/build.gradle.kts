import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val sherpaOnnxAar = layout.buildDirectory.file("downloads/sherpa-onnx-2.0.1.aar")
val downloadSherpaOnnxAar = tasks.register("downloadSherpaOnnxAar") {
    outputs.file(sherpaOnnxAar)
    doLast {
        val destination = sherpaOnnxAar.get().asFile
        destination.parentFile.mkdirs()
        URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar")
            .openStream()
            .use { input -> destination.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
    }
}

val stageLibcxxShared = tasks.register("stageLibcxxShared") {
    val abis = listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
    val sdkRoot = System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: throw GradleException("ANDROID_SDK_ROOT/ANDROID_HOME bulunamadı.")
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
        ?: file("$sdkRoot/ndk").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?.absolutePath
        ?: throw GradleException("Android NDK bulunamadı.")

    doLast {
        val hostTag = if (System.getProperty("os.name").lowercase().contains("windows")) {
            "windows-x86_64"
        } else {
            "linux-x86_64"
        }

        abis.forEach { abi ->
            val source = file(
                "$ndkHome/toolchains/llvm/prebuilt/$hostTag/sysroot/usr/lib/$abi/libc++_shared.so"
            )
            val destination = file("src/main/jniLibs/$abi/libc++_shared.so")
            if (!source.isFile) {
                throw GradleException("NDK libc++_shared.so bulunamadı: ${source.absolutePath}")
            }
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadSherpaOnnxAar)
}

android {
    namespace = "com.bytasq.aiclipper"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.bytasq.aiclipper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    chaquopy {
        defaultConfig {
            buildPython("python3.11")
            version = "3.11"
            pip {
                install("src/main/assets/ytdlp/yt_dlp-2026.10.26-py3-none-any.whl")
                install("youtube-transcript-api==1.2.3")
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files(sherpaOnnxAar))
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
