// Background scan of one music folder into the library database under the app data directory.
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use folia_library::{Library, LibraryError, ScanProgress, ScanReport, ScanStep, has_cover};
use parking_lot::Mutex;

use crate::{BridgeError, CoverImage, LibraryTrack, ScanPhase, ScanSnapshot};

const DEFAULT_MUSIC_FOLDER: &str = "/storage/emulated/0/Music";

struct LibraryInner {
    library: Library,
    covers: HashMap<i64, bool>,
}

pub(crate) struct LibraryHost {
    inner: Arc<Mutex<LibraryInner>>,
    snapshot: Arc<Mutex<ScanSnapshot>>,
    running: Arc<AtomicBool>,
}

struct ScanGuard {
    running: Arc<AtomicBool>,
    snapshot: Arc<Mutex<ScanSnapshot>>,
}

impl Drop for ScanGuard {
    fn drop(&mut self) {
        self.running.store(false, Ordering::Release);
        let mut snapshot = self.snapshot.lock();
        if snapshot.phase == ScanPhase::Running {
            snapshot.phase = ScanPhase::Failed;
            if snapshot.error.is_empty() {
                snapshot.error = "scan interrupted".to_owned();
            }
            tracing::error!("library scan interrupted");
        }
    }
}

impl LibraryHost {
    pub(crate) fn open(db: &Path) -> Result<Self, BridgeError> {
        let library = Library::open(db).map_err(lib_err)?;
        Ok(Self {
            inner: Arc::new(Mutex::new(LibraryInner { library, covers: HashMap::new() })),
            snapshot: Arc::new(Mutex::new(ScanSnapshot::default())),
            running: Arc::new(AtomicBool::new(false)),
        })
    }

    pub(crate) fn folder(&self) -> Result<String, BridgeError> {
        let guard = self.inner.lock();
        let folders = guard.library.folders().map_err(lib_err)?;
        match folders.into_iter().next() {
            Some(path) => path_string(&path),
            None => Ok(DEFAULT_MUSIC_FOLDER.to_owned()),
        }
    }

    pub(crate) fn set_folder(&self, path: &str) -> Result<(), BridgeError> {
        let folder = PathBuf::from(path);
        if path.is_empty() || !folder.is_absolute() {
            tracing::warn!(path, "music folder must be absolute");
            return Err(BridgeError::InvalidDirectory);
        }
        let mut guard = self.inner.lock();
        let existing = guard.library.folders().map_err(lib_err)?;
        for old in existing {
            if old != folder {
                guard.library.remove_folder(&old).map_err(lib_err)?;
            }
        }
        guard.library.add_folder(&folder).map_err(lib_err)?;
        tracing::info!(folder = %folder.display(), "music folder set");
        Ok(())
    }

    pub(crate) fn start_scan(&self) -> Result<(), BridgeError> {
        if self.running.swap(true, Ordering::AcqRel) {
            tracing::info!("library scan already running");
            return Ok(());
        }
        {
            let mut snapshot = self.snapshot.lock();
            snapshot.phase = ScanPhase::Running;
            snapshot.error.clear();
        }
        let inner = Arc::clone(&self.inner);
        let snapshot = Arc::clone(&self.snapshot);
        let running = Arc::clone(&self.running);
        let spawned = std::thread::Builder::new().name("folia-scan".into()).spawn(move || {
            let guard = ScanGuard { running, snapshot: Arc::clone(&snapshot) };
            let outcome = scan_folder(&inner, &snapshot);
            publish(&snapshot, outcome);
            drop(guard);
        });
        if let Err(error) = spawned {
            self.running.store(false, Ordering::Release);
            let mut snapshot = self.snapshot.lock();
            snapshot.phase = ScanPhase::Failed;
            snapshot.error = error.to_string();
            tracing::error!(error = &error as &dyn std::error::Error, "cannot start library scan");
            return Err(BridgeError::Library { reason: error.to_string() });
        }
        tracing::info!("library scan started");
        Ok(())
    }

    pub(crate) fn snapshot(&self) -> ScanSnapshot {
        self.snapshot.lock().clone()
    }

    pub(crate) fn tracks(&self) -> Result<Vec<LibraryTrack>, BridgeError> {
        let guard = self.inner.lock();
        let tracks = guard.library.tracks().map_err(lib_err)?;
        tracks.into_iter().map(|track| to_track(&guard.covers, track)).collect()
    }

    pub(crate) fn track(&self, track_id: i64) -> Result<Option<LibraryTrack>, BridgeError> {
        let guard = self.inner.lock();
        let Some(track) = guard.library.track(track_id).map_err(lib_err)? else { return Ok(None) };
        Ok(Some(to_track(&guard.covers, track)?))
    }

    pub(crate) fn cover(&self, track_id: i64, edge: i32) -> Result<Option<CoverImage>, BridgeError> {
        let path = {
            let guard = self.inner.lock();
            guard.library.track(track_id).map_err(lib_err)?.map(|track| track.path)
        };
        let Some(path) = path else {
            tracing::info!(track_id, "cover requested for an unknown track");
            return Ok(None);
        };
        let edge = u32::try_from(edge).unwrap_or(1).max(1);
        let Some(cover) = folia_library::cover_for(&path, edge, edge) else {
            return Ok(None);
        };
        Ok(Some(CoverImage {
            width: i32::try_from(cover.width).unwrap_or(i32::MAX),
            height: i32::try_from(cover.height).unwrap_or(i32::MAX),
            rgba: cover.rgba,
        }))
    }
}

fn scan_folder(inner: &Mutex<LibraryInner>, snapshot: &Mutex<ScanSnapshot>) -> Result<ScanReport, BridgeError> {
    let mut guard = inner.lock();
    if guard.library.folders().map_err(lib_err)?.is_empty() {
        guard.library.add_folder(Path::new(DEFAULT_MUSIC_FOLDER)).map_err(lib_err)?;
        tracing::info!(folder = DEFAULT_MUSIC_FOLDER, "using the default music folder");
    }
    let report = guard.library.scan_with(|progress| note_progress(snapshot, &progress)).map_err(lib_err)?;
    let tracks = guard.library.tracks().map_err(lib_err)?;
    let mut covers = HashMap::with_capacity(tracks.len());
    for track in &tracks {
        covers.insert(track.id, has_cover(&track.path));
    }
    guard.covers = covers;
    Ok(report)
}

fn note_progress(snapshot: &Mutex<ScanSnapshot>, progress: &ScanProgress) {
    let mut current = snapshot.lock();
    current.current_file = progress.path.display().to_string();
    match progress.step {
        ScanStep::Found => current.seen = as_i32(progress.index),
        ScanStep::Reading => {
            current.reading = as_i32(progress.index);
            current.reading_total = progress.total.map(as_i32).unwrap_or(0);
        }
    }
}

fn publish(snapshot: &Mutex<ScanSnapshot>, outcome: Result<ScanReport, BridgeError>) {
    let mut current = snapshot.lock();
    match outcome {
        Ok(report) => {
            current.phase = ScanPhase::Done;
            current.updated = as_i32(report.updated);
            current.removed = as_i32(report.removed);
            current.total = as_i32(report.total);
            current.error.clear();
            tracing::info!(
                updated = current.updated,
                removed = current.removed,
                total = current.total,
                "library scan finished"
            );
        }
        Err(error) => {
            current.phase = ScanPhase::Failed;
            current.error = error.to_string();
        }
    }
}

fn to_track(covers: &HashMap<i64, bool>, track: folia_core::Track) -> Result<LibraryTrack, BridgeError> {
    Ok(LibraryTrack {
        id: track.id,
        title: track.title,
        artist: track.artist.unwrap_or_default(),
        album: track.album.unwrap_or_default(),
        duration_seconds: track.duration,
        path: path_string(&track.path)?,
        has_cover: covers.get(&track.id).copied().unwrap_or(false),
        provider: String::new(),
        online_id: String::new(),
        cover_url: String::new(),
    })
}

fn path_string(path: &Path) -> Result<String, BridgeError> {
    path.to_str().map(str::to_owned).ok_or_else(|| {
        tracing::error!(path = %path.display(), "library path is not valid UTF-8");
        BridgeError::Library { reason: format!("path is not valid UTF-8: {}", path.display()) }
    })
}

fn lib_err(error: LibraryError) -> BridgeError {
    tracing::error!(error = &error as &dyn std::error::Error, "library request failed");
    BridgeError::Library { reason: error.to_string() }
}

fn as_i32(value: usize) -> i32 {
    i32::try_from(value).unwrap_or(i32::MAX)
}
