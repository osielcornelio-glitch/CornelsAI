plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.cornels.ai"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.cornels.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("cornels.jks")
            storePassword = "cornels123"
            keyAlias = "cornels"
            keyPassword = "cornels123"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
