// Audio playback: a decoder thread feeds a lock-free ring buffer that the device callback drains.

mod analyser;
mod convert;
mod decoder;
pub mod bass;
#[cfg(not(target_os="android"))]
mod output;
#[cfg(target_os="android")]
#[path="android_output.rs"]
mod output;
mod shared;
mod source;

pub use analyser::{Analyser, TAP_CAPACITY, push_samples};
pub use convert::map_channels;
pub use folia_core::AudioFrame;
pub use shared::PlayerState;
pub use source::Source as DecodedSource;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::mpsc::{self, Sender};

#[cfg(not(target_os="android"))]
use cpal::traits::StreamTrait;

use crate::decoder::{Command, Decoder};
use crate::shared::Shared;

#[derive(Debug, thiserror::Error)]
pub enum PlayerError {
    #[error("no audio output device")]
    NoDevice,
    #[error("Android audio output failed: {0}")]
    Output(String),
    #[error("audio output device error")]
    Device(#[source] cpal::Error),
    #[error("unsupported output sample format {0}")]
    UnsupportedFormat(String),
    #[error("cannot open {path}")]
    Open {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },
    #[error("file has no playable audio track")]
    NoAudioTrack,
    #[error("cannot decode audio")]
    Decode(#[from] symphonia::core::errors::Error),
    #[error("cannot resample audio: {0}")]
    Resample(String),
    #[error("cannot start the decoder thread")]
    Thread(#[source] std::io::Error),
}

/// What the UI needs to draw the transport: state, position and length in seconds.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Status {
    pub state: PlayerState,
    pub position: f64,
    pub duration: f64,
}

/// Audio callback clock for renderers that must not wait on the command queue.
#[derive(Clone)]
pub struct PlaybackClock { shared:Arc<Shared>, rate:u32, analyser: Arc<std::sync::Mutex<Analyser>> }
impl PlaybackClock {
    pub fn position(&self) -> f64 { timeline(&self.shared,self.rate).position }
    pub fn belongs_to(&self, player: &Player) -> bool { Arc::ptr_eq(&self.shared, &player.shared) }
    pub fn spectrum(&self, wall_ms: f64) -> AudioFrame {
        self.analyser.lock().map_or(AudioFrame::SILENT, |mut tap| {
            *tap.frame(self.shared.state() == PlayerState::Playing, wall_ms)
        })
    }
}

pub struct Player {
    commands: Sender<Command>,
    shared: Arc<Shared>,
    stream: output::Stream,
    rate: u32,
    analyser: Arc<std::sync::Mutex<Analyser>>,
    input: std::sync::Mutex<InputControl>,
}

#[derive(Default)]
struct InputControl {
    audio: Option<folia_core::AudioInput>,
    cancelled: Arc<std::sync::atomic::AtomicBool>,
    waiting: Arc<std::sync::atomic::AtomicBool>,
    preparing: Arc<std::sync::atomic::AtomicBool>,
}

impl Drop for Player {
    fn drop(&mut self) {
        self.cancel_input();
    }
}

impl Player {
    pub fn new() -> Result<Self, PlayerError> {
        let shared = Arc::new(Shared::new());
        let (tap, analyser) = Analyser::pair();
        let (output, producer) = output::open(shared.clone(), tap)?;
        let (commands, receiver) = mpsc::channel();
        let decoder = Decoder::new(shared.clone(), producer, output.channels, output.rate, receiver);
        std::thread::Builder::new()
            .name("folia-decoder".into())
            .spawn(move || decoder.run())
            .map_err(PlayerError::Thread)?;
        tracing::info!(rate = output.rate, channels = output.channels, "audio output opened");
        Ok(Self { commands, shared, stream: output.stream, rate: output.rate, analyser: Arc::new(std::sync::Mutex::new(analyser)), input: Default::default() })
    }

    /// The latest spectrum. `playing` selects analysis; otherwise the frame is the idle breath.
    pub fn audio_frame(&mut self, playing: bool, wall_ms: f64) -> AudioFrame {
        self.analyser.lock().map_or(AudioFrame::SILENT, |mut tap| *tap.frame(playing, wall_ms))
    }

    /// Starts playing `path` from the beginning.
    pub fn load(&self, path: &Path) { self.load_at(path,0.0,true); }

    pub fn load_at(&self, path: &Path, position: f64, playing: bool) {
        self.load_input_at(path.to_owned().into(), position, playing);
    }

    pub fn load_input(&self, input: folia_core::AudioInput) {
        self.load_input_at(input, 0.0, true);
    }

    pub fn load_input_at(&self, input: folia_core::AudioInput, position: f64, playing: bool) {
        let position = position.max(0.0);
        let Some(load) = self.prepare_input(Some(input), position) else { return };
        // Mark the new track as playing now, so a status read before the decoder starts never sees the old end.
        self.shared.decoder_done.store(false, std::sync::atomic::Ordering::Release);
        self.shared.duration.store(0.0);
        self.shared.request_flush(position);
        self.shared.set_state(if playing {PlayerState::Playing} else {PlayerState::Paused});
        self.run_stream(true);
        self.send(Command::Load(load));
    }

    pub fn pause(&self) {
        if self.shared.state() == PlayerState::Playing {
            self.shared.set_state(PlayerState::Paused);
        }
    }

    pub fn resume(&self) {
        if self.shared.state() == PlayerState::Paused {
            self.shared.set_state(PlayerState::Playing);
        }
    }

    pub fn seek(&self, seconds: f64) {
        let seconds = seconds.max(0.0);
        let Some(load) = self.prepare_input(None, seconds) else { return };
        self.shared.request_flush(seconds);
        self.shared.decoder_done.store(false, std::sync::atomic::Ordering::Release);
        self.run_stream(true);
        self.send(Command::Load(load));
    }

    pub fn stop(&self) {
        let epoch = self.cancel_input();
        self.shared.set_state(PlayerState::Idle);
        self.send(Command::Stop(epoch));
        self.run_stream(false);
    }

    // Waiting while decoded audio is still queued is read-ahead, not an audible stall.
    pub fn is_buffering(&self) -> bool {
        let input = self.input.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        input.preparing.load(std::sync::atomic::Ordering::Acquire)
            || (input.waiting.load(std::sync::atomic::Ordering::Acquire)
                && self.shared.starved.load(std::sync::atomic::Ordering::Acquire)
                && self.shared.state() == PlayerState::Playing)
    }

    pub fn is_preparing(&self) -> bool {
        self.input.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
            .preparing.load(std::sync::atomic::Ordering::Acquire)
    }

    fn prepare_input(&self, audio: Option<folia_core::AudioInput>, position: f64) -> Option<decoder::Load> {
        let mut input = self.input.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(audio) = audio {
            input.audio = Some(audio);
        }
        let audio = input.audio.clone()?;
        input.cancelled.store(true, std::sync::atomic::Ordering::Release);
        input.waiting.store(false, std::sync::atomic::Ordering::Release);
        input.preparing.store(false, std::sync::atomic::Ordering::Release);
        input.cancelled = Default::default();
        input.waiting = Default::default();
        input.preparing = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let epoch = self.shared.command_epoch.fetch_add(1, std::sync::atomic::Ordering::AcqRel) + 1;
        Some(decoder::Load {
            audio, position, epoch,
            cancelled: input.cancelled.clone(),
            waiting: input.waiting.clone(),
            preparing: input.preparing.clone(),
        })
    }

    fn cancel_input(&self) -> u64 {
        let mut input = self.input.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        input.cancelled.store(true, std::sync::atomic::Ordering::Release);
        input.waiting.store(false, std::sync::atomic::Ordering::Release);
        input.preparing.store(false, std::sync::atomic::Ordering::Release);
        input.audio = None;
        self.shared.starved.store(false, std::sync::atomic::Ordering::Release);
        self.shared.command_epoch.fetch_add(1, std::sync::atomic::Ordering::AcqRel) + 1
    }

    pub fn volume(&self) -> f32 {
        self.shared.volume()
    }

    /// Sets the output gain, clamped to `0.0..=1.0`.
    pub fn set_volume(&self, volume: f32) {
        self.shared.set_volume(volume);
    }

    pub fn status(&self) -> Status {
        #[cfg(target_os="android")]
        self.stream.maintain();
        self.timeline_status()
    }

    /// Read the playback clock without doing output-stream maintenance on render ticks.
    pub fn timeline_status(&self) -> Status {
        timeline(&self.shared,self.rate)
    }

    pub fn clock(&self) -> PlaybackClock {
        PlaybackClock { shared:self.shared.clone(), rate:self.rate, analyser:self.analyser.clone() }
    }

    fn send(&self, command: Command) {
        if self.commands.send(command).is_err() {
            tracing::error!("decoder thread has stopped");
        }
    }

    // The stream runs while a track is loaded, paused included, so flushes are always acknowledged.
    fn run_stream(&self, running: bool) {
        let result = if running { self.stream.play() } else { self.stream.pause() };
        if let Err(err) = result {
            tracing::warn!(error = &err as &dyn std::error::Error, running, "cannot change output stream state");
        }
    }
}

fn timeline(shared:&Shared, rate:u32) -> Status {
        let state = shared.state();
        let duration = shared.duration.load();
        let position = match state {
            PlayerState::Idle => 0.0,
            _ => shared.position(rate).min(if duration > 0.0 { duration } else { f64::MAX }),
        };
        Status { state, position, duration }
}
