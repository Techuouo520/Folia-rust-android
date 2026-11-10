简体中文 | [English](docs/README.en.md)

<div align="center">

<img src="https://raw.githubusercontent.com/Techuouo520/Folia-rust/main/assets/icon.png" width="128" alt="Folia-rust-android">

# Folia-rust-android

**把墙上的歌词，装进口袋。**

[Folia-rust](https://github.com/Techuouo520/Folia-rust) 的安卓端。页面为手机重新设计，播放、歌词、在线平台与歌词动画沿用 Folia-rust 的 Rust 核心。

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-c2410c)](LICENSE)
[![Rust 1.99](https://img.shields.io/badge/rust-1.99.1-b7410e?logo=rust)](rust-toolchain.toml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7f52ff?logo=kotlin)](gradle/libs.versions.toml)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-3b82f6)](https://developer.android.com/compose)
[![wgpu](https://img.shields.io/badge/GPU-wgpu%20%2F%20WGSL-6d28d9)](https://github.com/gfx-rs/wgpu)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20arm64-3ddc84?logo=android)](app/build.gradle.kts)

</div>

---

> [!NOTE]
> 本地音乐、封面墙、网易云、均衡器和通知栏已接入。歌词动画尚未接入。真机播放需要自己安装 release APK 后验证。

## 简介

Folia-rust 把 Folia 从 Electron 重写成了原生程序。Folia-rust-android 把同一套核心带到手机上：

- **与桌面端共用核心**：播放、歌词、本地资料库、在线平台与 Folia-rust 是同一套 Rust 代码。
- **竖屏优先**：页面用 Jetpack Compose 编写，按单手操作重新排布。
- **GPU 直绘歌词**：歌词动画由 Rust 用 wgpu 画到 `SurfaceView`，着色器与桌面端同为 WGSL。
- **系统原生能力**：媒体会话、通知栏控制、后台播放服务由 Kotlin 侧接入安卓系统。

## 计划特性

| 功能 | 状态 |
|---|:---:|
| Kotlin 调用 Rust 核心 | ✅ |
| 本地音乐扫描与资料库 | ✅ |
| 播放、队列与均衡器 | ✅ |
| 后台播放、通知栏、媒体会话 | ✅ |
| 封面墙首页（歌单 / 电台 / 探索 / 专辑 / 本地） | ✅ |
| 歌词显示与逐字时序 | ✅ |
| 歌词动画样式 | 🚧 |
| 在线平台：网易云 | ✅ |
| 在线平台：酷狗、QQ 音乐、波点、汽水 | 🚧 |
| 多语言：简体中文、繁體中文、English、Bahasa Indonesia、Tiếng Việt | ✅ |

> 安卓端不包含 Spotify。

## 技术栈

| 层 | 选型 |
|---|---|
| 页面 | Kotlin 2.4 + Jetpack Compose |
| 桥接 | uniffi 0.32（业务接口），JNI（Android 上下文，以及之后把 `Surface` 交给 wgpu） |
| 歌词渲染 | wgpu 27 + iced 子 crate，WGSL |
| 核心 | Rust 1.99.1，Folia-rust 的 crate |
| 构建 | Gradle 9.8 + AGP 9.5，cargo-ndk 4.1.2，NDK 29.0.14206865 |
| 系统版本 | minSdk 26（Android 8.0）/ targetSdk 36 / compileSdk 37，arm64-v8a |

## 下载

暂无发布版本，请按下文自行构建。需要 Android 8.0 及以上、arm64 设备。

## 构建

需要 JDK 17、Rust 1.99.1（由 `rust-toolchain.toml` 固定，含 `aarch64-linux-android`）、[cargo-ndk](https://github.com/bbqsrc/cargo-ndk) 4.1.2，以及 Android SDK（platform 37、build-tools 36.0.0、NDK 29.0.14206865）。

```bash
git clone https://github.com/Techuouo520/Folia-rust-android.git
cd Folia-rust-android
```

在仓库根目录新建 `local.properties`（不进 git），写入本机 SDK 路径：

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

然后构建 APK：

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Windows 上用 `gradlew.bat`。debug APK 在 `app/build/outputs/apk/debug/app-debug.apk`，release APK 在 `app/build/outputs/apk/release/app-release.apk`。release 打开 R8，Rust 用 `--release`。要换发布签名时，在 `local.properties` 写上 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`；不写就用 debug 签名，方便本机安装。密钥文件不要提交。

## 路线图

- [x] 工程骨架：Gradle、Compose、Rust 桥接
- [x] 本地音乐扫描与播放，后台播放服务与通知栏
- [x] 封面墙、网易云扫码与在线播放、均衡器、歌词行
- [x] 设置、多语言、明暗主题
- [x] release 构建、R8 与签名
- [ ] 歌词动画（`SurfaceView` + wgpu）
- [ ] 其余在线平台的页面

## 致谢

- **[Folia](https://github.com/chthollyphile/folia-major)**，作者 chthollyphile。界面、动画与交互设计都源自原版。
- **[Folia-rust](https://github.com/Techuouo520/Folia-rust)**，本项目的 Rust 核心。
- 各平台协议的参考实现：[NeteaseCloudMusicApi Enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)、[KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)、[qq-music-api](https://github.com/yakult-green-tea/qq-music-api)、[bodian-music-api](https://github.com/Mintcolour/bodian-music-api)、[qishui-music-api](https://github.com/GSQZ/qishui-music-api)。
- [uniffi](https://github.com/mozilla/uniffi-rs)、[wgpu](https://github.com/gfx-rs/wgpu)、[iced](https://github.com/iced-rs/iced)、[cargo-ndk](https://github.com/bbqsrc/cargo-ndk)、[Jetpack Compose](https://developer.android.com/compose)，以及所有依赖的作者。

## 许可证

[GNU Affero General Public License v3.0](LICENSE)（`AGPL-3.0-only`）。

Folia-rust-android 复用并移植了 [Folia-rust](https://github.com/Techuouo520/Folia-rust)（AGPL-3.0-only）的 crate，Folia-rust 又是 [folia-major](https://github.com/chthollyphile/folia-major)（AGPL-3.0）的衍生作品，因此本项目同样以 AGPL-3.0 发布，原项目的版权归其作者所有。分发 APK（包括发给他人的测试版）时，需要同时让接收者能获得对应的完整源代码。

`crates/cryoglyph/` 是 iced 文字渲染库 [cryoglyph](https://github.com/iced-rs/cryoglyph) 的副本（MIT、Apache-2.0 或 Zlib，授权文件在该目录），说明见其 `PATCH.md`。`assets/nebbia/nebbiaRaindrop.png` 来自 SardineFish 的 raindrop-fx，MIT 许可，见同目录的授权文件。
