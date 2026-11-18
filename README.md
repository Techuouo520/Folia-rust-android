简体中文 | [English](docs/README.en.md)

<div align="center">

<img src="https://raw.githubusercontent.com/Techuouo520/Folia-rust/main/assets/icon.png" width="128" alt="Folia-rust-android">

# Folia-rust-android

**把墙上的歌词，装进口袋。**

[Folia-rust](https://github.com/Techuouo520/Folia-rust) 的安卓端。页面为手机重新设计，歌词、资料库、设置与在线平台复用 Rust 核心，播放和渲染通过本地维护的安卓适配接入。

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-c2410c)](LICENSE)
[![Rust 1.99](https://img.shields.io/badge/rust-1.99.1-b7410e?logo=rust)](rust-toolchain.toml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7f52ff?logo=kotlin)](gradle/libs.versions.toml)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-3b82f6)](https://developer.android.com/compose)
[![wgpu](https://img.shields.io/badge/GPU-wgpu%20%2F%20WGSL-6d28d9)](https://github.com/gfx-rs/wgpu)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20arm64-3ddc84?logo=android)](app/build.gradle.kts)

</div>

---

> [!NOTE]
> 已接入本地音乐、封面墙、在线音乐、音效、通知栏和莫奈歌词效果页。莫奈页面将 Rust/wgpu 海报与安卓原生逐字歌词控件叠放显示。

## 简介

Folia-rust 把 Folia 从 Electron 重写成了原生程序。Folia-rust-android 把同一套核心带到手机上：

- **与桌面端共用核心**：core、i18n、lyrics、store、library、omni 通过 Git 依赖跟随 Folia-rust 的 main，具体提交由 Cargo.lock 记录。player、render、visualizer 在本仓库维护安卓适配。
- **竖屏优先**：页面用 Jetpack Compose 编写，按单手操作重新排布。
- **组合歌词渲染**：Rust/wgpu 在 `TextureView` 绘制莫奈海报、封面与频谱，安卓 Canvas/Skia 控件叠加逐字歌词和滚动动画。
- **系统原生能力**：媒体会话、通知栏控制、后台播放服务由 Kotlin 侧接入安卓系统。

## 功能进度

| 功能 | 状态 |
|---|:---:|
| Kotlin 调用 Rust 核心 | ✅ |
| 本地音乐扫描与资料库 | ✅ |
| 播放、队列与音效 | ✅ |
| 后台播放、通知栏、媒体会话 | ✅ |
| 封面墙首页（歌单 / 电台 / 探索 / 专辑 / 本地） | ✅ |
| 歌词显示与逐字时序 | ✅ |
| 莫奈歌词页（GPU 海报 / 原生逐字文字 / 弹簧滚动） | ✅ |
| 其余歌词样式的安卓页面入口 | 🚧 |
| 在线平台：网易云 | ✅ |
| 在线平台：酷狗、QQ 音乐、波点、汽水 | 🚧 |
| 多语言：简体中文、繁體中文、English、Bahasa Indonesia、Tiếng Việt | ✅ |

> 安卓端不包含 Spotify。

## 技术栈

| 层 | 选型 |
|---|---|
| 页面 | Kotlin 2.4 + Jetpack Compose |
| 桥接 | UniFFI 0.32.2（业务与歌词数据），JNI（上下文、Surface、逐帧渲染、输入和动画内核） |
| 歌词渲染 | TextureView + wgpu 27 / iced 子 crate / WGSL，原生 Canvas/Skia 文字 |
| 核心 | Rust 1.99.1，Folia-rust 的 crate |
| 构建 | Gradle 9.8.2 + AGP 9.5.1，cargo-ndk 4.1.2，NDK 29.0.14206865 |
| 系统版本 | minSdk 26（Android 8.0）/ targetSdk 36 / compileSdk 37，arm64-v8a |

## 歌词效果页如何工作

当前接通莫奈样式。Rust/wgpu 绘制海报、封面与频谱，源自 SalamPlayer 的原生控件负责逐字歌词与弹簧滚动。两层跟随同一播放进度，支持点击歌词跳转和封面拖动。

## 下载

暂无发布版本，请按下文自行构建。需要 Android 8.0 及以上、arm64 设备。

## 构建

准备 JDK 17、Rust 1.99.1（含 Android arm64 目标）、cargo-ndk 4.1.2，以及 Android SDK 37、build-tools 36.0.0、NDK 29.0.14206865。

参考[配置模板](local.properties.example)，在 `local.properties` 填写 SDK 路径和签名信息。Android Studio 的 Gradle JDK 选 17。

在仓库根目录编译 release APK。

```powershell
.\gradlew.bat assembleRelease
```

macOS/Linux 使用 `./gradlew assembleRelease`。调试构建将任务改为 `assembleDebug`。

## 路线图

- [x] 工程骨架：Gradle、Compose、Rust 桥接
- [x] 本地音乐扫描与播放，后台播放服务与通知栏
- [x] 封面墙、网易云扫码与在线播放、音效、歌词行
- [x] 设置、多语言、明暗主题
- [x] release 构建、R8 与签名
- [x] 莫奈歌词效果页（TextureView / wgpu 海报与原生逐字文字）
- [ ] 其余歌词样式的安卓页面入口
- [ ] 其余在线平台的页面

## 致谢

- **[Folia](https://github.com/chthollyphile/folia-major)**，作者 chthollyphile。界面、动画与交互设计都源自原版。
- **[Folia-rust](https://github.com/Techuouo520/Folia-rust)**，本项目的 Rust 核心。
- 各平台协议的参考实现：[NeteaseCloudMusicApi Enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)、[KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)、[qq-music-api](https://github.com/yakult-green-tea/qq-music-api)、[bodian-music-api](https://github.com/Mintcolour/bodian-music-api)、[qishui-music-api](https://github.com/GSQZ/qishui-music-api)。
- [uniffi](https://github.com/mozilla/uniffi-rs)、[wgpu](https://github.com/gfx-rs/wgpu)、[iced](https://github.com/iced-rs/iced)、[cargo-ndk](https://github.com/bbqsrc/cargo-ndk)、[Jetpack Compose](https://developer.android.com/compose)，以及所有依赖的作者。

## 许可证

[GNU Affero General Public License v3.0](LICENSE)（`AGPL-3.0-only`）。

平台图标复用自 Folia-rust，属于各平台的商标，仅用于标识对应平台，不在 AGPL-3.0 授权范围内。

Folia-rust-android 复用并移植了 [Folia-rust](https://github.com/Techuouo520/Folia-rust)（AGPL-3.0-only）的 crate，Folia-rust 又是 [folia-major](https://github.com/chthollyphile/folia-major)（AGPL-3.0）的衍生作品，因此本项目同样以 AGPL-3.0 发布，原项目的版权归其作者所有。分发 APK（包括发给他人的测试版）时，需要同时让接收者能获得对应的完整源代码。

`crates/cryoglyph/` 是 iced 文字渲染库 [cryoglyph](https://github.com/iced-rs/cryoglyph) 的副本（MIT、Apache-2.0 或 Zlib，授权文件在该目录），说明见其 `PATCH.md`。`assets/nebbia/nebbiaRaindrop.png` 来自 SardineFish 的 raindrop-fx，MIT 许可，见同目录的授权文件。
