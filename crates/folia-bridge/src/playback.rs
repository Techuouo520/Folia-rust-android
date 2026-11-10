// One thread owns the player and the queue. Callers only send commands.
use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::JoinHandle;

use std::sync::Arc;

use folia_core::{AudioQuality, EqualizerSettings, Lyrics, Queue, Repeat};
use folia_player::{Player, PlayerError, PlayerState};
use parking_lot::Mutex;

use crate::{BridgeError, LibraryTrack, PlaybackSnapshot, RepeatMode, TransportState};

#[derive(Clone)]
struct Queued {
    id: i64,
    path: PathBuf,
    title: String,
    artist: String,
    album: String,
    duration: f64,
    has_cover: bool,
    provider: String,
    online_id: String,
    cover_url: String,
}

impl Queued {
    fn from_track(track: &LibraryTrack) -> Self {
        Self {
            id: track.id,
            path: PathBuf::from(&track.path),
            title: track.title.clone(),
            artist: track.artist.clone(),
            album: track.album.clone(),
            duration: track.duration_seconds,
            has_cover: track.has_cover,
            provider: track.provider.clone(),
            online_id: track.online_id.clone(),
            cover_url: track.cover_url.clone(),
        }
    }

    fn to_track(&self) -> LibraryTrack {
        LibraryTrack {
            id: self.id,
            title: self.title.clone(),
            artist: self.artist.clone(),
            album: self.album.clone(),
            duration_seconds: self.duration,
            path: self.path.to_string_lossy().into_owned(),
            has_cover: self.has_cover,
            provider: self.provider.clone(),
            online_id: self.online_id.clone(),
            cover_url: self.cover_url.clone(),
        }
    }
}

type AudioFetch = Arc<dyn Fn(&str, &str, AudioQuality) -> Result<PathBuf, String> + Send + Sync>;
type LyricsFetch = Arc<dyn Fn(&str, &str) -> Result<Option<Lyrics>, String> + Send + Sync>;
type ArtworkFetch = Arc<dyn Fn(&str, &str) -> Result<Vec<u8>, String> + Send + Sync>;

struct Playback {
    commands: Sender<Command>,
    player: Option<Player>,
    queue: Queue<Queued>,
    failures: usize,
    volume: f32,
    generation: i64,
    token: u64,
    loading: bool,
    quality: AudioQuality,
    equalizer: EqualizerSettings,
    lyrics: Option<(i64, Lyrics)>,
    artwork: Option<(i64, Vec<u8>)>,
    artwork_for: Option<i64>,
    audio_fetch: AudioFetch,
    lyrics_fetch: LyricsFetch,
    artwork_fetch: ArtworkFetch,
}

impl Playback {
    fn new(
        commands: Sender<Command>,
        audio_fetch: AudioFetch,
        lyrics_fetch: LyricsFetch,
        artwork_fetch: ArtworkFetch,
    ) -> Self {
        Self {
            commands,
            player: None,
            queue: Queue::default(),
            failures: 0,
            volume: 1.0,
            generation: 0,
            token: 0,
            loading: false,
            quality: AudioQuality::High,
            equalizer: EqualizerSettings::default(),
            lyrics: None,
            artwork: None,
            artwork_for: None,
            audio_fetch,
            lyrics_fetch,
            artwork_fetch,
        }
    }

    fn dispatch(&mut self, command: Command) {
        match command {
            Command::PlayAll { tracks, start, reply } => reply_send(reply, self.play_all(tracks, start)),
            Command::Play(reply) => reply_send(reply, self.play()),
            Command::Pause(reply) => reply_send(reply, self.pause()),
            Command::Seek { seconds, reply } => reply_send(reply, self.seek(seconds)),
            Command::Stop(reply) => reply_send(reply, self.stop()),
            Command::SetVolume { volume, reply } => reply_send(reply, self.set_volume(volume)),
            Command::Volume(reply) => reply_send(reply, Ok(self.volume())),
            Command::Status(reply) => reply_send(reply, self.status()),
            Command::Next(reply) => reply_send(reply, self.next()),
            Command::Previous(reply) => reply_send(reply, self.previous()),
            Command::PlayIndex { index, reply } => reply_send(reply, self.play_index(index)),
            Command::SetRepeat { mode, reply } => reply_send(reply, self.set_repeat(mode)),
            Command::CycleRepeat(reply) => reply_send(reply, Ok(self.cycle_repeat())),
            Command::Queue(reply) => reply_send(reply, Ok(self.queue_tracks())),
            Command::PlayTracks { tracks, start, reply } => reply_send(reply, self.play_all(tracks, start)),
            Command::SetQuality { quality, reply } => reply_send(reply, self.set_quality(quality)),
            Command::SetEqualizer { settings, reply } => reply_send(reply, self.apply_equalizer(settings)),
            Command::Lyrics(reply) => reply_send(reply, Ok(self.lyric_view())),
            Command::Artwork(reply) => reply_send(reply, Ok(self.artwork_bytes())),
            Command::Loaded { token, result } => self.loaded(token, result),
            Command::LyricsReady { id, lyrics } => self.store_lyrics(id, lyrics),
            Command::ArtworkReady { id, bytes } => self.store_artwork(id, bytes),
            Command::Shutdown => {}
        }
    }

    fn set_quality(&mut self, quality: AudioQuality) -> Result<(), BridgeError> {
        self.quality = quality;
        tracing::info!(quality = quality.id(), "audio quality set");
        Ok(())
    }

    fn apply_equalizer(&mut self, settings: EqualizerSettings) -> Result<(), BridgeError> {
        self.equalizer = settings;
        if let Some(player) = self.player.as_mut() {
            player.set_equalizer(folia_player::Parameters::of(&self.equalizer));
        }
        tracing::info!(enabled = self.equalizer.enabled, preset = %self.equalizer.preset, "equalizer applied");
        Ok(())
    }

    fn play_all(&mut self, tracks: Vec<LibraryTrack>, start: i32) -> Result<(), BridgeError> {
        if tracks.is_empty() {
            tracing::info!("play ignored because the library is empty");
            return Err(BridgeError::Playback { reason: "queue is empty".into() });
        }
        let start = queue_index(start, tracks.len())?;
        self.failures = 0;
        self.queue.replace(tracks.iter().map(Queued::from_track).collect(), start);
        self.generation = self.generation.wrapping_add(1);
        self.start_current()
    }

    fn play(&mut self) -> Result<(), BridgeError> {
        match self.player.as_ref().map(Player::status).map(|status| status.state) {
            Some(PlayerState::Paused) => {
                self.ensure_player()?.resume();
                tracing::info!("playback resumed");
                Ok(())
            }
            Some(PlayerState::Playing) => Ok(()),
            _ => {
                self.failures = 0;
                self.start_current()
            }
        }
    }

    fn pause(&mut self) -> Result<(), BridgeError> {
        if let Some(player) = self.player.as_ref() {
            player.pause();
            tracing::info!("playback paused");
        }
        Ok(())
    }

    fn seek(&mut self, seconds: f64) -> Result<(), BridgeError> {
        let seconds = seconds.max(0.0);
        self.ensure_player()?.seek(seconds);
        tracing::info!(seconds, "seek");
        Ok(())
    }

    fn stop(&mut self) -> Result<(), BridgeError> {
        if let Some(player) = self.player.as_ref() {
            player.stop();
        }
        tracing::info!("playback stopped");
        Ok(())
    }

    fn set_volume(&mut self, volume: f32) -> Result<(), BridgeError> {
        let volume = volume.clamp(0.0, 1.0);
        self.volume = volume;
        self.ensure_player()?.set_volume(volume);
        tracing::info!(volume, "volume set");
        Ok(())
    }

    fn volume(&self) -> f32 {
        self.player.as_ref().map(Player::volume).unwrap_or(self.volume)
    }

    fn status(&mut self) -> Result<PlaybackSnapshot, BridgeError> {
        self.pump()?;
        Ok(self.snapshot())
    }

    fn next(&mut self) -> Result<(), BridgeError> {
        self.failures = 0;
        self.advance(false)
    }

    fn previous(&mut self) -> Result<(), BridgeError> {
        self.failures = 0;
        if self.queue.back().is_some() {
            self.start_current()
        } else {
            tracing::info!("no previous track");
            Ok(())
        }
    }

    fn play_index(&mut self, index: i32) -> Result<(), BridgeError> {
        let selected = queue_index(index, self.queue.items().len())?;
        if self.queue.select(selected).is_none() {
            return bad_index(index).map(|_| ());
        }
        self.failures = 0;
        self.start_current()
    }

    fn set_repeat(&mut self, mode: RepeatMode) -> Result<(), BridgeError> {
        self.queue.repeat = mode.into();
        tracing::info!(?mode, "repeat mode set");
        Ok(())
    }

    fn cycle_repeat(&mut self) -> RepeatMode {
        let mode = match self.queue.repeat {
            Repeat::Off => RepeatMode::All,
            Repeat::All => RepeatMode::One,
            Repeat::One => RepeatMode::Off,
        };
        self.queue.repeat = mode.into();
        tracing::info!(?mode, "repeat mode set");
        mode
    }

    fn queue_tracks(&self) -> Vec<LibraryTrack> {
        self.queue.items().iter().map(Queued::to_track).collect()
    }

    fn pump(&mut self) -> Result<(), BridgeError> {
        match self.player.as_ref().map(Player::status).map(|status| status.state) {
            Some(PlayerState::Ended) => self.advance(true),
            Some(PlayerState::Failed) => self.fail(),
            _ => Ok(()),
        }
    }

    fn advance(&mut self, finished: bool) -> Result<(), BridgeError> {
        if self.queue.advance(finished).is_some() {
            self.start_current()
        } else {
            if let Some(player) = self.player.as_ref() {
                player.stop();
            }
            tracing::info!("playback reached the end of the queue");
            Ok(())
        }
    }

    fn fail(&mut self) -> Result<(), BridgeError> {
        self.failures += 1;
        let len = self.queue.items().len();
        if len == 0 || self.failures >= len {
            self.failures = 0;
            if let Some(player) = self.player.as_ref() {
                player.stop();
            }
            tracing::error!("playback stopped after repeated failures");
            return Ok(());
        }
        tracing::warn!(failures = self.failures, "skipping a track that failed to play");
        self.advance(false)
    }

    fn start_current(&mut self) -> Result<(), BridgeError> {
        let Some(track) = self.queue.current().cloned() else {
            tracing::info!("play ignored because the queue is empty");
            return Err(BridgeError::Playback { reason: "queue is empty".into() });
        };
        self.token = self.token.wrapping_add(1);
        self.loading = true;
        if let Some(player) = self.player.as_ref() {
            player.stop();
        }
        if track.path.is_file() {
            tracing::info!(title = %track.title, path = %track.path.display(), "playing");
            self.loading = false;
            self.ensure_player()?.load(&track.path);
            self.spawn_lyrics(&track);
            return Ok(());
        }
        if track.online_id.is_empty() {
            tracing::warn!(title = %track.title, "track has no file and no online id");
            self.loading = false;
            return self.fail();
        }
        tracing::info!(title = %track.title, provider = %track.provider, song = %track.online_id, "fetching audio");
        let token = self.token;
        let fetch = Arc::clone(&self.audio_fetch);
        let commands = self.commands.clone();
        let provider = track.provider.clone();
        let song = track.online_id.clone();
        let quality = self.quality;
        let spawned = std::thread::Builder::new().name("folia-audio".into()).spawn(move || {
            let result = fetch(&provider, &song, quality);
            if commands.send(Command::Loaded { token, result }).is_err() {
                tracing::warn!("playback thread dropped an audio download");
            }
        });
        if let Err(error) = spawned {
            tracing::error!(error = &error as &dyn std::error::Error, "cannot start an audio download");
            self.loading = false;
            return Err(BridgeError::Playback { reason: error.to_string() });
        }
        Ok(())
    }

    fn loaded(&mut self, token: u64, result: Result<PathBuf, String>) {
        if token != self.token {
            tracing::info!("ignoring an audio download for a track that is no longer current");
            return;
        }
        match result {
            Ok(path) => {
                if let Some(track) = self.queue.current_mut() {
                    track.path = path.clone();
                }
                self.loading = false;
                match self.ensure_player() {
                    Ok(player) => {
                        player.load(&path);
                        if let Some(track) = self.queue.current().cloned() {
                            self.spawn_lyrics(&track);
                        }
                        tracing::info!(path = %path.display(), "online audio ready");
                    }
                    Err(error) => tracing::error!(error = %error, "cannot play the downloaded audio"),
                }
            }
            Err(error) => {
                tracing::warn!(error, "audio download failed");
                self.loading = false;
                if let Err(play_error) = self.fail() {
                    tracing::error!(error = %play_error, "cannot skip a failed download");
                }
            }
        }
    }

    fn spawn_lyrics(&self, track: &Queued) {
        let id = track.id;
        let path = track.path.clone();
        let provider = track.provider.clone();
        let song = track.online_id.clone();
        let fetch = Arc::clone(&self.lyrics_fetch);
        let commands = self.commands.clone();
        let spawned = std::thread::Builder::new().name("folia-lyrics".into()).spawn(move || {
            let lyrics = if provider.is_empty() || song.is_empty() {
                folia_library::lyrics_for(&path)
            } else {
                fetch(&provider, &song).ok().flatten()
            };
            if commands.send(Command::LyricsReady { id, lyrics }).is_err() {
                tracing::warn!("playback thread dropped lyrics");
            }
        });
        if let Err(error) = spawned {
            tracing::error!(error = &error as &dyn std::error::Error, "cannot start lyric loading");
        }
    }

    fn store_lyrics(&mut self, id: i64, lyrics: Option<Lyrics>) {
        if self.queue.current().is_some_and(|track| track.id == id) {
            self.lyrics = lyrics.map(|lyrics| (id, lyrics));
            tracing::info!(id, ready = self.lyrics.is_some(), "lyrics stored");
        }
    }

    fn lyric_view(&mut self) -> crate::LyricSnapshot {
        self.pump().ok();
        let position = self.player.as_ref().map(Player::status).map(|status| status.position).unwrap_or(0.0);
        let Some((id, lyrics)) = &self.lyrics else {
            return crate::LyricSnapshot { line_index: -1, word_index: -1, lines: Vec::new() };
        };
        if self.queue.current().is_none_or(|track| track.id != *id) {
            return crate::LyricSnapshot { line_index: -1, word_index: -1, lines: Vec::new() };
        }
        let line_index = folia_lyrics::active_line(&lyrics.lines, position);
        let lines = lyrics
            .lines
            .iter()
            .map(|line| crate::LyricLine {
                text: line.text.clone(),
                start_seconds: line.start,
                end_seconds: line.end,
                words: line
                    .words
                    .iter()
                    .map(|word| crate::LyricWord {
                        text: word.text.clone(),
                        start_seconds: word.start,
                        end_seconds: word.end,
                    })
                    .collect(),
            })
            .collect();
        let word_index = line_index
            .and_then(|index| lyrics.lines.get(index))
            .and_then(|line| {
                line.words
                    .iter()
                    .rposition(|word| word.start <= position)
                    .map(|index| i32::try_from(index).unwrap_or(0))
            })
            .unwrap_or(-1);
        crate::LyricSnapshot {
            line_index: line_index.map(|index| i32::try_from(index).unwrap_or(0)).unwrap_or(-1),
            word_index,
            lines,
        }
    }

    fn artwork_bytes(&mut self) -> Option<Vec<u8>> {
        let track = self.queue.current()?.clone();
        if let Some((id, bytes)) = &self.artwork
            && *id == track.id
        {
            return Some(bytes.clone());
        }
        if self.artwork_for == Some(track.id) {
            return None;
        }
        self.artwork_for = Some(track.id);
        let fetch = Arc::clone(&self.artwork_fetch);
        let commands = self.commands.clone();
        let id = track.id;
        let path = track.path.to_string_lossy().into_owned();
        let url = track.cover_url.clone();
        let spawned =
            std::thread::Builder::new().name("folia-artwork".into()).spawn(move || match fetch(&path, &url) {
                Ok(bytes) => {
                    if commands.send(Command::ArtworkReady { id, bytes }).is_err() {
                        tracing::warn!("playback thread dropped artwork");
                    }
                }
                Err(error) => tracing::info!(error, "track has no artwork"),
            });
        if let Err(error) = spawned {
            tracing::error!(error = &error as &dyn std::error::Error, "cannot start artwork loading");
        }
        None
    }

    fn store_artwork(&mut self, id: i64, bytes: Vec<u8>) {
        if self.queue.current().is_some_and(|track| track.id == id) {
            self.artwork = Some((id, bytes));
            tracing::info!(id, "artwork ready");
        }
    }

    fn ensure_player(&mut self) -> Result<&Player, BridgeError> {
        if self.player.is_none() {
            let mut player = Player::new().map_err(play_err)?;
            player.set_volume(self.volume);
            player.set_equalizer(folia_player::Parameters::of(&self.equalizer));
            tracing::info!("audio output opened");
            self.player = Some(player);
        }
        self.player.as_ref().ok_or_else(|| BridgeError::Playback { reason: "audio output is not open".into() })
    }

    fn snapshot(&self) -> PlaybackSnapshot {
        let status = self.player.as_ref().map(Player::status);
        let current = self.queue.current();
        let state = status.map(|item| item.state).unwrap_or(PlayerState::Idle);
        let position = status.map(|item| item.position).unwrap_or(0.0);
        let decoded = status.map(|item| item.duration).unwrap_or(0.0);
        let (track_id, title, artist, album, path, queued_duration) = match current {
            Some(track) => (
                track.id,
                track.title.clone(),
                track.artist.clone(),
                track.album.clone(),
                track.path.to_string_lossy().into_owned(),
                track.duration,
            ),
            None => (0, String::new(), String::new(), String::new(), String::new(), 0.0),
        };
        PlaybackSnapshot {
            state: transport(state),
            position_seconds: position,
            duration_seconds: if decoded > 0.0 { decoded } else { queued_duration },
            volume: self.volume(),
            track_id,
            title,
            artist,
            album,
            path,
            queue_index: self.queue.current_index().map(as_i32).unwrap_or(-1),
            queue_length: as_i32(self.queue.items().len()),
            has_next: self.queue.next_index().is_some(),
            has_previous: self.queue.previous_index().is_some(),
            repeat_mode: self.queue.repeat.into(),
            queue_generation: self.generation,
            loading: self.loading,
            provider: current.map(|track| track.provider.clone()).unwrap_or_default(),
            online_id: current.map(|track| track.online_id.clone()).unwrap_or_default(),
            cover_url: current.map(|track| track.cover_url.clone()).unwrap_or_default(),
        }
    }
}

enum Command {
    PlayAll { tracks: Vec<LibraryTrack>, start: i32, reply: Sender<Result<(), BridgeError>> },
    Play(Sender<Result<(), BridgeError>>),
    Pause(Sender<Result<(), BridgeError>>),
    Seek { seconds: f64, reply: Sender<Result<(), BridgeError>> },
    Stop(Sender<Result<(), BridgeError>>),
    SetVolume { volume: f32, reply: Sender<Result<(), BridgeError>> },
    Volume(Sender<Result<f32, BridgeError>>),
    Status(Sender<Result<PlaybackSnapshot, BridgeError>>),
    Next(Sender<Result<(), BridgeError>>),
    Previous(Sender<Result<(), BridgeError>>),
    PlayIndex { index: i32, reply: Sender<Result<(), BridgeError>> },
    SetRepeat { mode: RepeatMode, reply: Sender<Result<(), BridgeError>> },
    CycleRepeat(Sender<Result<RepeatMode, BridgeError>>),
    Queue(Sender<Result<Vec<LibraryTrack>, BridgeError>>),
    PlayTracks { tracks: Vec<LibraryTrack>, start: i32, reply: Sender<Result<(), BridgeError>> },
    SetQuality { quality: AudioQuality, reply: Sender<Result<(), BridgeError>> },
    SetEqualizer { settings: EqualizerSettings, reply: Sender<Result<(), BridgeError>> },
    Lyrics(Sender<Result<crate::LyricSnapshot, BridgeError>>),
    Artwork(Sender<Result<Option<Vec<u8>>, BridgeError>>),
    Shutdown,
    Loaded { token: u64, result: Result<PathBuf, String> },
    LyricsReady { id: i64, lyrics: Option<Lyrics> },
    ArtworkReady { id: i64, bytes: Vec<u8> },
}

pub(crate) struct PlaybackHost {
    commands: Mutex<Option<Sender<Command>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl PlaybackHost {
    pub(crate) fn start(
        audio_fetch: AudioFetch,
        lyrics_fetch: LyricsFetch,
        artwork_fetch: ArtworkFetch,
    ) -> Result<Self, BridgeError> {
        let (commands, receiver) = mpsc::channel();
        let thread_commands = commands.clone();
        let thread = std::thread::Builder::new()
            .name("folia-playback".into())
            .spawn(move || run(receiver, thread_commands, audio_fetch, lyrics_fetch, artwork_fetch))
            .map_err(|error| {
                tracing::error!(error = &error as &dyn std::error::Error, "cannot start the playback thread");
                BridgeError::Playback { reason: error.to_string() }
            })?;
        Ok(Self { commands: Mutex::new(Some(commands)), thread: Mutex::new(Some(thread)) })
    }

    pub(crate) fn play_all(&self, tracks: Vec<LibraryTrack>, start: i32) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::PlayAll { tracks, start, reply })
    }

    pub(crate) fn play(&self) -> Result<(), BridgeError> {
        self.roundtrip(Command::Play)
    }

    pub(crate) fn pause(&self) -> Result<(), BridgeError> {
        self.roundtrip(Command::Pause)
    }

    pub(crate) fn seek(&self, seconds: f64) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::Seek { seconds, reply })
    }

    pub(crate) fn stop(&self) -> Result<(), BridgeError> {
        self.roundtrip(Command::Stop)
    }

    pub(crate) fn set_volume(&self, volume: f32) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::SetVolume { volume, reply })
    }

    pub(crate) fn volume(&self) -> Result<f32, BridgeError> {
        self.roundtrip(Command::Volume)
    }

    pub(crate) fn status(&self) -> Result<PlaybackSnapshot, BridgeError> {
        self.roundtrip(Command::Status)
    }

    pub(crate) fn next(&self) -> Result<(), BridgeError> {
        self.roundtrip(Command::Next)
    }

    pub(crate) fn previous(&self) -> Result<(), BridgeError> {
        self.roundtrip(Command::Previous)
    }

    pub(crate) fn play_index(&self, index: i32) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::PlayIndex { index, reply })
    }

    pub(crate) fn set_repeat(&self, mode: RepeatMode) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::SetRepeat { mode, reply })
    }

    pub(crate) fn cycle_repeat(&self) -> Result<RepeatMode, BridgeError> {
        self.roundtrip(Command::CycleRepeat)
    }

    pub(crate) fn queue(&self) -> Result<Vec<LibraryTrack>, BridgeError> {
        self.roundtrip(Command::Queue)
    }

    pub(crate) fn play_tracks(&self, tracks: Vec<LibraryTrack>, start: i32) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::PlayTracks { tracks, start, reply })
    }

    pub(crate) fn set_quality(&self, quality: AudioQuality) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::SetQuality { quality, reply })
    }

    pub(crate) fn set_equalizer(&self, settings: EqualizerSettings) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::SetEqualizer { settings, reply })
    }

    pub(crate) fn lyrics(&self) -> Result<crate::LyricSnapshot, BridgeError> {
        self.roundtrip(Command::Lyrics)
    }

    pub(crate) fn artwork(&self) -> Result<Option<Vec<u8>>, BridgeError> {
        self.roundtrip(Command::Artwork)
    }

    fn roundtrip<T>(&self, make: impl FnOnce(Sender<Result<T, BridgeError>>) -> Command) -> Result<T, BridgeError> {
        let (reply, waiter) = mpsc::channel();
        let guard = self.commands.lock();
        let Some(commands) = guard.as_ref() else {
            tracing::error!("playback thread has stopped");
            return Err(BridgeError::PlaybackStopped);
        };
        commands.send(make(reply)).map_err(|_| {
            tracing::error!("playback thread has stopped");
            BridgeError::PlaybackStopped
        })?;
        drop(guard);
        waiter.recv().map_err(|_| {
            tracing::error!("playback thread dropped the reply");
            BridgeError::PlaybackStopped
        })?
    }
}

impl Drop for PlaybackHost {
    fn drop(&mut self) {
        if let Some(commands) = self.commands.lock().take() {
            let _ = commands.send(Command::Shutdown);
        }
        let thread = self.thread.lock().take();
        if let Some(thread) = thread
            && thread.join().is_err()
        {
            tracing::error!("playback thread panicked");
        }
    }
}

fn run(
    receiver: Receiver<Command>,
    commands: Sender<Command>,
    audio: AudioFetch,
    lyrics: LyricsFetch,
    artwork: ArtworkFetch,
) {
    let mut playback = Playback::new(commands, audio, lyrics, artwork);
    while let Ok(command) = receiver.recv() {
        if matches!(command, Command::Shutdown) {
            break;
        }
        playback.dispatch(command);
    }
    tracing::info!("playback thread stopped");
}

fn reply_send<T>(sender: Sender<Result<T, BridgeError>>, result: Result<T, BridgeError>) {
    if sender.send(result).is_err() {
        tracing::warn!("playback caller dropped the reply");
    }
}

fn play_err(error: PlayerError) -> BridgeError {
    match error {
        PlayerError::NoDevice => {
            tracing::error!("no audio output device");
            BridgeError::NoAudioDevice
        }
        other => {
            tracing::error!(error = &other as &dyn std::error::Error, "audio output failed");
            BridgeError::Playback { reason: other.to_string() }
        }
    }
}

fn queue_index(index: i32, len: usize) -> Result<usize, BridgeError> {
    match usize::try_from(index) {
        Ok(index) if index < len => Ok(index),
        _ => bad_index(index),
    }
}

fn bad_index(index: i32) -> Result<usize, BridgeError> {
    tracing::warn!(index, "track index is out of range");
    Err(BridgeError::Playback { reason: "track index is out of range".into() })
}

fn transport(state: PlayerState) -> TransportState {
    match state {
        PlayerState::Idle => TransportState::Idle,
        PlayerState::Playing => TransportState::Playing,
        PlayerState::Paused => TransportState::Paused,
        PlayerState::Ended => TransportState::Ended,
        PlayerState::Failed => TransportState::Failed,
    }
}

fn as_i32(value: usize) -> i32 {
    i32::try_from(value).unwrap_or(i32::MAX)
}

impl From<RepeatMode> for Repeat {
    fn from(mode: RepeatMode) -> Self {
        match mode {
            RepeatMode::Off => Repeat::Off,
            RepeatMode::All => Repeat::All,
            RepeatMode::One => Repeat::One,
        }
    }
}

impl From<Repeat> for RepeatMode {
    fn from(mode: Repeat) -> Self {
        match mode {
            Repeat::Off => RepeatMode::Off,
            Repeat::All => RepeatMode::All,
            Repeat::One => RepeatMode::One,
        }
    }
}
