/*
 * IPTV Mobile — a standalone, touch-first Android phone app.
 *
 * This module shares NO code, UI or dependencies with the TV app module (:app):
 * it is built from scratch with plain Jetpack Compose Material 3 (not TV
 * material), a phone launcher entry, portrait-friendly layouts and a simple
 * state model with no dependency-injection or persistence frameworks.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.indian.iptv.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.indian.iptv.mobile"
        // Android 9 (API 28) floor, per project requirement.
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
    composeOptions {
        // Compose compiler matched to the project's Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Plain phone Compose (Material 3) via the BOM — intentionally NOT
    // androidx.tv.material, so every control is touch-first.
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Media3 player with HLS support (most IPTV streams are HLS).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
}
