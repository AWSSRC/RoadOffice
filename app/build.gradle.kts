import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// release 서명 정보는 저장소 밖(keystore.properties, git 제외)에서 읽는다 — 키·비밀번호 하드코딩 금지
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.roadoffice.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.roadoffice.app"
        minSdk = 28 // Pleos 요구사항: 사이닝 키 로테이션 지원
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Pleos 헤드유닛(arm64) + x86_64 에뮬레이터만 — SQLCipher 네이티브 라이브러리 용량 절감
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Pleos: 사이닝 키 로테이션·리니지 지원(v3) — minSdk 28 이상
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    useLibrary("android.car")
}

dependencies {
    // 보안: Pleos LLM SDK → retrofit 2.9.0 이 끌어오는 옛 버전의 알려진 취약점 대체 (OSV 점검 2026-11-02)
    //  okhttp 3.14.9: CVE-2021-0341 (잘못된 인증서 허용, 4.9.2 에서 수정) / okio 1.17.2: CVE-2023-3635 (1.17.6·3.4.0 에서 수정)
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0") { because("CVE-2021-0341") }
        implementation("com.squareup.okio:okio:3.9.1") { because("CVE-2023-3635") }
    }
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:LLM:2.1.3.2")
    // 차 안 소형 언어모델 실행(LiteRT/MediaPipe) — Pleos LLM 을 못 쓰는 차·에뮬레이터에서 상담 정리. 모델 파일은 APK 에 넣지 않는다
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // 저장소: Room + SQLCipher(전체 DB 암호화, 키는 Android Keystore 로 보호)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // JVM 테스트에서 실제 org.json 사용
}
