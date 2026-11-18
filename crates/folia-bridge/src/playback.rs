// One thread owns the player and the queue. Callers only send commands.
use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::JoinHandle;

use std::sync::Arc;

use folia_core::{AudioQuality, Lyrics, Queue, Repeat};
use folia_player::{Player, PlayerError, PlayerState};
use parking_lot::Mutex;

use crate::{BridgeError, LibraryTrack, PlaybackSnapshot, RepeatMode, TransportState};

#[derive(Clone, serde::Serialize, serde::Deserialize)]
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

#[derive(serde::Serialize,serde::Deserialize)]
struct ResumeState { version:u8, tracks:Vec<Queued>, index:usize, position:f64, repeat:Repeat }

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
    lyrics: Option<(i64, Arc<Lyrics>)>,
    lyrics_generation: u64,
    lyrics_loading: bool,
    lyrics_failed: bool,
    audio_fetch: AudioFetch,
    lyrics_fetch: LyricsFetch,
    checkpoint: PathBuf,
    last_save: std::time::Instant,
    last_checkpoint: Option<Vec<u8>>,
    position_on_load: f64,
    restored: bool,
    want_play: bool,
    recovering: bool,
}

impl Playback {
    fn new(
        commands: Sender<Command>,
        audio_fetch: AudioFetch,
        lyrics_fetch: LyricsFetch,
        checkpoint: PathBuf,
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
            lyrics: None,
            lyrics_generation: 0,
            lyrics_loading: false,
            lyrics_failed: false,
            audio_fetch,
            lyrics_fetch,
            checkpoint, last_save:std::time::Instant::now(),
            last_checkpoint: None,
            position_on_load:0.0, restored:false, want_play:false, recovering:false,
        }
    }

    fn restore(&mut self) {
        let loaded=std::fs::read(&self.checkpoint).ok().and_then(|bytes|serde_json::from_slice::<ResumeState>(&bytes).ok());
        if let Some(saved)=loaded.filter(|s|s.version==1 && s.index<s.tracks.len() && s.position.is_finite()) {
            self.queue.replace(saved.tracks,saved.index);self.queue.repeat=saved.repeat;
            self.position_on_load=saved.position.max(0.0);self.restored=true;self.want_play=false;
            self.generation=self.generation.wrapping_add(1);
        }
    }
    fn save(&mut self, force: bool) {
        if !force && self.last_save.elapsed() < std::time::Duration::from_secs(2) {
            return;
        }
        self.last_save = std::time::Instant::now();
        let Some(index) = self.queue.current_index() else {
            if !self.last_checkpoint.as_ref().is_some_and(|bytes| bytes.is_empty())
                && let Err(error) = self.clear_checkpoint()
            {
                tracing::warn!(%error, "empty playback checkpoint cleanup failed");
            }
            return;
        };
        let state = ResumeState {
            version: 1,
            tracks: self.queue.items().to_vec(),
            index,
            position: self.snapshot().position_seconds,
            repeat: self.queue.repeat,
        };
        let result = (|| -> Result<(), Box<dyn std::error::Error>> {
            let bytes = serde_json::to_vec(&state)?;
            if self.last_checkpoint.as_deref() == Some(bytes.as_slice()) {
                return Ok(());
            }
            if let Some(parent) = self.checkpoint.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let temporary = self.checkpoint.with_extension("tmp");
            use std::io::Write;
            let mut file = std::fs::File::create(&temporary)?;
            file.write_all(&bytes)?;
            file.sync_all()?;
            std::fs::rename(temporary, &self.checkpoint)?;
            self.last_checkpoint = Some(bytes);
            Ok(())
        })();
        if let Err(error) = result {
            tracing::warn!(%error, "playback checkpoint failed");
        }
    }

    fn clear_checkpoint(&mut self) -> Result<(), BridgeError> {
        let temporary = self.checkpoint.with_extension("tmp");
        for path in [&self.checkpoint, &temporary] {
            match std::fs::remove_file(path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(BridgeError::Playback {
                    reason: format!("cannot clear saved playback: {error}"),
                }),
            }
        }
        // An empty marker avoids repeating filesystem work while the queue stays empty.
        self.last_checkpoint = Some(Vec::new());
        Ok(())
    }

    // Mutate queue ownership on the playback thread without restarting the current decoder.
    fn enqueue_next(&mut self, tracks: Vec<LibraryTrack>) -> Result<(), BridgeError> {
        if tracks.is_empty() { return Ok(()); }
        let current = self.queue.current_index();
        let mut queued = self.queue.items().to_vec();
        let at = current.map_or(0, |i| i + 1);
        queued.splice(at..at, tracks.iter().map(Queued::from_track));
        self.queue.replace(queued, current.unwrap_or(usize::MAX));
        self.generation = self.generation.wrapping_add(1);
        Ok(())
    }

    fn dispatch(&mut self, command: Command) {
        match command {
            Command::PlayAll { tracks, start, reply } => reply_send(reply, self.play_all(tracks, start)),
            Command::Play(reply) => reply_send(reply, self.play()),
            Command::Pause(reply) => reply_send(reply, self.pause()),
            Command::Seek { seconds, owner, reply } => {
                let current = owner.is_none_or(|(generation, index)| self.generation == generation
                    && self.queue.current_index().is_some_and(|i| as_i32(i) == index));
                reply_send(reply, if current { self.seek(seconds) } else { Ok(()) });
            }
            Command::Stop(reply) => reply_send(reply, self.stop()),
            Command::ClearQueue(reply) => reply_send(reply, self.clear_queue()),
            Command::SetVolume { volume, reply } => reply_send(reply, self.set_volume(volume)),
            Command::Volume(reply) => reply_send(reply, Ok(self.volume())),
            Command::Status(reply) => reply_send(reply, self.status()),
            Command::Next(reply) => reply_send(reply, self.next()),
            Command::Previous(reply) => reply_send(reply, self.previous()),
            Command::PlayIndex { index, reply } => reply_send(reply, self.play_index(index)),
            Command::SetRepeat { mode, reply } => reply_send(reply, self.set_repeat(mode)),
            Command::CycleRepeat(reply) => reply_send(reply, Ok(self.cycle_repeat())),
            Command::EnqueueNext { tracks, reply } => reply_send(reply, self.enqueue_next(tracks)),
            Command::Queue(reply) => reply_send(reply, Ok(self.queue_tracks())),
            Command::PlayTracks { tracks, start, reply } => reply_send(reply, self.play_all(tracks, start)),
            Command::SetQuality { quality, reply } => reply_send(reply, self.set_quality(quality)),
            Command::Lyrics(reply) => reply_send(reply, Ok(self.lyric_view())),
            #[cfg(target_os = "android")]
            Command::RequestLyrics { id, provider, online_id, retry } => {
                if self.queue.current().is_some_and(|track| {
                    track.id == id && track.provider == provider && track.online_id == online_id
                }) {
                    self.ensure_lyrics(retry);
                }
            }
            Command::Loaded { token, result } => self.loaded(token, result),
            Command::LyricsReady { token, generation, id, result }
                if token == self.token && generation == self.lyrics_generation => self.store_lyrics(id, result),
            Command::LyricsReady { .. } => {},
            Command::Shutdown => {}
        }
    }

    fn set_quality(&mut self, quality: AudioQuality) -> Result<(), BridgeError> {
        self.quality = quality;
        tracing::info!(quality = quality.id(), "audio quality set");
        Ok(())
    }

    fn play_all(&mut self, tracks: Vec<LibraryTrack>, start: i32) -> Result<(), BridgeError> {
        if tracks.is_empty() {
            tracing::info!("play ignored because the library is empty");
            return Err(BridgeError::Playback { reason: "queue is empty".into() });
        }
        let start = queue_index(start, tracks.len())?;
        self.restored=false;self.position_on_load=0.0;self.want_play=true;
        self.failures = 0;
        self.queue.replace(tracks.iter().map(Queued::from_track).collect(), start);
        self.generation = self.generation.wrapping_add(1);
        self.start_current()
    }

    fn play(&mut self) -> Result<(), BridgeError> {
        self.want_play=true;
        if self.loading {return Ok(())}
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
        self.want_play=false;
        if let Some(player) = self.player.as_ref() {
            player.pause();
            tracing::info!("playback paused");
        }
        Ok(())
    }

    fn seek(&mut self, seconds: f64) -> Result<(), BridgeError> {
        if !seconds.is_finite(){return Err(BridgeError::Playback{reason:"invalid seek position".into()})}
        let seconds = seconds.max(0.0);
        if self.restored || self.loading {self.position_on_load=seconds;return Ok(())}
        self.ensure_player()?.seek(seconds);
        tracing::info!(seconds, "seek");
        Ok(())
    }

    fn stop(&mut self) -> Result<(), BridgeError> {
        self.token=self.token.wrapping_add(1);self.loading=false;self.want_play=false;
        self.lyrics_loading = false;
        self.restored=true;self.position_on_load=0.0;
        if let Some(player) = self.player.as_ref() {
            player.stop();
        }
        self.ensure_lyrics(false);
        tracing::info!("playback stopped");
        Ok(())
    }

    fn clear_queue(&mut self) -> Result<(), BridgeError> {
        self.token = self.token.wrapping_add(1);
        self.lyrics_generation = self.lyrics_generation.wrapping_add(1);
        self.generation = self.generation.wrapping_add(1);
        self.loading = false;
        self.lyrics_loading = false;
        self.lyrics_failed = false;
        self.lyrics = None;
        self.want_play = false;
        self.restored = false;
        self.recovering = false;
        self.position_on_load = 0.0;
        self.failures = 0;
        self.queue.replace(Vec::new(), 0);
        if let Some(player) = self.player.take() {
            player.stop();
        }
        self.last_checkpoint = None;
        self.clear_checkpoint()?;
        tracing::info!("playback queue cleared");
        Ok(())
    }

    fn set_volume(&mut self, volume: f32) -> Result<(), BridgeError> {
        let volume = volume.clamp(0.0, 1.0);
        self.volume = volume;
        if let Some(player)=self.player.as_ref(){player.set_volume(volume);}
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
        self.restored=false;self.position_on_load=0.0;self.want_play=true;
        self.failures = 0;
        self.advance(false)
    }

    fn previous(&mut self) -> Result<(), BridgeError> {
        self.restored=false;self.position_on_load=0.0;self.want_play=true;
        self.failures = 0;
        if self.queue.back().is_some() {
            self.start_current()
        } else {
            tracing::info!("no previous track");
            Ok(())
        }
    }

    fn play_index(&mut self, index: i32) -> Result<(), BridgeError> {
        self.restored=false;self.position_on_load=0.0;self.want_play=true;
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
            Some(PlayerState::Ended) if self.want_play => self.advance(true),
            Some(PlayerState::Failed) => {
                // Recover the same song/position once after a native route disconnect.
                if self.recovering {
                    self.position_on_load=self.player.as_ref().map(|p|p.status().position).unwrap_or(self.position_on_load);
                    self.player=None;self.restored=true;self.want_play=false;self.loading=false;return Ok(())
                }
                self.position_on_load=self.player.as_ref().map(|p|p.status().position).unwrap_or(0.0);
                self.player=None;self.restored=true;self.recovering=true;self.start_current()
            },
            _ => Ok(()),
        }
    }

    fn advance(&mut self, finished: bool) -> Result<(), BridgeError> {
        self.restored=false;self.position_on_load=0.0;self.want_play=true;
        if self.queue.advance(finished).is_some() {
            self.start_current()
        } else {
            self.want_play=false;
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
        if !self.restored {self.position_on_load=0.0;self.want_play=true;self.recovering=false;}
        self.restored=false;
        self.token = self.token.wrapping_add(1);
        self.lyrics = None;
        self.lyrics_loading = false;
        self.lyrics_failed = false;
        self.loading = true;
        if let Some(player) = self.player.as_ref() {
            player.stop();
        }
        if track.path.is_file() {
            tracing::info!(title = %track.title, path = %track.path.display(), "playing");
            self.loading = false;
            let position=self.position_on_load;let playing=self.want_play;
            self.ensure_player()?.load_at(&track.path,position,playing);
            self.spawn_lyrics(&track);
            return Ok(());
        }
        if track.online_id.is_empty() {
            tracing::warn!(title = %track.title, "track has no file and no online id");
            self.loading = false;
            return self.fail();
        }
        self.spawn_lyrics(&track);
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
                let position=self.position_on_load;let playing=self.want_play;
                match self.ensure_player() {
                    Ok(player) => {
                        player.load_at(&path,position,playing);
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

    fn ensure_lyrics(&mut self, retry: bool) {
        if self.lyrics_loading || (!retry && (self.lyrics.is_some() || self.lyrics_failed)) {
            return;
        }
        if let Some(track) = self.queue.current().cloned() {
            self.spawn_lyrics(&track);
        }
    }

    fn spawn_lyrics(&mut self, track: &Queued) {
        self.lyrics_generation = self.lyrics_generation.wrapping_add(1);
        self.lyrics_loading = true;
        self.lyrics_failed = false;
        self.lyrics = None;
        let generation = self.lyrics_generation;
        let token = self.token;
        let id = track.id;
        let path = track.path.clone();
        let provider = track.provider.clone();
        let song = track.online_id.clone();
        let fetch = Arc::clone(&self.lyrics_fetch);
        let commands = self.commands.clone();
        let spawned = std::thread::Builder::new().name("folia-lyrics".into()).spawn(move || {
            let result = if provider.is_empty() || song.is_empty() {
                Ok(folia_library::lyrics_for(&path))
            } else {
                fetch(&provider, &song)
            };
            if commands.send(Command::LyricsReady { token, generation, id, result }).is_err() {
                tracing::warn!("playback thread dropped lyrics");
            }
        });
        if let Err(error) = spawned {
            self.store_lyrics(id, Err(error.to_string()));
            tracing::error!(error = &error as &dyn std::error::Error, "cannot start lyric loading");
        }
    }

    fn store_lyrics(&mut self, id: i64, result: Result<Option<Lyrics>, String>) {
        if self.queue.current().is_some_and(|track| track.id == id) {
            self.lyrics_loading = false;
            match result {
                Ok(lyrics) => {
                    self.lyrics_failed = false;
                    self.lyrics = Some((id, Arc::new(lyrics.unwrap_or_default())));
                    tracing::info!(id, "lyrics stored");
                }
                Err(error) => {
                    self.lyrics_failed = true;
                    self.lyrics = None;
                    tracing::warn!(id, error, "lyrics could not be loaded");
                }
            }
        }
    }

    fn lyric_view(&mut self) -> crate::LyricSnapshot {
        self.pump().ok();
        self.ensure_lyrics(false);
        let position = self.snapshot().position_seconds;
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

    fn ensure_player(&mut self) -> Result<&Player, BridgeError> {
        if self.player.as_ref().is_some_and(|p|p.status().state==PlayerState::Failed){self.player=None;}
        if self.player.is_none() {
            let player = Player::new().map_err(play_err)?;
            player.set_volume(self.volume);
            tracing::info!("audio output opened");
            self.player = Some(player);
        }
        self.player.as_ref().ok_or_else(|| BridgeError::Playback { reason: "audio output is not open".into() })
    }

    fn snapshot(&self) -> PlaybackSnapshot {
        let status = self.player.as_ref().map(Player::status);
        let current = self.queue.current();
        let state = if (self.restored || self.loading) && !self.want_play {PlayerState::Paused}
            else {status.map(|item| item.state).unwrap_or(PlayerState::Idle)};
        let position = if self.restored || self.loading {self.position_on_load} else {status.map(|item| item.position).unwrap_or(0.0)};
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
            current_lyric: self.lyrics.as_ref()
                .filter(|(id, lyrics)| Some(*id) == current.map(|track| track.id) && !lyrics.lines.is_empty())
                .map(|(_, lyrics)| folia_lyrics::active_line(&lyrics.lines, position)
                    .map(|index| lyrics.lines[index].text.clone()).unwrap_or_default()),
        }
    }
}

enum Command {
    EnqueueNext { tracks: Vec<LibraryTrack>, reply: Sender<Result<(), BridgeError>> },
    PlayAll { tracks: Vec<LibraryTrack>, start: i32, reply: Sender<Result<(), BridgeError>> },
    Play(Sender<Result<(), BridgeError>>),
    Pause(Sender<Result<(), BridgeError>>),
    Seek { seconds: f64, owner: Option<(i64,i32)>, reply: Sender<Result<(), BridgeError>> },
    Stop(Sender<Result<(), BridgeError>>),
    ClearQueue(Sender<Result<(), BridgeError>>),
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
    Lyrics(Sender<Result<crate::LyricSnapshot, BridgeError>>),
    #[cfg(target_os = "android")]
    RequestLyrics { id: i64, provider: String, online_id: String, retry: bool },
    Shutdown,
    Loaded { token: u64, result: Result<PathBuf, String> },
    LyricsReady { token: u64, generation: u64, id: i64, result: Result<Option<Lyrics>, String> },
}

#[cfg(target_os = "android")]
type MonetFeed = Arc<Mutex<Option<Arc<crate::monet::Input>>>>;

#[cfg(target_os = "android")]
impl Playback {
    fn publish_monet(&self, feed: &MonetFeed) {
        let mut slot = feed.lock();
        let Some(track) = self.queue.current() else { *slot = None; return };
        let lyrics = self.lyrics.as_ref().map(|(_, lyrics)| lyrics);
        let player = self.player.as_ref().filter(|_| !self.restored && !self.loading);
        let same = slot.as_ref().is_some_and(|old| old.token == self.token &&
            std::path::Path::new(&old.track.path) == track.path &&
            old.lyrics_failed == self.lyrics_failed && old.position == self.position_on_load &&
            match (&old.clock, player) { (Some(c), Some(p)) => c.belongs_to(p), (None,None) => true, _ => false } &&
            match (&old.lyrics, lyrics) { (Some(a),Some(b)) => Arc::ptr_eq(a,b), (None,None) => true, _ => false });
        if !same {
            *slot = Some(Arc::new(crate::monet::Input { token: self.token, track: track.to_track(),
                lyrics: lyrics.cloned(), lyrics_failed: self.lyrics_failed, position: self.position_on_load,
                clock: player.map(Player::clock) }));
        }
    }
}

pub(crate) struct PlaybackHost {
    #[cfg(target_os = "android")]
    monet: MonetFeed,
    commands: Mutex<Option<Sender<Command>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl PlaybackHost {
    pub(crate) fn start(
        audio_fetch: AudioFetch,
        lyrics_fetch: LyricsFetch,
        checkpoint: PathBuf,
    ) -> Result<Self, BridgeError> {
        let (commands, receiver) = mpsc::channel();
        #[cfg(target_os = "android")]
        let monet = MonetFeed::default();
        #[cfg(target_os = "android")]
        let publisher = monet.clone();
        let thread_commands = commands.clone();
        let thread = std::thread::Builder::new()
            .name("folia-playback".into())
            .spawn(move || run(receiver, thread_commands, audio_fetch, lyrics_fetch, checkpoint, #[cfg(target_os = "android")] publisher))
            .map_err(|error| {
                tracing::error!(error = &error as &dyn std::error::Error, "cannot start the playback thread");
                BridgeError::Playback { reason: error.to_string() }
            })?;
        Ok(Self { commands: Mutex::new(Some(commands)), thread: Mutex::new(Some(thread)), #[cfg(target_os = "android")] monet })
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
        self.roundtrip(|reply| Command::Seek { seconds, owner: None, reply })
    }

    pub(crate) fn seek_queue_item(&self, seconds: f64, generation: i64, index: i32) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::Seek { seconds, owner: Some((generation,index)), reply })
    }

    pub(crate) fn stop(&self) -> Result<(), BridgeError> {
        self.roundtrip(Command::Stop)
    }

    pub(crate) fn clear_queue(&self) -> Result<(), BridgeError> {
        self.roundtrip(Command::ClearQueue)
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

    pub(crate) fn enqueue_next(&self, tracks: Vec<LibraryTrack>) -> Result<(), BridgeError> {
        self.roundtrip(|reply| Command::EnqueueNext { tracks, reply })
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

    #[cfg(target_os = "android")]
    pub(crate) fn monet_input(&self) -> Option<Arc<crate::monet::Input>> { self.monet.lock().clone() }

    pub(crate) fn lyrics(&self) -> Result<crate::LyricSnapshot, BridgeError> {
        self.roundtrip(Command::Lyrics)
    }

    #[cfg(target_os = "android")]
    pub(crate) fn request_lyrics(&self, id: i64, provider: String, online_id: String, retry: bool) {
        let guard = self.commands.lock();
        if let Some(commands) = guard.as_ref()
            && commands.send(Command::RequestLyrics { id, provider, online_id, retry }).is_err()
        {
            tracing::warn!("playback thread dropped a lyric request");
        }
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
    checkpoint: PathBuf,
    #[cfg(target_os = "android")] monet: MonetFeed,
) {
    let mut playback = Playback::new(commands, audio, lyrics, checkpoint);
    playback.restore();
    loop {
        #[cfg(target_os = "android")]
        playback.publish_monet(&monet);
        match receiver.recv_timeout(std::time::Duration::from_millis(250)) {
            Ok(Command::Shutdown)=>break,
            Ok(command)=> {
                let force=matches!(command,Command::Pause(_)|Command::Stop(_)|Command::Seek{..}|Command::PlayAll{..}|Command::PlayTracks{..}|Command::PlayIndex{..}|Command::Next(_)|Command::Previous(_)|Command::SetRepeat{..}|Command::EnqueueNext{..});
                playback.dispatch(command);playback.save(force);
            },
            Err(mpsc::RecvTimeoutError::Timeout)=>{let _=playback.pump();playback.save(false);},
            Err(mpsc::RecvTimeoutError::Disconnected)=>break,
        }
    }
    playback.save(true);
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
