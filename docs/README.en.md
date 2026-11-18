[简体中文](../README.md) | English

<div align="center">

<img src="https://raw.githubusercontent.com/Techuouo520/Folia-rust/main/assets/icon.png" width="128" alt="Folia-rust-android">

# Folia-rust-android

**The lyrics on the wall, now in your pocket.**

The Android client of [Folia-rust](https://github.com/Techuouo520/Folia-rust). The screens are redesigned for phones. Lyrics, the library, settings and online providers reuse the Rust core, with playback and rendering integrated through locally maintained Android adaptations.

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-c2410c)](../LICENSE)
[![Rust 1.99](https://img.shields.io/badge/rust-1.99.1-b7410e?logo=rust)](../rust-toolchain.toml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7f52ff?logo=kotlin)](../gradle/libs.versions.toml)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-3b82f6)](https://developer.android.com/compose)
[![wgpu](https://img.shields.io/badge/GPU-wgpu%20%2F%20WGSL-6d28d9)](https://github.com/gfx-rs/wgpu)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20arm64-3ddc84?logo=android)](../app/build.gradle.kts)

</div>

---

> [!NOTE]
> Local music, the cover wall, online music, audio effects, notifications and the Monet lyrics page are integrated. Monet combines a Rust/wgpu poster with an Android native animated lyric view.

## About

Folia-rust rewrote Folia from Electron into a native program. Folia-rust-android brings the same core to phones:

- **Shared core with the desktop**: core, i18n, lyrics, store, library and omni follow Folia-rust main through Git dependencies, with the commit recorded in Cargo.lock. Android adaptations of player, render and visualizer are maintained in this repository.
- **Portrait first**: screens are written in Jetpack Compose and laid out for one-handed use.
- **Combined lyric rendering**: Rust/wgpu draws the Monet poster, cover and spectrum in a `TextureView`. An Android Canvas/Skia view overlays animated words and scrolling lyrics.
- **Native system features**: the media session, notification controls and the background playback service are wired into Android on the Kotlin side.

## Feature progress

| Feature | Status |
|---|:---:|
| Kotlin calls into the Rust core | ✅ |
| Local music scanning and library | ✅ |
| Playback, queue and audio effects | ✅ |
| Background playback, notification, media session | ✅ |
| Cover-wall home (playlists / radio / explore / albums / local) | ✅ |
| Lyrics with per-word timing | ✅ |
| Monet lyrics page (GPU poster / native animated text / spring scrolling) | ✅ |
| Android page entry points for other lyric styles | 🚧 |
| Online platform: NetEase Cloud Music | ✅ |
| Online platforms: KuGou, QQ Music, Bodian, Qishui | 🚧 |
| Languages: 简体中文, 繁體中文, English, Bahasa Indonesia, Tiếng Việt | ✅ |

> The Android client does not include Spotify.

## Tech stack

| Layer | Choice |
|---|---|
| Screens | Kotlin 2.4 + Jetpack Compose |
| Bridge | UniFFI 0.32.2 (app and lyric data), JNI (context, Surface, frames, input and animation kernels) |
| Lyric rendering | TextureView + wgpu 27 / iced sub-crates / WGSL, native Canvas/Skia text |
| Core | Rust 1.99.1, Folia-rust crates |
| Build | Gradle 9.8.2 + AGP 9.5.1, cargo-ndk 4.1.2, NDK 29.0.14206865 |
| System versions | minSdk 26 (Android 8.0) / targetSdk 36 / compileSdk 37, arm64-v8a |

## How the lyrics page works

The page currently uses Monet. Rust/wgpu draws the poster, cover and spectrum, while native views adapted from SalamPlayer handle animated words and spring scrolling. Both layers follow playback, with tap-to-seek lyrics and a draggable cover.

## Download

No release yet; build it yourself as described below. Requires an arm64 device running Android 8.0 or later.

## Building

You need JDK 17, Rust 1.99.1 with the Android arm64 target, cargo-ndk 4.1.2, Android SDK 37, build-tools 36.0.0 and NDK 29.0.14206865.

Use the [configuration template](../local.properties.example) to fill in SDK and signing settings in `local.properties`. Select JDK 17 as Android Studio's Gradle JDK.

Build the release APK from the repository root.

```powershell
.\gradlew.bat assembleRelease
```

On macOS/Linux, use `./gradlew assembleRelease`. For a debug build, change the task to `assembleDebug`.

## Roadmap

- [x] Project skeleton: Gradle, Compose, Rust bridge
- [x] Local music scanning and playback, background service and notification
- [x] Cover wall, NetEase QR login and online playback, audio effects, lyric lines
- [x] Settings, languages, light and dark theme
- [x] Release build, R8 and signing
- [x] Monet lyrics page (TextureView / wgpu poster and native animated text)
- [ ] Android page entry points for other lyric styles
- [ ] Screens for the other online platforms

## Acknowledgements

- **[Folia](https://github.com/chthollyphile/folia-major)** by chthollyphile. The interface, animation and interaction design all come from the original.
- **[Folia-rust](https://github.com/Techuouo520/Folia-rust)**, the Rust core of this project.
- Reference implementations of the platform protocols: [NeteaseCloudMusicApi Enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced), [KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi), [qq-music-api](https://github.com/yakult-green-tea/qq-music-api), [bodian-music-api](https://github.com/Mintcolour/bodian-music-api), [qishui-music-api](https://github.com/GSQZ/qishui-music-api).
- [uniffi](https://github.com/mozilla/uniffi-rs), [wgpu](https://github.com/gfx-rs/wgpu), [iced](https://github.com/iced-rs/iced), [cargo-ndk](https://github.com/bbqsrc/cargo-ndk), [Jetpack Compose](https://developer.android.com/compose), and the authors of every dependency.

## License

[GNU Affero General Public License v3.0](../LICENSE) (`AGPL-3.0-only`).

Platform icons are reused from Folia-rust and are trademarks of their respective owners. They identify the corresponding services and are not covered by AGPL-3.0.

Folia-rust-android reuses and ports crates from [Folia-rust](https://github.com/Techuouo520/Folia-rust) (AGPL-3.0-only), which is itself a derivative of [folia-major](https://github.com/chthollyphile/folia-major) (AGPL-3.0), so this project is released under AGPL-3.0 as well. Copyright of the original project belongs to its authors. Whenever you distribute the APK (test builds included), recipients must be able to obtain the complete corresponding source code.

`crates/cryoglyph/` is a copy of the iced text renderer [cryoglyph](https://github.com/iced-rs/cryoglyph) (MIT, Apache-2.0 or Zlib; license files in that directory), see its `PATCH.md`. `assets/nebbia/nebbiaRaindrop.png` comes from SardineFish's raindrop-fx under the MIT license, see the license file next to it.
