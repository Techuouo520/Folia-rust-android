// Decoded covers for the wall, kept under a 48 MB budget. A request returns at once; workers decode it.
use std::collections::{HashSet, VecDeque};
use std::path::Path;
use std::sync::Arc;
use std::thread::JoinHandle;

use folia_library::{CoverCache, cover_bytes};
use parking_lot::{Condvar, Mutex};

const COVER_BYTES: usize = 48 * 1024 * 1024;
const WORKERS: usize = 4;

#[derive(Clone, Debug, uniffi::Record)]
pub struct CoverAsk {
    pub key: String,
    pub path: String,
    pub url: String,
    pub width: i32,
    pub height: i32,
}

#[derive(Clone, Debug, uniffi::Enum)]
pub enum CoverLookup {
    Pending,
    Missing,
    // Encoded image bytes. The phone decodes them at the tile size.
    Ready { bytes: Vec<u8> },
}

#[derive(Clone)]
enum Cached {
    Missing,
    Ready { bytes: Vec<u8> },
}

struct Queue {
    items: VecDeque<CoverAsk>,
    closed: bool,
}

type CoverFn = dyn Fn(&str, u32) -> Result<Vec<u8>, String> + Send + Sync;

pub(crate) struct CoverHost {
    cache: Arc<Mutex<CoverCache<String, Cached>>>,
    pending: Arc<Mutex<HashSet<String>>>,
    queue: Arc<(Mutex<Queue>, Condvar)>,
    workers: Vec<JoinHandle<()>>,
}

impl CoverHost {
    pub(crate) fn start(fetch_url: Arc<CoverFn>) -> Self {
        let cache = Arc::new(Mutex::new(CoverCache::new(COVER_BYTES)));
        let pending = Arc::new(Mutex::new(HashSet::new()));
        let queue = Arc::new((Mutex::new(Queue { items: VecDeque::new(), closed: false }), Condvar::new()));
        let mut workers = Vec::new();
        for index in 0..WORKERS {
            let cache = Arc::clone(&cache);
            let pending = Arc::clone(&pending);
            let queue = Arc::clone(&queue);
            let fetch_url = Arc::clone(&fetch_url);
            let spawned = std::thread::Builder::new().name(format!("folia-cover-{index}")).spawn(move || {
                loop {
                    let ask = {
                        let (lock, wake) = &*queue;
                        let mut guard = lock.lock();
                        while guard.items.is_empty() && !guard.closed {
                            wake.wait(&mut guard);
                        }
                        if guard.closed && guard.items.is_empty() {
                            return;
                        }
                        guard.items.pop_front()
                    };
                    let Some(ask) = ask else { continue };
                    let decoded = load_bytes(&ask, fetch_url.as_ref());
                    let bytes = match &decoded {
                        Cached::Ready { bytes } => bytes.len(),
                        Cached::Missing => 0,
                    };
                    cache.lock().insert(ask.key.clone(), Some(decoded), bytes);
                    pending.lock().remove(&ask.key);
                }
            });
            match spawned {
                Ok(handle) => workers.push(handle),
                Err(error) => tracing::error!(error = &error as &dyn std::error::Error, "cannot start a cover worker"),
            }
        }
        Self { cache, pending, queue, workers }
    }

    pub(crate) fn request(&self, asks: Vec<CoverAsk>) {
        let mut queue = self.queue.0.lock();
        let mut pending = self.pending.lock();
        let cache = self.cache.lock();
        let mut added = false;
        for ask in asks {
            if ask.key.is_empty() || cache.peek(&ask.key).is_some() || !pending.insert(ask.key.clone()) {
                continue;
            }
            queue.items.push_back(ask);
            added = true;
        }
        drop(cache);
        drop(pending);
        if added {
            self.queue.1.notify_all();
        }
    }

    pub(crate) fn lookup(&self, key: &str) -> CoverLookup {
        let mut cache = self.cache.lock();
        let found = cache.peek(&key.to_owned()).cloned();
        if matches!(found, Some(Some(Cached::Ready { .. }))) {
            cache.touch(&key.to_owned());
        }
        match found {
            Some(Some(Cached::Ready { bytes })) => CoverLookup::Ready { bytes },
            Some(Some(Cached::Missing)) | Some(None) => CoverLookup::Missing,
            None => CoverLookup::Pending,
        }
    }
}

impl Drop for CoverHost {
    fn drop(&mut self) {
        self.queue.0.lock().closed = true;
        self.queue.1.notify_all();
        for worker in self.workers.drain(..) {
            if worker.join().is_err() {
                tracing::error!("cover worker panicked");
            }
        }
    }
}

fn load_bytes(ask: &CoverAsk, fetch_url: &CoverFn) -> Cached {
    let edge = u32::try_from(ask.width.max(ask.height)).unwrap_or(1).max(1);
    let bytes = if !ask.path.is_empty() {
        cover_bytes(Path::new(&ask.path))
    } else if !ask.url.is_empty() {
        match fetch_url(&ask.url, edge) {
            Ok(bytes) if !bytes.is_empty() => Some(bytes),
            Ok(_) => None,
            Err(error) => {
                tracing::warn!(key = %ask.key, error, "cover download failed");
                None
            }
        }
    } else {
        None
    };
    match bytes {
        Some(bytes) => Cached::Ready { bytes },
        None => Cached::Missing,
    }
}
