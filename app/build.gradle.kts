plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.local.mangatranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.local.mangatranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1-validated"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Prevents duplicate native library collision between ONNX Runtime and MediaPipe
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libomp.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // 1. Bundled Japanese OCR V2 (Supports Vertical & Horizontal Japanese Scripts Offline)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // 2. Heavy Local LLM Engine (MediaPipe GenAI)
    implementation("com.google.mediapipe:tasks-genai:0.10.32")

    // 3. ONNX Runtime (For optional custom MangaOCR .onnx models)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")

    // 4. Offline Neural Machine Translation Fallback
    implementation("com.google.mlkit:translate:17.0.3")
}
