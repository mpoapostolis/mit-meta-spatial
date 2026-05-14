# Museum VR (Native Android, Meta Spatial SDK)

Native Quest VR app for the Virtual Museum, built with [Meta Spatial SDK 0.12](https://developers.meta.com/horizon/develop/spatial-sdk).

Loads exhibitions from the same PocketBase backend used by the web viewer (`https://yms.galerra.art`) and renders them in immersive VR with hardware-accelerated 3D and full headset tracking — no WebView, no Capacitor, no Oculus Browser wrapper.

## Requirements

- Android Studio Ladybug (or newer)
- JDK 17+ (bundled with Android Studio)
- Quest 2 / 3 / 3S / Pro in Developer Mode connected via USB-C

## Build & install

```bash
# debug APK
./gradlew assembleDebug

# install on connected Quest
adb install -r app/build/outputs/apk/debug/app-debug.apk

# launch
adb shell am start -n art.galerra.museum.spatial/.ImmersiveActivity
```

## Deeplink to a specific exhibition

```bash
adb shell am start -a android.intent.action.VIEW -d "museum://exhibit/ah1bngq5143ujhw"
```

## Project layout

```
android-native/
├── app/
│   ├── src/main/AndroidManifest.xml     ← Quest VR manifest (supportedDevices, vr.headtracking, com.oculus.intent.category.VR)
│   ├── src/main/java/art/galerra/museum/spatial/
│   │   ├── ImmersiveActivity.kt          ← entry point, loads PB, places objects in 3D
│   │   └── PocketBaseClient.kt           ← OkHttp + kotlinx-serialization client (mirrors web viewer's pb.ts)
│   └── build.gradle.kts                  ← Spatial SDK dependencies (0.12.0)
├── build.gradle.kts                      ← plugin versions (AGP, Kotlin, Spatial plugin, KSP)
├── settings.gradle.kts
├── gradle.properties
└── gradlew
```

## Why a separate APK (vs the Capacitor WebView one)

The Android system WebView does not expose `navigator.xr`; only Meta's Oculus Browser does. A Capacitor wrapper therefore cannot run WebXR in immersive mode. This project bypasses the browser entirely and renders the scene with native OpenXR via Meta Spatial SDK.
