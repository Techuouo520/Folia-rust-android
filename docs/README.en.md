[简体中文](../README.md) | English

<div align="center">

<img src="https://raw.githubusercontent.com/Techuouo520/Folia-rust/main/assets/icon.png" width="128" alt="Folia-rust-android">

# Folia-rust-android

**The lyrics on the wall, now in your pocket.**

The Android client of [Folia-rust](https://github.com/Techuouo520/Folia-rust). The screens are redesigned for phones; playback, lyrics, online platforms and lyric animations come from the Folia-rust Rust core.

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-c2410c)](../LICENSE)
[![Rust 1.99](https://img.shields.io/badge/rust-1.99.1-b7410e?logo=rust)](../rust-toolchain.toml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7f52ff?logo=kotlin)](../gradle/libs.versions.toml)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-3b82f6)](https://developer.android.com/compose)
[![wgpu](https://img.shields.io/badge/GPU-wgpu%20%2F%20WGSL-6d28d9)](https://github.com/gfx-rs/wgpu)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20arm64-3ddc84?logo=android)](../app/build.gradle.kts)

</div>

---

> [!NOTE]
> Early stage: only the project skeleton exists so far. Kotlin can already call the Rust core; playback, online platforms and lyric animations are not wired in yet.

## About

Folia-rust rewrote Folia from Electron into a native program. Folia-rust-android brings the same core to phones:

- **Shared core with the desktop**: playback, lyrics, the local library and online platforms are the same Rust code as Folia-rust.
- **Portrait first**: screens are written in Jetpack Compose and laid out for one-handed use.
- **GPU-drawn lyrics**: lyric animations are drawn by Rust with wgpu onto a `SurfaceView`, with the same WGSL shaders as the desktop.
- **Native system features**: the media session, notification controls and the background playback service are wired into Android on the Kotlin side.

## Planned features

| Feature | Status |
|---|:---:|
| Kotlin calls into the Rust core | ✅ |
| Local music scanning and library | 🚧 |
| Playback (AAudio output, equalizer) | 🚧 |
| Background playback, notification, media session | 🚧 |
| Lyrics with per-word timing | 🚧 |
| Lyric animation styles | 🚧 |
| Online platforms: NetEase Cloud Music, KuGou, QQ Music, Bodian, Qishui | 🚧 |
| Languages: 简体中文, 繁體中文, English, Bahasa Indonesia, Tiếng Việt | 🚧 |

> The Android client does not include Spotify.

## Tech stack

| Layer | Choice |
|---|---|
| Screens | Kotlin 2.4 + Jetpack Compose |
| Bridge | uniffi 0.32 (app API), JNI (hands the `Surface` to wgpu) |
| Lyric rendering | wgpu 27 + iced sub-crates, WGSL |
| Core | Rust 1.99.1, Folia-rust crates |
| Build | Gradle 9.8 + AGP 9.5, cargo-ndk 4.1.2, NDK 29.0.14206865 |
| System versions | minSdk 26 (Android 8.0) / targetSdk 36 / compileSdk 37, arm64-v8a |

## Download

No release yet; build it yourself as described below. Requires an arm64 device running Android 8.0 or later.

## Building

You need JDK 17, Rust 1.99.1 (pinned by `rust-toolchain.toml`, including `aarch64-linux-android`), [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) 4.1.2, and the Android SDK (platform 37, build-tools 36.0.0, NDK 29.0.14206865).

```bash
git clone https://github.com/Techuouo520/Folia-rust-android.git
cd Folia-rust-android
```

Create `local.properties` in the repo root (not tracked by git) with your SDK path:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

Then build the debug APK:

```bash
./gradlew assembleDebug
```

On Windows use `gradlew.bat assembleDebug`. The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

## Roadmap

- [x] Project skeleton: Gradle, Compose, Rust bridge
- [ ] Local music scanning and playback, background service and notification
- [ ] Lyrics and lyric animations (`SurfaceView` + wgpu)
- [ ] Online platforms: search, playback, QR login, playlists
- [ ] Settings, languages, themes
- [ ] Release builds and signing

## Acknowledgements

- **[Folia](https://github.com/chthollyphile/folia-major)** by chthollyphile. The interface, animation and interaction design all come from the original.
- **[Folia-rust](https://github.com/Techuouo520/Folia-rust)**, the Rust core of this project.
- Reference implementations of the platform protocols: [NeteaseCloudMusicApi Enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced), [KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi), [qq-music-api](https://github.com/yakult-green-tea/qq-music-api), [bodian-music-api](https://github.com/Mintcolour/bodian-music-api), [qishui-music-api](https://github.com/GSQZ/qishui-music-api).
- [uniffi](https://github.com/mozilla/uniffi-rs), [wgpu](https://github.com/gfx-rs/wgpu), [iced](https://github.com/iced-rs/iced), [cargo-ndk](https://github.com/bbqsrc/cargo-ndk), [Jetpack Compose](https://developer.android.com/compose), and the authors of every dependency.

## License

[GNU Affero General Public License v3.0](../LICENSE) (`AGPL-3.0-only`).

Folia-rust-android reuses and ports crates from [Folia-rust](https://github.com/Techuouo520/Folia-rust) (AGPL-3.0-only), which is itself a derivative of [folia-major](https://github.com/chthollyphile/folia-major) (AGPL-3.0), so this project is released under AGPL-3.0 as well. Copyright of the original project belongs to its authors. Whenever you distribute the APK (test builds included), recipients must be able to obtain the complete corresponding source code.

`crates/cryoglyph/` is a copy of the iced text renderer [cryoglyph](https://github.com/iced-rs/cryoglyph) (MIT, Apache-2.0 or Zlib; license files in that directory), see its `PATCH.md`. `assets/nebbia/nebbiaRaindrop.png` comes from SardineFish's raindrop-fx under the MIT license, see the license file next to it.
