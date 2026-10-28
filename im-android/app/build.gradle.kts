plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.im.nativeapp"
    compileSdk = 36

    defaultConfig {
        // 与壳版 com.im.app 不同，可同机共存对比测试
        applicationId = "com.im.nativeapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 没有 java.time，开启脱糖后可直接使用（消息时间格式化要用）
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-android-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // M1：消息/会话本地缓存（对齐 Web 端 localdb 的「本地优先渲染 + 服务端回写」）
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // 图片加载（头像/聊天图片）；coil 2.x 内置 OkHttp 集成，ImageLoader.Builder.okHttpClient 直接可用
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 语音播放（录音用系统 MediaRecorder）
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    // HLS 模块：直播/回放是 m3u8，缺它 MediaItem 会被当渐进式流解析直接失败
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")

    // 聊天页图标（加号面板/语音/文件等）
    implementation("androidx.compose.material:material-icons-extended")

    // M2：1:1 音视频通话（Google libwebrtc 预编译，提供 org.webrtc 标准类）
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    // M3：图内文字 OCR（ML Kit 端侧中文识别，替代 Web 端 tesseract.js，无 wasm 依赖）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
