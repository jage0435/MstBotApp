plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mstbot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mstbot"
        minSdk = 30          // Android 11 以上
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
        // ML Kit 內建模型每個 ABI 約 4 MB：手機用 arm64，x86_64 給模擬器測試
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 拉丁文字辨識（找關卡結束的「OK」），模型打包在 App 內，離線可用
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
