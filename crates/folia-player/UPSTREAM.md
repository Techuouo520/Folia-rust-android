# Android output adaptation
Decoder, source, resampler, analyser and shared transport state originate from Folia-rust revision 45130275 (the prior locked dependency). This local crate replaces the old equalizer/output path so that Android owns an effect-compatible shared AAudio session.

Progressive AudioInput/DownloadReader decoding, per-read cancellation, seek reopening and buffering detection are ported from Folia-rust 39e33e8e89980289edcb01ad1fa9dde2b676ba9f. Android retains its command epochs, paused-load API, render clock and shared AAudio/effects output. Both output backends only publish a starvation flag; network waits remain on the decoder thread.

native/virtual_bass.cpp is the VirtualBassState/helper block from the user's SalamPlayer app/src/main/cpp/hxcore_dsp_native.cpp (2026-11-15), unchanged internally. Rust bass.rs adapts VirtualBassDsp.Design; Kotlin audio/HeadroomEffect.kt adapts the session compensation wrapper. See ../../docs/corners-bass-20261115/HANDOFF.md for the exact pipeline, constraints and validation. No Java source files are carried into this crate/app.
