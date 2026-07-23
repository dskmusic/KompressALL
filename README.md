<div align="center">

# 📦 KompressALL

**One app to shrink your photos and videos — batch, on‑device, no cloud, no nonsense.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Media3 Transformer](https://img.shields.io/badge/Media3%20Transformer-1.5.0-00C853)](https://developer.android.com/guide/topics/media/transformer)
[![Min SDK](https://img.shields.io/badge/minSdk-30-brightgreen)](#requirements)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#requirements)

</div>

---

## What is this?

KompressALL merges two smaller sibling apps — an image compressor and a video compressor — into a single, focused tool: grab everything from a day out (photos **and** videos, mixed), pick a preset, and get one folder back with everything optimized.

Everything runs **fully on-device**. Nothing is uploaded anywhere — the only network request the app ever makes is opening a link from the credits footer.

## ✨ Features

- **Batch, mixed media** — select photos and videos together (system Photo Picker), or send them in via **Share** / **Open with** from your camera or gallery app, in as many rounds as you like.
- **Smart presets** — High / Medium / Max saving for both photos and videos, or drop into full manual mode (format, quality, resolution, codec, target size, audio bitrate…).
- **Hardware-accelerated video** — built on **Media3 Transformer**, auto-detects the best available encoder (H.264 / H.265 / AV1) with automatic fallback if the device can't handle a config, plus an optional two-pass mode for hitting a target size precisely.
- **EXIF & date preserving image engine** — custom `Bitmap`-based compressor that keeps orientation, GPS, camera metadata and the original capture date intact.
- **Flexible output** — save into a new dated folder (`KompressALL/20260723 Beach day/`, videos tucked into `Videos/`) or replace the originals in place, with an optional automatic backup before overwriting.
- **Keeps running in the background** — a foreground service with a persistent, cancelable notification means you can minimize the app mid-batch and keep using your phone.
- **Before/after summary** — total original vs. final size, percentage saved, per-file breakdown, and a one-tap **Share** of every successfully compressed file straight to the system share sheet.
- **Make it yours** — dark/light/system theme, 12 accent colors, 5 system font families, automatic ES/EN language detection with manual override, and full settings **export/import** as JSON.
- **App shortcut** — long-press the launcher icon to jump straight to the picker, no home screen in between.

## 🛠️ Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Video engine | AndroidX Media3 Transformer / ExoPlayer / Effect |
| Image engine | Custom `BitmapFactory` + `ExifInterface` pipeline (no third-party compression library) |
| Background work | Foreground `Service` + Kotlin Coroutines / `StateFlow` |
| Build | Gradle Kotlin DSL with a version catalog (`libs.versions.toml`) |
| Persistence | `SharedPreferences` (settings), JSON export/import |

## 📋 Requirements

- Android Studio (recent stable), AGP 8.7.x
- JDK 17
- Android **10 (API 30)** or newer on-device
- "All files access" permission — needed to write the `KompressALL/` folder at the storage root and to replace/delete originals in place

## 🚀 Getting started

```bash
git clone <this-repo-url>
```

1. Open the project folder in Android Studio.
2. Let Gradle sync (it will fetch the dependencies listed in `gradle/libs.versions.toml`).
3. Run on a device or emulator with API 30+.

No API keys, no backend, no config files to fill in — it just builds.

## 📁 Output structure

```
/storage/emulated/0/KompressALL/
├── Backups/
│   └── 20260723 Beach day/        ← originals, only if backup is enabled
└── 20260723 Beach day/
    ├── IMG_20260723_114500.jpg
    ├── IMG_20260723_114532.jpg
    └── Videos/
        └── VID_20260723_115010.mp4
```

The folder name always starts with the batch's detected date (`YYYYMMDD`), editable before you hit compress.

## 🌍 Localization

English (default) and Spanish, with automatic detection based on the system language and a manual override in Settings.

## 🙏 Credits

Built by **DSK** — [dskmusic.com](https://www.dskmusic.com/dsk_dev_redirect.php)

This project has no license file, so all rights are reserved by the author by default.
