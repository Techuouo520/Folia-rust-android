// Online music on a tokio runtime. Callers start a job and poll; nothing here blocks them.
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use folia_core::{AudioQuality, Lyrics, OnlineId, Track};
use folia_omni::{
    Account, Collection, CollectionKind, ExploreEntry, ExploreSection, LoginQr, LoginState, Omni, OmniError, Page,
    Platform, RadioFeed,
};
use parking_lot::Mutex;

use crate::BridgeError;

pub(crate) type CoverFn = dyn Fn(&str, u32) -> Result<Vec<u8>, String> + Send + Sync;
pub(crate) type AudioFn = dyn Fn(&str, &str, AudioQuality) -> Result<PathBuf, String> + Send + Sync;
pub(crate) type LyricsFn = dyn Fn(&str, &str) -> Result<Option<Lyrics>, String> + Send + Sync;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LoginPhase {
    Idle,
    Loading,
    Waiting,
    Scanned,
    Confirmed,
    Expired,
    Failed,
}

#[derive(Clone, Debug)]
pub(crate) struct LoginView {
    pub phase: LoginPhase,
    pub width: usize,
    pub modules: Vec<bool>,
    pub message: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SessionPhase {
    Resolving,
    SignedOut,
    SignedIn,
}

#[derive(Clone, Debug)]
pub(crate) struct SessionView {
    pub phase: SessionPhase,
    pub provider: String,
    pub user_id: String,
    pub nickname: String,
}

#[derive(Clone, Debug)]
pub(crate) struct SearchView {
    pub loading: bool,
    pub error: String,
    pub query: String,
    pub tracks: Vec<Track>,
    pub has_more: bool,
    pub next_offset: u32,
}

#[derive(Clone, Debug)]
pub(crate) struct Slot<T> {
    pub(crate) loading: bool,
    pub(crate) error: String,
    pub(crate) value: Option<T>,
}

impl<T> Default for Slot<T> {
    fn default() -> Self {
        Self { loading: false, error: String::new(), value: None }
    }
}

struct State {
    login_generation: u64,
    login: LoginView,
    session: SessionView,
    search_generation: u64,
    search: SearchView,
    playlists: Slot<Vec<Collection>>,
    albums: Slot<Vec<Collection>>,
    radio: Slot<RadioFeed>,
    explore: Slot<Vec<ExploreSection>>,
    collections: HashMap<String, Collection>,
    open: HashMap<String, Slot<Vec<Track>>>,
}

impl State {
    fn new() -> Self {
        Self {
            login_generation: 0,
            login: LoginView { phase: LoginPhase::Idle, width: 0, modules: Vec::new(), message: String::new() },
            session: SessionView {
                phase: SessionPhase::Resolving,
                provider: String::new(),
                user_id: String::new(),
                nickname: String::new(),
            },
            search_generation: 0,
            search: SearchView {
                loading: false,
                error: String::new(),
                query: String::new(),
                tracks: Vec::new(),
                has_more: false,
                next_offset: 0,
            },
            playlists: Slot::default(),
            albums: Slot::default(),
            radio: Slot::default(),
            explore: Slot::default(),
            collections: HashMap::new(),
            open: HashMap::new(),
        }
    }
}

pub(crate) struct OnlineHost {
    omni: Omni,
    runtime: tokio::runtime::Runtime,
    state: Arc<Mutex<State>>,
}

impl OnlineHost {
    pub(crate) fn start(cache: &Path) -> Result<Self, BridgeError> {
        let runtime =
            tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().map_err(|error| {
                tracing::error!(error = &error as &dyn std::error::Error, "cannot start the online runtime");
                BridgeError::Online { reason: error.to_string() }
            })?;
        let omni = Omni::new(cache.to_owned(), "Folia".to_owned()).map_err(online_err)?;
        tracing::info!(providers = omni.providers().len(), "online runtime ready");
        Ok(Self { omni, runtime, state: Arc::new(Mutex::new(State::new())) })
    }

    pub(crate) fn cover_fetcher(&self) -> Arc<CoverFn> {
        let omni = self.omni.clone();
        let handle = self.runtime.handle().clone();
        Arc::new(move |url: &str, px: u32| {
            let omni = omni.clone();
            let url = url.to_owned();
            handle.block_on(async move {
                let path = omni.fetch_cover(&url, px.max(1)).await.map_err(|error| error.to_string())?;
                std::fs::read(&path).map_err(|error| error.to_string())
            })
        })
    }

    pub(crate) fn audio_fetcher(&self) -> Arc<AudioFn> {
        let omni = self.omni.clone();
        let handle = self.runtime.handle().clone();
        Arc::new(move |provider: &str, song: &str, quality: AudioQuality| {
            let omni = omni.clone();
            let song = OnlineId { provider: provider.to_owned(), id: song.to_owned() };
            handle.block_on(async move { omni.fetch_audio(&song, quality).await.map_err(|error| error.to_string()) })
        })
    }

    pub(crate) fn lyrics_fetcher(&self) -> Arc<LyricsFn> {
        let omni = self.omni.clone();
        let handle = self.runtime.handle().clone();
        Arc::new(move |provider: &str, song: &str| {
            let omni = omni.clone();
            let song = OnlineId { provider: provider.to_owned(), id: song.to_owned() };
            handle.block_on(async move { omni.lyrics(&song).await.map_err(|error| error.to_string()) })
        })
    }

    pub(crate) fn platforms(&self) -> Vec<Platform> {
        self.omni.platforms()
    }

    pub(crate) fn restore(&self, preferred: String) {
        let omni = self.omni.clone();
        let state = Arc::clone(&self.state);
        self.runtime.spawn(async move {
            let ids = blocking(omni.clone(), move |omni| {
                let mut ids: Vec<String> = omni
                    .providers()
                    .into_iter()
                    .filter_map(|(id, _)| match omni.restore_session(id) {
                        Ok(true) => Some(id.to_owned()),
                        Ok(false) => None,
                        Err(error) => {
                            tracing::warn!(provider = id, error = &error as &dyn std::error::Error, "session restore failed");
                            None
                        }
                    })
                    .collect();
                ids.sort_by_key(|id| if *id == preferred { 0 } else { 1 });
                ids
            })
            .await
            .unwrap_or_default();
            for id in ids {
                match omni.account(&id).await {
                    Ok(Some(account)) => {
                        let playlists = omni.user_playlists(&id, &account.id).await.unwrap_or_else(|error| {
                            tracing::warn!(provider = %id, error = &error as &dyn std::error::Error, "playlists were not read");
                            Vec::new()
                        });
                        let mut guard = state.lock();
                        remember_all(&mut guard, &playlists);
                        guard.playlists = Slot { loading: false, error: String::new(), value: Some(playlists) };
                        guard.session = session_in(&id, &account);
                        tracing::info!(provider = %id, user = %account.id, "session restored");
                        return;
                    }
                    Ok(None) => {
                        let omni = omni.clone();
                        let id_for_forget = id.clone();
                        let _ = blocking(omni, move |omni| omni.forget_session(&id_for_forget).ok()).await;
                    }
                    Err(error) => {
                        tracing::warn!(provider = %id, error = &error as &dyn std::error::Error, "account read failed");
                    }
                }
            }
            state.lock().session.phase = SessionPhase::SignedOut;
            tracing::info!("no online session");
        });
    }

    pub(crate) fn begin_login(&self, provider: &str) {
        let generation = {
            let mut guard = self.state.lock();
            guard.login_generation = guard.login_generation.wrapping_add(1);
            guard.login =
                LoginView { phase: LoginPhase::Loading, width: 0, modules: Vec::new(), message: String::new() };
            guard.login_generation
        };
        let omni = self.omni.clone();
        let state = Arc::clone(&self.state);
        let provider = provider.to_owned();
        self.runtime.spawn(async move {
            let qr = match omni.login_qr(&provider).await {
                Ok(qr) => qr,
                Err(error) => {
                    fail_login(&state, generation, &error);
                    return;
                }
            };
            if !current(&state, generation) {
                return;
            }
            store_qr(&state, &qr);
            loop {
                if !current(&state, generation) {
                    return;
                }
                let state_now = match omni.check_login(&provider, &qr.key).await {
                    Ok(state_now) => state_now,
                    Err(error) => {
                        fail_login(&state, generation, &error);
                        return;
                    }
                };
                if !current(&state, generation) {
                    return;
                }
                match state_now {
                    LoginState::Waiting => state.lock().login.phase = LoginPhase::Waiting,
                    LoginState::Scanned => state.lock().login.phase = LoginPhase::Scanned,
                    LoginState::Confirmed => {
                        match blocking(omni.clone(), {
                            let provider = provider.clone();
                            move |omni| omni.save_session(&provider)
                        })
                        .await
                        {
                            Ok(Ok(())) => {}
                            Ok(Err(error)) => {
                                fail_login(&state, generation, &error);
                                return;
                            }
                            Err(()) => {
                                let mut guard = state.lock();
                                if guard.login_generation == generation {
                                    guard.login.phase = LoginPhase::Failed;
                                    guard.login.message = "keyring".to_owned();
                                }
                                return;
                            }
                        }
                        match omni.account(&provider).await {
                            Ok(Some(account)) => {
                                let mut guard = state.lock();
                                guard.session = session_in(&provider, &account);
                                guard.login.phase = LoginPhase::Confirmed;
                                guard.login.message = account.nickname.clone();
                                tracing::info!(provider = %provider, user = %account.id, "signed in");
                            }
                            Ok(None) => {
                                let mut guard = state.lock();
                                guard.login.phase = LoginPhase::Failed;
                                guard.login.message = "account".to_owned();
                            }
                            Err(error) => fail_login(&state, generation, &error),
                        }
                        return;
                    }
                    LoginState::Expired => {
                        state.lock().login.phase = LoginPhase::Expired;
                        return;
                    }
                    LoginState::Failed { message, .. } => {
                        let mut guard = state.lock();
                        guard.login.phase = LoginPhase::Failed;
                        guard.login.message = message.unwrap_or_default();
                        return;
                    }
                }
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            }
        });
    }

    pub(crate) fn login(&self) -> LoginView {
        self.state.lock().login.clone()
    }

    pub(crate) fn session(&self) -> SessionView {
        self.state.lock().session.clone()
    }

    pub(crate) fn begin_logout(&self) {
        let provider = self.state.lock().session.provider.clone();
        if provider.is_empty() {
            self.state.lock().session = signed_out();
            return;
        }
        let omni = self.omni.clone();
        let state = Arc::clone(&self.state);
        self.runtime.spawn(async move {
            if let Err(error) = omni.logout(&provider).await {
                tracing::warn!(provider = %provider, error = &error as &dyn std::error::Error, "platform logout failed");
            }
            let _ = blocking(omni, {
                let provider = provider.clone();
                move |omni| omni.forget_session(&provider).ok()
            })
            .await;
            let mut guard = state.lock();
            guard.session = signed_out();
            guard.playlists = Slot::default();
            guard.albums = Slot::default();
            guard.radio = Slot::default();
            guard.explore = Slot::default();
            guard.collections.clear();
            guard.open.clear();
            tracing::info!(provider = %provider, "signed out");
        });
    }

    pub(crate) fn begin_search(&self, provider: &str, query: &str, offset: u32) {
        let query = query.to_owned();
        let provider = provider.to_owned();
        let generation = {
            let mut guard = self.state.lock();
            guard.search_generation = guard.search_generation.wrapping_add(1);
            if offset == 0 {
                guard.search.tracks.clear();
            }
            guard.search.loading = true;
            guard.search.error.clear();
            guard.search.query = query.clone();
            guard.search_generation
        };
        let omni = self.omni.clone();
        let state = Arc::clone(&self.state);
        self.runtime.spawn(async move {
            let page = omni.search(&provider, &query, 20, offset).await;
            let mut guard = state.lock();
            if guard.search_generation != generation {
                return;
            }
            guard.search.loading = false;
            match page {
                Ok(page) => apply_page(&mut guard.search, page, offset),
                Err(error) => {
                    tracing::warn!(error = &error as &dyn std::error::Error, "search failed");
                    guard.search.error = error.to_string();
                }
            }
        });
    }

    pub(crate) fn search(&self) -> SearchView {
        self.state.lock().search.clone()
    }

    pub(crate) fn begin_feed(&self, kind: &str) {
        let (provider, user) = {
            let mut guard = self.state.lock();
            if guard.session.phase != SessionPhase::SignedIn {
                return;
            }
            if feed_ready(&guard, kind) {
                return;
            }
            match kind {
                "albums" => {
                    guard.albums.loading = true;
                    guard.albums.error.clear();
                }
                "radio" => {
                    guard.radio.loading = true;
                    guard.radio.error.clear();
                }
                "explore" => {
                    guard.explore.loading = true;
                    guard.explore.error.clear();
                }
                _ => {
                    guard.playlists.loading = true;
                    guard.playlists.error.clear();
                }
            }
            (guard.session.provider.clone(), guard.session.user_id.clone())
        };
        let omni = self.omni.clone();
        let state = Arc::clone(&self.state);
        let kind = kind.to_owned();
        self.runtime.spawn(async move {
            let result = load_feed(&omni, &provider, &user, &kind).await;
            let mut guard = state.lock();
            match result {
                Ok(loaded) => store_feed(&mut guard, &kind, loaded),
                Err(error) => {
                    tracing::warn!(kind = %kind, error = &error as &dyn std::error::Error, "home feed failed");
                    store_feed_error(&mut guard, &kind, error.to_string());
                }
            }
        });
    }

    pub(crate) fn feed_loading(&self, kind: &str) -> bool {
        let guard = self.state.lock();
        match kind {
            "albums" => guard.albums.loading,
            "radio" => guard.radio.loading,
            "explore" => guard.explore.loading,
            _ => guard.playlists.loading,
        }
    }

    pub(crate) fn feed_error(&self, kind: &str) -> String {
        let guard = self.state.lock();
        match kind {
            "albums" => guard.albums.error.clone(),
            "radio" => guard.radio.error.clone(),
            "explore" => guard.explore.error.clone(),
            _ => guard.playlists.error.clone(),
        }
    }

    pub(crate) fn playlists(&self) -> Vec<Collection> {
        self.state.lock().playlists.value.clone().unwrap_or_default()
    }

    pub(crate) fn albums(&self) -> Vec<Collection> {
        self.state.lock().albums.value.clone().unwrap_or_default()
    }

    pub(crate) fn radio(&self) -> Option<RadioFeed> {
        self.state.lock().radio.value.clone()
    }

    pub(crate) fn explore(&self) -> Vec<ExploreSection> {
        self.state.lock().explore.value.clone().unwrap_or_default()
    }

    pub(crate) fn begin_collection(&self, key: &str) {
        let Some(collection) = self.state.lock().collections.get(key).cloned() else {
            self.state
                .lock()
                .open
                .insert(key.to_owned(), Slot { loading: false, error: "unknown collection".to_owned(), value: None });
            return;
        };
        {
            let mut guard = self.state.lock();
            if guard.open.get(key).is_some_and(|slot| slot.loading || slot.value.is_some()) {
                return;
            }
            guard.open.insert(key.to_owned(), Slot { loading: true, error: String::new(), value: None });
        }
        let omni = self.omni.clone();
        let state = Arc::clone(&self.state);
        let key = key.to_owned();
        self.runtime.spawn(async move {
            let result = omni.collection_detail(&collection).await;
            let mut guard = state.lock();
            let slot = guard.open.entry(key).or_default();
            slot.loading = false;
            match result {
                Ok(detail) => slot.value = Some(detail.tracks),
                Err(error) => {
                    tracing::warn!(error = &error as &dyn std::error::Error, "collection read failed");
                    slot.error = error.to_string();
                }
            }
        });
    }

    pub(crate) fn collection_tracks(&self, key: &str) -> Slot<Vec<Track>> {
        self.state.lock().open.get(key).cloned().unwrap_or_default()
    }

    pub(crate) fn collection(&self, key: &str) -> Option<Collection> {
        self.state.lock().collections.get(key).cloned()
    }

    pub(crate) fn remember(&self, collection: Collection) {
        let key = collection_key(&collection);
        self.state.lock().collections.insert(key, collection);
    }

    pub(crate) fn has_daily(&self, provider: &str) -> bool {
        self.omni.has_daily_songs(provider)
    }
}

enum Loaded {
    Playlists(Vec<Collection>),
    Albums(Vec<Collection>),
    Radio(RadioFeed),
    Explore(Vec<ExploreSection>),
}

async fn load_feed(omni: &Omni, provider: &str, user: &str, kind: &str) -> Result<Loaded, OmniError> {
    match kind {
        "albums" => Ok(Loaded::Albums(omni.favorite_albums(provider).await?)),
        "radio" => Ok(Loaded::Radio(omni.radio_feed(provider, 35).await?)),
        "explore" => Ok(Loaded::Explore(omni.explore(provider).await?)),
        _ => Ok(Loaded::Playlists(omni.user_playlists(provider, user).await?)),
    }
}

fn store_feed(state: &mut State, _kind: &str, loaded: Loaded) {
    match loaded {
        Loaded::Playlists(items) => {
            remember_all(state, &items);
            state.playlists = Slot { loading: false, error: String::new(), value: Some(items) };
        }
        Loaded::Albums(items) => {
            remember_all(state, &items);
            state.albums = Slot { loading: false, error: String::new(), value: Some(items) };
        }
        Loaded::Radio(feed) => {
            remember_all(state, &feed.recommended);
            state.radio = Slot { loading: false, error: String::new(), value: Some(feed) };
        }
        Loaded::Explore(sections) => {
            for section in &sections {
                for entry in &section.entries {
                    if let ExploreEntry::Collection(collection) = entry {
                        state.collections.insert(collection_key(collection), collection.clone());
                    }
                }
            }
            state.explore = Slot { loading: false, error: String::new(), value: Some(sections) };
        }
    }
}

fn store_feed_error(state: &mut State, kind: &str, error: String) {
    match kind {
        "albums" => {
            state.albums.loading = false;
            state.albums.error = error;
        }
        "radio" => {
            state.radio.loading = false;
            state.radio.error = error;
        }
        "explore" => {
            state.explore.loading = false;
            state.explore.error = error;
        }
        _ => {
            state.playlists.loading = false;
            state.playlists.error = error;
        }
    }
}

fn online_err(error: OmniError) -> crate::BridgeError {
    tracing::error!(error = &error as &dyn std::error::Error, "online setup failed");
    crate::BridgeError::Online { reason: error.to_string() }
}

fn feed_ready(state: &State, kind: &str) -> bool {
    match kind {
        "albums" => state.albums.loading || state.albums.value.is_some(),
        "radio" => state.radio.loading || state.radio.value.is_some(),
        "explore" => state.explore.loading || state.explore.value.is_some(),
        _ => state.playlists.loading || state.playlists.value.is_some(),
    }
}

pub(crate) fn collection_key(collection: &Collection) -> String {
    format!("{}\u{1f}{}\u{1f}{}", collection.provider, kind_name(collection.kind), collection.id)
}

fn kind_name(kind: CollectionKind) -> &'static str {
    match kind {
        CollectionKind::Playlist => "playlist",
        CollectionKind::Album => "album",
        CollectionKind::DailySongs => "daily",
        CollectionKind::PersonalFm => "fm",
    }
}

fn remember_all(state: &mut State, collections: &[Collection]) {
    for collection in collections {
        state.collections.insert(collection_key(collection), collection.clone());
    }
}

fn session_in(provider: &str, account: &Account) -> SessionView {
    SessionView {
        phase: SessionPhase::SignedIn,
        provider: provider.to_owned(),
        user_id: account.id.clone(),
        nickname: account.nickname.clone(),
    }
}

fn signed_out() -> SessionView {
    SessionView {
        phase: SessionPhase::SignedOut,
        provider: String::new(),
        user_id: String::new(),
        nickname: String::new(),
    }
}

fn current(state: &Mutex<State>, generation: u64) -> bool {
    state.lock().login_generation == generation
}

fn store_qr(state: &Mutex<State>, qr: &LoginQr) {
    let mut guard = state.lock();
    guard.login.phase = LoginPhase::Waiting;
    guard.login.width = qr.width;
    guard.login.modules = qr.modules.clone();
    guard.login.message.clear();
}

fn fail_login(state: &Mutex<State>, generation: u64, error: &OmniError) {
    tracing::warn!(error = error as &dyn std::error::Error, "sign-in failed");
    let mut guard = state.lock();
    if guard.login_generation != generation {
        return;
    }
    guard.login.phase = LoginPhase::Failed;
    guard.login.message = error_text(error);
}

fn error_text(error: &OmniError) -> String {
    let mut text = error.to_string();
    let mut source = std::error::Error::source(error);
    while let Some(next) = source {
        text.push_str(": ");
        text.push_str(&next.to_string());
        source = std::error::Error::source(next);
    }
    text
}

fn apply_page(search: &mut SearchView, page: Page<Track>, offset: u32) {
    if offset == 0 {
        search.tracks = page.items;
    } else {
        search.tracks.extend(page.items);
    }
    search.has_more = page.has_more;
    search.next_offset = page.next_offset;
}

async fn blocking<T: Send + 'static>(omni: Omni, job: impl FnOnce(Omni) -> T + Send + 'static) -> Result<T, ()> {
    match tokio::task::spawn_blocking(move || job(omni)).await {
        Ok(value) => Ok(value),
        Err(_) => {
            tracing::error!("blocking online task stopped");
            Err(())
        }
    }
}
