plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("com.meta.spatial.plugin")
}

android {
    namespace = "art.galerra.museum.spatial"
    compileSdk = 34

    defaultConfig {
        applicationId = "art.galerra.museum.spatial"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    packaging {
        resources.excludes += "META-INF/*"
    }

    buildFeatures {
        compose = true
    }
}

val metaSpatialSdkVersion = "0.12.0"

dependencies {
    // Meta Spatial SDK core
    implementation("com.meta.spatial:meta-spatial-sdk:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-physics:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-compose:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-spatialaudio:$metaSpatialSdkVersion")

    // Kotlin coroutines for async asset loading
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // PocketBase HTTP client (OkHttp + kotlinx-serialization)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Compose for 2D panels inside VR scene (Spatial SDK ships with Compose runtime)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ExoPlayer for video assets rendered onto quad SceneTextures
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
}
