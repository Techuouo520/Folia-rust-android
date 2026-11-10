// Android bridge: directories, a background library scan, and queued playback.
use std::sync::Once;

use folia_core::LanguagePreference;
use folia_i18n::I18n;
use folia_store::AppDirs;

mod covers;
mod library;
mod online;
mod playback;
mod settings;
mod wall;

pub use wall::{PointerEnd, WallBoard};

#[cfg(target_os = "android")]
mod android;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum BridgeError {
    #[error("application directories must be absolute")]
    InvalidDirectory,
    #[error("directory initialization failed: {reason}")]
    Directory { reason: String },
    #[error("translation initialization failed: {reason}")]
    Translation { reason: String },
    #[error("library failed: {reason}")]
    Library { reason: String },
    #[error("no audio output device")]
    NoAudioDevice,
    #[error("playback failed: {reason}")]
    Playback { reason: String },
    #[error("playback thread stopped")]
    PlaybackStopped,
    #[error("settings failed: {reason}")]
    Settings { reason: String },
    #[error("online failed: {reason}")]
    Online { reason: String },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ScanPhase {
    Idle,
    Running,
    Done,
    Failed,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ScanSnapshot {
    pub phase: ScanPhase,
    pub updated: i32,
    pub removed: i32,
    pub total: i32,
    pub seen: i32,
    pub reading: i32,
    pub reading_total: i32,
    pub current_file: String,
    pub error: String,
}

impl Default for ScanSnapshot {
    fn default() -> Self {
        Self {
            phase: ScanPhase::Idle,
            updated: 0,
            removed: 0,
            total: 0,
            seen: 0,
            reading: 0,
            reading_total: 0,
            current_file: String::new(),
            error: String::new(),
        }
    }
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct LibraryTrack {
    pub id: i64,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_seconds: f64,
    pub path: String,
    pub has_cover: bool,
    pub provider: String,
    pub online_id: String,
    pub cover_url: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct CoverImage {
    pub width: i32,
    pub height: i32,
    pub rgba: Vec<u8>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TransportState {
    Idle,
    Playing,
    Paused,
    Ended,
    Failed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RepeatMode {
    Off,
    All,
    One,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct PlaybackSnapshot {
    pub state: TransportState,
    pub position_seconds: f64,
    pub duration_seconds: f64,
    pub volume: f32,
    pub track_id: i64,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub path: String,
    pub queue_index: i32,
    pub queue_length: i32,
    pub has_next: bool,
    pub has_previous: bool,
    pub repeat_mode: RepeatMode,
    pub queue_generation: i64,
    pub loading: bool,
    pub provider: String,
    pub online_id: String,
    pub cover_url: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct LyricWord {
    pub text: String,
    pub start_seconds: f64,
    pub end_seconds: f64,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct LyricLine {
    pub text: String,
    pub start_seconds: f64,
    pub end_seconds: f64,
    pub words: Vec<LyricWord>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct LyricSnapshot {
    pub line_index: i32,
    pub word_index: i32,
    pub lines: Vec<LyricLine>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct EqualizerSnapshot {
    pub enabled: bool,
    pub preset: String,
    pub gains: Vec<f32>,
    pub presets: Vec<String>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct PlatformInfo {
    pub id: String,
    pub name: String,
    pub short_name: String,
    pub letter: String,
    pub color: u32,
    pub login_title: String,
    pub login_note: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum LoginPhase {
    Idle,
    Loading,
    Waiting,
    Scanned,
    Confirmed,
    Expired,
    Failed,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct LoginSnapshot {
    pub phase: LoginPhase,
    pub width: i32,
    pub modules: Vec<u8>,
    pub message: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SessionPhase {
    Resolving,
    SignedOut,
    SignedIn,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SessionSnapshot {
    pub phase: SessionPhase,
    pub provider: String,
    pub user_id: String,
    pub nickname: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct CollectionCard {
    pub key: String,
    pub title: String,
    pub subtitle: String,
    pub cover_url: String,
    pub kind: String,
    pub track_count: i32,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ExploreCard {
    pub key: String,
    pub title: String,
    pub subtitle: String,
    pub cover_url: String,
    pub section: String,
    pub playable: bool,
    pub track_index: i32,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct HomeSnapshot {
    pub session: SessionSnapshot,
    pub loading: bool,
    pub error: String,
    pub cards: Vec<CollectionCard>,
    pub explore: Vec<ExploreCard>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SearchSnapshot {
    pub loading: bool,
    pub error: String,
    pub query: String,
    pub tracks: Vec<LibraryTrack>,
    pub has_more: bool,
    pub next_offset: i32,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct CollectionPage {
    pub key: String,
    pub title: String,
    pub loading: bool,
    pub error: String,
    pub tracks: Vec<LibraryTrack>,
}

#[derive(uniffi::Object)]
pub struct FoliaBridge {
    dirs: AppDirs,
    settings: settings::SettingsHost,
    library: library::LibraryHost,
    online: online::OnlineHost,
    covers: covers::CoverHost,
    playback: playback::PlaybackHost,
}

#[uniffi::export]
impl FoliaBridge {
    #[uniffi::constructor]
    pub fn initialize(files_dir: String, cache_dir: String) -> Result<std::sync::Arc<Self>, BridgeError> {
        init_tracing();
        let files = std::path::PathBuf::from(&files_dir);
        let cache = std::path::PathBuf::from(&cache_dir);
        if !files.is_absolute() || !cache.is_absolute() {
            tracing::warn!(files = %files.display(), cache = %cache.display(), "app directories must be absolute");
            return Err(BridgeError::InvalidDirectory);
        }
        let dirs = AppDirs { config: files.join("config"), data: files.join("data"), cache };
        for dir in [&dirs.config, &dirs.data, &dirs.cache] {
            std::fs::create_dir_all(dir).map_err(|error| {
                tracing::error!(
                    path = %dir.display(),
                    error = &error as &dyn std::error::Error,
                    "cannot create an app directory"
                );
                BridgeError::Directory { reason: error.to_string() }
            })?;
        }
        let settings = settings::SettingsHost::open(&dirs.config)?;
        let library = library::LibraryHost::open(&dirs.data.join("library.db"))?;
        let online = online::OnlineHost::start(&dirs.cache)?;
        let covers = covers::CoverHost::start(online.cover_fetcher());
        let artwork = artwork_fetcher(online.cover_fetcher());
        let playback = playback::PlaybackHost::start(online.audio_fetcher(), online.lyrics_fetcher(), artwork)?;
        playback.set_volume(settings.volume())?;
        playback.set_repeat(repeat_mode(settings.repeat()))?;
        playback.set_equalizer(settings.equalizer())?;
        playback.set_quality(settings.audio_quality())?;
        online.restore(settings.online_provider());
        tracing::info!(data = %dirs.data.display(), "bridge initialized");
        Ok(std::sync::Arc::new(Self { dirs, settings, library, online, covers, playback }))
    }

    pub fn text(&self, language_tag: String, key: String) -> Result<String, BridgeError> {
        let language = LanguagePreference::System.resolve(Some(&language_tag));
        let i18n = I18n::new(language).map_err(|error| {
            tracing::error!(error = &error as &dyn std::error::Error, "translation initialization failed");
            BridgeError::Translation { reason: error.to_string() }
        })?;
        Ok(i18n.t(&key).to_owned())
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_owned()
    }

    pub fn set_music_folder(&self, path: String) -> Result<(), BridgeError> {
        self.library.set_folder(&path)
    }

    pub fn music_folder(&self) -> Result<String, BridgeError> {
        self.library.folder()
    }

    pub fn start_scan(&self) -> Result<(), BridgeError> {
        self.library.start_scan()
    }

    pub fn scan_snapshot(&self) -> ScanSnapshot {
        self.library.snapshot()
    }

    pub fn tracks(&self) -> Result<Vec<LibraryTrack>, BridgeError> {
        self.library.tracks()
    }

    pub fn track(&self, id: i64) -> Result<Option<LibraryTrack>, BridgeError> {
        self.library.track(id)
    }

    pub fn cover(&self, track_id: i64, edge: i32) -> Result<Option<CoverImage>, BridgeError> {
        self.library.cover(track_id, edge)
    }

    pub fn play_library(&self, start_index: i32) -> Result<(), BridgeError> {
        let tracks = self.library.tracks()?;
        self.playback.play_all(tracks, start_index)
    }

    pub fn play(&self) -> Result<(), BridgeError> {
        self.playback.play()
    }

    pub fn pause(&self) -> Result<(), BridgeError> {
        self.playback.pause()
    }

    pub fn seek(&self, seconds: f64) -> Result<(), BridgeError> {
        self.playback.seek(seconds)
    }

    pub fn stop(&self) -> Result<(), BridgeError> {
        self.playback.stop()
    }

    pub fn set_volume(&self, volume: f32) -> Result<(), BridgeError> {
        self.playback.set_volume(volume)?;
        self.settings.set_playback(volume, self.settings.repeat())
    }

    /// Output gain that is not written to settings. Audio-focus ducking uses this.
    pub fn set_output_volume(&self, volume: f32) -> Result<(), BridgeError> {
        self.playback.set_volume(volume)
    }

    pub fn volume(&self) -> Result<f32, BridgeError> {
        self.playback.volume()
    }

    pub fn status(&self) -> Result<PlaybackSnapshot, BridgeError> {
        self.playback.status()
    }

    pub fn next(&self) -> Result<(), BridgeError> {
        self.playback.next()
    }

    pub fn previous(&self) -> Result<(), BridgeError> {
        self.playback.previous()
    }

    pub fn play_index(&self, index: i32) -> Result<(), BridgeError> {
        self.playback.play_index(index)
    }

    pub fn set_repeat(&self, mode: RepeatMode) -> Result<(), BridgeError> {
        self.playback.set_repeat(mode)?;
        self.settings.set_playback(self.playback.volume()?, core_repeat(mode))
    }

    pub fn cycle_repeat(&self) -> Result<RepeatMode, BridgeError> {
        let mode = self.playback.cycle_repeat()?;
        self.settings.set_playback(self.playback.volume()?, core_repeat(mode))?;
        Ok(mode)
    }

    pub fn queue(&self) -> Result<Vec<LibraryTrack>, BridgeError> {
        self.playback.queue()
    }

    pub fn play_tracks(&self, tracks: Vec<LibraryTrack>, start_index: i32) -> Result<(), BridgeError> {
        self.playback.play_tracks(tracks, start_index)
    }

    pub fn lyrics(&self) -> Result<LyricSnapshot, BridgeError> {
        self.playback.lyrics()
    }

    pub fn artwork(&self) -> Result<Option<Vec<u8>>, BridgeError> {
        self.playback.artwork()
    }

    pub fn set_system_locale(&self, tag: String) {
        self.settings.set_system_locale(&tag);
    }

    pub fn label(&self, key: String) -> String {
        self.settings.label(&key)
    }

    pub fn language_preference(&self) -> String {
        self.settings.language_preference()
    }

    pub fn set_language_preference(&self, value: String) -> Result<(), BridgeError> {
        self.settings.set_language_preference(&value)
    }

    pub fn home_tab(&self) -> String {
        self.settings.home_tab()
    }

    pub fn set_home_tab(&self, tab: String) -> Result<(), BridgeError> {
        self.settings.set_home_tab(&tab)
    }

    pub fn dark_theme(&self) -> bool {
        self.settings.dark_theme()
    }

    pub fn set_dark_theme(&self, dark: bool) -> Result<(), BridgeError> {
        self.settings.set_dark_theme(dark)
    }

    pub fn audio_quality(&self) -> String {
        self.settings.audio_quality().id().to_owned()
    }

    pub fn set_audio_quality(&self, id: String) -> Result<(), BridgeError> {
        self.settings.set_audio_quality(&id)?;
        self.playback.set_quality(self.settings.audio_quality())
    }

    pub fn equalizer(&self) -> EqualizerSnapshot {
        let settings = self.settings.equalizer();
        EqualizerSnapshot {
            enabled: settings.enabled,
            preset: settings.preset,
            gains: settings.gains.to_vec(),
            presets: self.settings.preset_ids(),
        }
    }

    pub fn set_equalizer_enabled(&self, enabled: bool) -> Result<(), BridgeError> {
        let mut settings = self.settings.equalizer();
        settings.enabled = enabled;
        self.store_equalizer(settings)
    }

    pub fn set_equalizer_preset(&self, id: String) -> Result<(), BridgeError> {
        let mut settings = self.settings.equalizer();
        if let Some(preset) = folia_core::preset(&id) {
            settings.preset = preset.id.to_owned();
            settings.gains = preset.gains;
            settings.effects = preset.effects;
            settings.enabled = true;
        }
        self.store_equalizer(settings)
    }

    pub fn set_equalizer_gain(&self, index: i32, gain_db: f32) -> Result<(), BridgeError> {
        let mut settings = self.settings.equalizer();
        if let Some(slot) = usize::try_from(index).ok().and_then(|index| settings.gains.get_mut(index)) {
            *slot = gain_db;
        }
        settings.preset = "custom1".to_owned();
        settings.gains = folia_core::EqualizerSettings::normalized_gains(&settings.gains);
        self.store_equalizer(settings)
    }

    pub fn platforms(&self) -> Vec<PlatformInfo> {
        self.online
            .platforms()
            .into_iter()
            .map(|platform| PlatformInfo {
                id: platform.id.to_owned(),
                name: platform.display_name.to_owned(),
                short_name: platform.short_name.to_owned(),
                letter: platform.letter.to_string(),
                color: platform.color,
                login_title: self.settings.label(platform.login_title),
                login_note: self.settings.label(platform.login_note),
            })
            .collect()
    }

    pub fn begin_login(&self, provider: String) {
        self.online.begin_login(&provider);
    }

    pub fn login_snapshot(&self) -> LoginSnapshot {
        let view = self.online.login();
        LoginSnapshot {
            phase: match view.phase {
                online::LoginPhase::Idle => LoginPhase::Idle,
                online::LoginPhase::Loading => LoginPhase::Loading,
                online::LoginPhase::Waiting => LoginPhase::Waiting,
                online::LoginPhase::Scanned => LoginPhase::Scanned,
                online::LoginPhase::Confirmed => LoginPhase::Confirmed,
                online::LoginPhase::Expired => LoginPhase::Expired,
                online::LoginPhase::Failed => LoginPhase::Failed,
            },
            width: i32::try_from(view.width).unwrap_or(0),
            modules: view.modules.into_iter().map(u8::from).collect(),
            message: view.message,
        }
    }

    pub fn session_snapshot(&self) -> SessionSnapshot {
        let view = self.online.session();
        if view.phase == online::SessionPhase::SignedIn && self.settings.online_provider() != view.provider {
            let _ = self.settings.set_online_provider(&view.provider);
        }
        SessionSnapshot {
            phase: match view.phase {
                online::SessionPhase::Resolving => SessionPhase::Resolving,
                online::SessionPhase::SignedOut => SessionPhase::SignedOut,
                online::SessionPhase::SignedIn => SessionPhase::SignedIn,
            },
            provider: view.provider,
            user_id: view.user_id,
            nickname: view.nickname,
        }
    }

    pub fn begin_logout(&self) {
        self.online.begin_logout();
    }

    pub fn begin_search(&self, query: String, offset: i32) {
        let provider = self.provider_id();
        self.online.begin_search(&provider, &query, u32::try_from(offset).unwrap_or(0));
    }

    pub fn search_snapshot(&self) -> SearchSnapshot {
        let view = self.online.search();
        SearchSnapshot {
            loading: view.loading,
            error: view.error,
            query: view.query,
            tracks: view.tracks.iter().map(online_track).collect(),
            has_more: view.has_more,
            next_offset: i32::try_from(view.next_offset).unwrap_or(i32::MAX),
        }
    }

    pub fn begin_home(&self, tab: String) {
        self.online.begin_feed(&tab);
    }

    pub fn home_snapshot(&self, tab: String) -> HomeSnapshot {
        let session = self.session_snapshot();
        if tab == "local" {
            return HomeSnapshot {
                session,
                loading: false,
                error: String::new(),
                cards: Vec::new(),
                explore: Vec::new(),
            };
        }
        let cards = match tab.as_str() {
            "albums" => self.online.albums().iter().map(|item| collection_card(item, &self.settings)).collect(),
            "radio" => radio_cards(&self.online, &self.settings),
            "explore" => Vec::new(),
            _ => self.online.playlists().iter().map(|item| collection_card(item, &self.settings)).collect(),
        };
        let explore = if tab == "explore" { explore_cards(&self.online, &self.settings) } else { Vec::new() };
        HomeSnapshot {
            session,
            loading: self.online.feed_loading(&tab),
            error: self.online.feed_error(&tab),
            cards,
            explore,
        }
    }

    pub fn begin_collection(&self, key: String) {
        self.online.begin_collection(&key);
    }

    pub fn collection_page(&self, key: String) -> CollectionPage {
        let slot = self.online.collection_tracks(&key);
        let title = self.online.collection(&key).map(|item| item.name).unwrap_or_default();
        CollectionPage {
            key,
            title,
            loading: slot.loading,
            error: slot.error,
            tracks: slot.value.unwrap_or_default().iter().map(online_track).collect(),
        }
    }

    pub fn personal_fm(&self) -> Vec<LibraryTrack> {
        self.online.radio().map(|feed| feed.personal_fm.iter().map(online_track).collect()).unwrap_or_default()
    }

    pub fn explore_tracks(&self, section: String) -> Vec<LibraryTrack> {
        self.online
            .explore()
            .into_iter()
            .find(|item| item.key == section)
            .map(|item| {
                item.entries
                    .iter()
                    .filter_map(|entry| match entry {
                        folia_omni::ExploreEntry::Song(track) => Some(online_track(track)),
                        folia_omni::ExploreEntry::Collection(_) => None,
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn request_covers(&self, asks: Vec<covers::CoverAsk>) {
        self.covers.request(asks);
    }

    pub fn cover_lookup(&self, key: String) -> covers::CoverLookup {
        self.covers.lookup(&key)
    }
}

impl FoliaBridge {
    fn store_equalizer(&self, settings: folia_core::EqualizerSettings) -> Result<(), BridgeError> {
        self.settings.set_equalizer(settings.clone())?;
        self.playback.set_equalizer(settings)
    }

    fn provider_id(&self) -> String {
        let session = self.online.session();
        if session.provider.is_empty() { "netease".to_owned() } else { session.provider }
    }
}

impl FoliaBridge {
    pub fn directories(&self) -> &AppDirs {
        &self.dirs
    }
}

type ArtworkFn = dyn Fn(&str, &str) -> Result<Vec<u8>, String> + Send + Sync;

fn artwork_fetcher(cover: std::sync::Arc<online::CoverFn>) -> std::sync::Arc<ArtworkFn> {
    std::sync::Arc::new(move |path: &str, url: &str| {
        if !path.is_empty() {
            return folia_library::cover_bytes(std::path::Path::new(path))
                .ok_or_else(|| "no embedded cover".to_owned());
        }
        if url.is_empty() {
            return Err("no cover".to_owned());
        }
        cover(url, 512)
    })
}

fn repeat_mode(mode: folia_core::Repeat) -> RepeatMode {
    match mode {
        folia_core::Repeat::Off => RepeatMode::Off,
        folia_core::Repeat::All => RepeatMode::All,
        folia_core::Repeat::One => RepeatMode::One,
    }
}

fn core_repeat(mode: RepeatMode) -> folia_core::Repeat {
    match mode {
        RepeatMode::Off => folia_core::Repeat::Off,
        RepeatMode::All => folia_core::Repeat::All,
        RepeatMode::One => folia_core::Repeat::One,
    }
}

fn online_track(track: &folia_core::Track) -> LibraryTrack {
    let (provider, online_id, id) = match &track.online {
        Some(online) => (online.provider.clone(), online.id.clone(), online.track_id()),
        None => (String::new(), String::new(), track.id),
    };
    LibraryTrack {
        id,
        title: track.title.clone(),
        artist: track.artist.clone().unwrap_or_default(),
        album: track.album.clone().unwrap_or_default(),
        duration_seconds: track.duration,
        path: track.path.to_string_lossy().into_owned(),
        has_cover: track.cover_url.is_some(),
        provider,
        online_id,
        cover_url: track.cover_url.clone().unwrap_or_default(),
    }
}

fn collection_card(collection: &folia_omni::Collection, settings: &settings::SettingsHost) -> CollectionCard {
    let kind = match collection.kind {
        folia_omni::CollectionKind::Album => "album",
        folia_omni::CollectionKind::DailySongs => "daily",
        folia_omni::CollectionKind::PersonalFm => "fm",
        folia_omni::CollectionKind::Playlist => "playlist",
    };
    let fallback = match kind {
        "album" => "home.albums",
        "fm" => "home.personalFm",
        "daily" => "home.dailyRecommendations",
        _ => "home.playlists",
    };
    let subtitle = collection
        .creator
        .clone()
        .or(collection.description.clone())
        .filter(|text| !text.is_empty())
        .unwrap_or_else(|| settings.label(fallback));
    CollectionCard {
        key: online::collection_key(collection),
        title: if collection.name.is_empty() { settings.label(fallback) } else { collection.name.clone() },
        subtitle,
        cover_url: collection.cover_url.clone().unwrap_or_default(),
        kind: kind.to_owned(),
        track_count: collection.track_count.map(|count| i32::try_from(count).unwrap_or(i32::MAX)).unwrap_or(0),
    }
}

fn radio_cards(online: &online::OnlineHost, settings: &settings::SettingsHost) -> Vec<CollectionCard> {
    let Some(feed) = online.radio() else { return Vec::new() };
    let provider = online.session().provider;
    let mut cards = Vec::new();
    if !feed.personal_fm.is_empty() {
        let collection = folia_omni::Collection {
            provider: provider.clone(),
            id: "personal_fm".to_owned(),
            kind: folia_omni::CollectionKind::PersonalFm,
            name: settings.label("home.personalFm"),
            cover_url: feed.personal_fm.first().and_then(|track| track.cover_url.clone()),
            track_count: None,
            creator: None,
            description: Some(settings.label("home.radio")),
            owned: false,
        };
        online.remember(collection.clone());
        cards.push(collection_card(&collection, settings));
    }
    if online.has_daily(&provider) && !feed.daily_songs.is_empty() {
        let collection = folia_omni::Collection {
            provider: provider.clone(),
            id: "daily_recommendations".to_owned(),
            kind: folia_omni::CollectionKind::DailySongs,
            name: settings.label("home.dailyRecommendations"),
            cover_url: feed.daily_songs.first().and_then(|track| track.cover_url.clone()),
            track_count: u32::try_from(feed.daily_songs.len()).ok(),
            creator: None,
            description: Some(settings.label("home.dailyRecommendationsDescription")),
            owned: false,
        };
        online.remember(collection.clone());
        cards.push(collection_card(&collection, settings));
    }
    for collection in &feed.recommended {
        online.remember(collection.clone());
        cards.push(collection_card(collection, settings));
    }
    cards
}

fn explore_cards(online: &online::OnlineHost, settings: &settings::SettingsHost) -> Vec<ExploreCard> {
    let mut cards = Vec::new();
    for section in online.explore() {
        let heading = match &section.shelf {
            folia_omni::Shelf::DailySongs => settings.label("home.dailyRecommendations"),
            folia_omni::Shelf::DailyPlaylists => settings.label("home.neteaseDailyPlaylists"),
            folia_omni::Shelf::Charts => settings.label("home.neteaseCharts"),
            folia_omni::Shelf::Block(name) => name.clone(),
        };
        let mut song_index = 0i32;
        for entry in &section.entries {
            match entry {
                folia_omni::ExploreEntry::Song(track) => {
                    cards.push(ExploreCard {
                        key: section.key.clone(),
                        title: track.title.clone(),
                        subtitle: track.artist.clone().unwrap_or_else(|| settings.label("player.unknownArtist")),
                        cover_url: track.cover_url.clone().unwrap_or_default(),
                        section: section.key.clone(),
                        playable: true,
                        track_index: song_index,
                    });
                    song_index += 1;
                }
                folia_omni::ExploreEntry::Collection(collection) => cards.push(ExploreCard {
                    key: online::collection_key(collection),
                    title: collection.name.clone(),
                    subtitle: heading.clone(),
                    cover_url: collection.cover_url.clone().unwrap_or_default(),
                    section: section.key.clone(),
                    playable: false,
                    track_index: -1,
                }),
            }
        }
    }
    cards
}

pub(crate) fn init_tracing() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let filter = std::env::var("FOLIA_LOG")
            .ok()
            .and_then(|value| tracing_subscriber::EnvFilter::try_new(value).ok())
            .unwrap_or_else(|| tracing_subscriber::EnvFilter::new("info"));
        let _ = tracing_subscriber::fmt().with_env_filter(filter).with_target(true).try_init();
    });
}
