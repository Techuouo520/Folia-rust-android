// Minimal platform-directory, translation and version interface for Android.
use std::path::PathBuf;
use std::sync::Arc;

use folia_core::LanguagePreference;
use folia_i18n::I18n;
use folia_store::AppDirs;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum BridgeError {
    #[error("application directories must be absolute")]
    InvalidDirectory,
    #[error("directory initialization failed: {reason}")]
    Directory { reason: String },
    #[error("translation initialization failed: {reason}")]
    Translation { reason: String },
}

#[derive(uniffi::Object)]
pub struct FoliaBridge {
    dirs: AppDirs,
}

#[uniffi::export]
impl FoliaBridge {
    #[uniffi::constructor]
    pub fn initialize(files_dir: String, cache_dir: String) -> Result<Arc<Self>, BridgeError> {
        let files = PathBuf::from(files_dir);
        let cache = PathBuf::from(cache_dir);
        if !files.is_absolute() || !cache.is_absolute() {
            return Err(BridgeError::InvalidDirectory);
        }
        let dirs = AppDirs { config: files.join("config"), data: files.join("data"), cache };
        for dir in [&dirs.config, &dirs.data, &dirs.cache] {
            std::fs::create_dir_all(dir).map_err(|error| BridgeError::Directory { reason: error.to_string() })?;
        }
        Ok(Arc::new(Self { dirs }))
    }

    pub fn text(&self, language_tag: String, key: String) -> Result<String, BridgeError> {
        let language = LanguagePreference::System.resolve(Some(&language_tag));
        let i18n = I18n::new(language).map_err(|error| BridgeError::Translation { reason: error.to_string() })?;
        Ok(i18n.t(&key).to_owned())
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_owned()
    }
}

impl FoliaBridge {
    pub fn directories(&self) -> &AppDirs {
        &self.dirs
    }
}
