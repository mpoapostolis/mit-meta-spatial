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
        // HorizonOS is Android 14 (API level 34). Meta Spatial SDK 0.12 requires API 34
        // for panel/compose runtime surface composition — lower minSdk causes panels to
        // spawn entities but produce no painted surface, which is exactly the symptom
        // we hit on Quest 2 (black panels). Matches StarterSample/build.gradle.kts.
        minSdk = 34
        targetSdk = 34
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
        // Only strip the LICENSE file — `META-INF/*` glob was eating Spatial SDK
        // service-loader manifests / native library descriptors, which prevented
        // ComposeFeature's panel renderer from initialising at runtime.
        resources.excludes.add("META-INF/LICENSE")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Match StarterSample (Kotlin 2.0.20 / Compose compiler plugin pin).
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
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
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // uiset is what StarterSample uses for SpatialTheme + color tokens. Required by the
    // compose panel renderer for default text styles in 0.12.
    implementation("com.meta.spatial:meta-spatial-sdk-uiset:$metaSpatialSdkVersion")

    // ExoPlayer for video assets rendered onto quad SceneTextures
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
}
